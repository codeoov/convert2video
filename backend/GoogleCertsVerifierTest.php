<?php

/**
 * GoogleCertsVerifierTest — CLI-only, no PHPUnit required.
 *
 * Usage:
 *   php backend/GoogleCertsVerifierTest.php
 *
 * Generates RSA key pairs locally; no real Google URL is called.
 * Exits 0 on all PASS, exits 1 if any FAIL.
 */

// On Windows, PHP's openssl extension may not find openssl.cnf automatically.
// Locate the config file and pass it explicitly to openssl_pkey_new via 'config' key.
$opensslCnf = null;
if (DIRECTORY_SEPARATOR === '\\') {
    $candidates = [
        'C:\\xampp\\php\\extras\\ssl\\openssl.cnf',
        'C:\\Program Files\\Common Files\\SSL\\openssl.cnf',
        'C:\\OpenSSL-Win64\\bin\\openssl.cnf',
        'C:\\OpenSSL-Win32\\bin\\openssl.cnf',
    ];
    foreach ($candidates as $candidate) {
        if (is_file($candidate)) {
            $opensslCnf = $candidate;
            break;
        }
    }
}

/**
 * Build the openssl_pkey_new config array, injecting 'config' on Windows if needed.
 */
function opensslKeyConfig(array $extra = []): array
{
    global $opensslCnf;
    $base = ['private_key_bits' => 2048, 'private_key_type' => OPENSSL_KEYTYPE_RSA];
    if ($opensslCnf !== null) {
        $base['config'] = $opensslCnf;
    }
    return array_merge($base, $extra);
}

require_once __DIR__ . '/src/GoogleCertsVerifier.php';
require_once __DIR__ . '/src/HuaweiIapVerifier.php';
require_once __DIR__ . '/src/Response.php';
require_once __DIR__ . '/src/Controllers/EntitlementController.php';
require_once __DIR__ . '/src/Controllers/WebhookController.php';
require_once __DIR__ . '/src/EntitlementJwtIssuer.php';

// ── Helpers ─────────────────────────────────────────────────────────────────

function base64urlEncodeTest(string $data): string
{
    return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
}

function makeJwt(array $header, array $payload, string $privateKeyPem): string
{
    $headerB64  = base64urlEncodeTest(json_encode($header));
    $payloadB64 = base64urlEncodeTest(json_encode($payload));
    $signingInput = $headerB64 . '.' . $payloadB64;
    $sig = '';
    openssl_sign($signingInput, $sig, $privateKeyPem, OPENSSL_ALGO_SHA256);
    return $signingInput . '.' . base64urlEncodeTest($sig);
}

final class EmptyNonEofStreamWrapper
{
    public function stream_open(string $path, string $mode, int $options, ?string &$openedPath): bool
    {
        return true;
    }

    public function stream_read(int $count): string
    {
        return '';
    }

    public function stream_eof(): bool
    {
        return false;
    }

    public function stream_stat(): array
    {
        return [];
    }
}

// ── Key generation ───────────────────────────────────────────────────────────

$keyRes = openssl_pkey_new(opensslKeyConfig());
if ($keyRes === false) {
    echo "FAIL: could not generate RSA key\n";
    exit(1);
}
openssl_pkey_export($keyRes, $privateKeyPem, null, opensslKeyConfig());
$pubDetails   = openssl_pkey_get_details($keyRes);
$publicKeyPem = $pubDetails['key'];

// Alternate key for signature-mismatch test
$altKeyRes = openssl_pkey_new(opensslKeyConfig());
openssl_pkey_export($altKeyRes, $altPrivateKeyPem, null, opensslKeyConfig());

$kid   = 'test-kid-abc';
$email = 'pubsub-rtdn@my-project.iam.gserviceaccount.com';
$audience = 'https://api.example.com/webhooks/rtdn';

// Fetcher injects our test public key — no network call
$fetcher = static function () use ($kid, $publicKeyPem): array {
    return [$kid => $publicKeyPem];
};

// ── Counters ─────────────────────────────────────────────────────────────────

$passCount = 0;
$failCount = 0;

function assertCase(string $name, bool $actual, bool $expected): void
{
    global $passCount, $failCount;
    if ($actual === $expected) {
        echo "PASS: $name\n";
        $passCount++;
    } else {
        $exp = $expected ? 'true' : 'false';
        $got = $actual   ? 'true' : 'false';
        echo "FAIL: $name (expected=$exp, got=$got)\n";
        $failCount++;
    }
}

// ── Base valid payload ────────────────────────────────────────────────────────

$validHeader = ['alg' => 'RS256', 'typ' => 'JWT', 'kid' => $kid];

$validPayload = [
    'iss'            => 'https://accounts.google.com',
    'email'          => $email,
    'email_verified' => true,
    'exp'            => time() + 3600,
    'iat'            => time(),
    'sub'            => 'user-subject-id',
    'aud'            => $audience,
];

// ── Case 1: Valid JWT → true ──────────────────────────────────────────────────

$jwt1     = makeJwt($validHeader, $validPayload, $privateKeyPem);
$result1  = (new GoogleCertsVerifier($fetcher))->verify($jwt1, $email, $audience);
assertCase('Valid JWT → true', $result1, true);

// ── Case 2: Signature mismatch (signed with different key) → false ────────────

$jwt2    = makeJwt($validHeader, $validPayload, $altPrivateKeyPem);
$result2 = (new GoogleCertsVerifier($fetcher))->verify($jwt2, $email, $audience);
assertCase('Signature mismatch → false', $result2, false);

// ── Case 3: Expired token (exp in the past) → false ──────────────────────────

$expiredPayload = array_merge($validPayload, ['exp' => time() - 1]);
$jwt3           = makeJwt($validHeader, $expiredPayload, $privateKeyPem);
$result3        = (new GoogleCertsVerifier($fetcher))->verify($jwt3, $email, $audience);
assertCase('Expired token → false', $result3, false);

// ── Case 4: Email mismatch → false ───────────────────────────────────────────

$jwt4    = makeJwt($validHeader, $validPayload, $privateKeyPem);
$result4 = (new GoogleCertsVerifier($fetcher))->verify($jwt4, 'wrong@example.com', $audience);
assertCase('Email mismatch → false', $result4, false);

// ── Case 5: email_verified = false → false ────────────────────────────────────

$unverifiedPayload = array_merge($validPayload, ['email_verified' => false]);
$jwt5              = makeJwt($validHeader, $unverifiedPayload, $privateKeyPem);
$result5           = (new GoogleCertsVerifier($fetcher))->verify($jwt5, $email, $audience);
assertCase('email_verified false → false', $result5, false);

// ── Case 6: Missing audience → false ────────────────────────────────────────

$missingAudiencePayload = $validPayload;
unset($missingAudiencePayload['aud']);
$jwt6 = makeJwt($validHeader, $missingAudiencePayload, $privateKeyPem);
$result6 = (new GoogleCertsVerifier($fetcher))->verify($jwt6, $email, $audience);
assertCase('Missing audience → false', $result6, false);

// ── Case 7: Wrong audience → false ───────────────────────────────────────────

$wrongAudiencePayload = array_merge($validPayload, ['aud' => 'https://wrong.example.com/rtdn']);
$jwt7 = makeJwt($validHeader, $wrongAudiencePayload, $privateKeyPem);
$result7 = (new GoogleCertsVerifier($fetcher))->verify($jwt7, $email, $audience);
assertCase('Wrong audience → false', $result7, false);

// ── Case 8: Audience array → false ──────────────────────────────────────────

$arrayAudiencePayload = array_merge($validPayload, ['aud' => [$audience]]);
$jwt8 = makeJwt($validHeader, $arrayAudiencePayload, $privateKeyPem);
$result8 = (new GoogleCertsVerifier($fetcher))->verify($jwt8, $email, $audience);
assertCase('Audience array → false', $result8, false);

// ── Case 9: Audience non-string → false ─────────────────────────────────────

$numericAudiencePayload = array_merge($validPayload, ['aud' => 123]);
$jwt9 = makeJwt($validHeader, $numericAudiencePayload, $privateKeyPem);
$result9 = (new GoogleCertsVerifier($fetcher))->verify($jwt9, $email, $audience);
assertCase('Audience non-string → false', $result9, false);

// ── Case 10: Header alg mismatch → false ─────────────────────────────────────

$wrongAlgHeader = array_merge($validHeader, ['alg' => 'HS256']);
$jwt10 = makeJwt($wrongAlgHeader, $validPayload, $privateKeyPem);
$result10 = (new GoogleCertsVerifier($fetcher))->verify($jwt10, $email, $audience);
assertCase('Header alg mismatch → false', $result10, false);

// ── Case 11: Missing header typ → false ──────────────────────────────────────

$missingTypHeader = $validHeader;
unset($missingTypHeader['typ']);
$jwt11 = makeJwt($missingTypHeader, $validPayload, $privateKeyPem);
$result11 = (new GoogleCertsVerifier($fetcher))->verify($jwt11, $email, $audience);
assertCase('Missing header typ → false', $result11, false);

// ── Case 12: Header array / non-string algorithm → false ─────────────────────

$arrayHeaderJwt = makeJwt(['RS256', 'JWT', $kid], $validPayload, $privateKeyPem);
$result12 = (new GoogleCertsVerifier($fetcher))->verify($arrayHeaderJwt, $email, $audience);
assertCase('Header JSON array → false', $result12, false);

$nonStringAlgHeader = array_merge($validHeader, ['alg' => ['RS256']]);
$jwt13 = makeJwt($nonStringAlgHeader, $validPayload, $privateKeyPem);
$result13 = (new GoogleCertsVerifier($fetcher))->verify($jwt13, $email, $audience);
assertCase('Header non-string alg → false', $result13, false);

$nonStringTypHeader = array_merge($validHeader, ['typ' => ['JWT']]);
$jwt14 = makeJwt($nonStringTypHeader, $validPayload, $privateKeyPem);
$result14 = (new GoogleCertsVerifier($fetcher))->verify($jwt14, $email, $audience);
assertCase('Header non-string typ → false', $result14, false);

// ── Huawei pure response contract cases ─────────────────────────────────────

function assertStatusCase(string $name, array $actual, string $expected): void
{
    assertCase($name, ($actual['status'] ?? null) === $expected, true);
}

$huaweiAppId = 'synthetic-app-id';
$huaweiPackage = 'com.example.convert2video';
$huaweiProduct = 'pro_lifetime_unlock';
$huaweiToken = 'synthetic-purchase-token';
$huaweiPurchase = [
    'applicationId' => $huaweiAppId,
    'packageName' => $huaweiPackage,
    'productId' => $huaweiProduct,
    'purchaseToken' => $huaweiToken,
    'orderId' => 'synthetic-order-id',
    'purchaseTime' => (int)(microtime(true) * 1000),
    'purchaseState' => 0,
];
$huaweiBodyWithoutExpiry = [
    'responseCode' => 0,
    'purchaseTokenData' => json_encode($huaweiPurchase),
];
$huaweiResult = HuaweiIapVerifier::validateProviderResponse(
    $huaweiBodyWithoutExpiry,
    $huaweiAppId,
    $huaweiPackage,
    $huaweiProduct,
    $huaweiToken
);
assertStatusCase('Huawei missing expiry is malformed by default', $huaweiResult, HuaweiIapVerifier::MALFORMED);

$huaweiResult = HuaweiIapVerifier::validateProviderResponse(
    $huaweiBodyWithoutExpiry,
    $huaweiAppId,
    $huaweiPackage,
    $huaweiProduct,
    $huaweiToken
);
assertStatusCase('Huawei missing expiry remains malformed without bypass', $huaweiResult, HuaweiIapVerifier::MALFORMED);

$activePurchase = array_merge($huaweiPurchase, [
    'expirationDate' => (int)(microtime(true) * 1000) + 3600000,
]);
$activeBody = [
    'responseCode' => 0,
    'purchaseTokenData' => json_encode($activePurchase),
];
$huaweiResult = HuaweiIapVerifier::validateProviderResponse(
    $activeBody,
    $huaweiAppId,
    $huaweiPackage,
    $huaweiProduct,
    $huaweiToken
);
assertStatusCase('Huawei active purchase requires valid expiry', $huaweiResult, HuaweiIapVerifier::ACTIVE);

$expiredBody = [
    'responseCode' => 0,
    'purchaseTokenData' => json_encode(array_merge($huaweiPurchase, [
        'expirationDate' => (int)(microtime(true) * 1000) - 1,
    ])),
];
$huaweiResult = HuaweiIapVerifier::validateProviderResponse(
    $expiredBody,
    $huaweiAppId,
    $huaweiPackage,
    $huaweiProduct,
    $huaweiToken
);
assertStatusCase('Huawei expired active purchase is revoked', $huaweiResult, HuaweiIapVerifier::REVOKED);

$invalidExpiryBody = [
    'responseCode' => 0,
    'purchaseTokenData' => json_encode(array_merge($huaweiPurchase, ['expirationDate' => 'not-a-timestamp'])),
];
$huaweiResult = HuaweiIapVerifier::validateProviderResponse(
    $invalidExpiryBody,
    $huaweiAppId,
    $huaweiPackage,
    $huaweiProduct,
    $huaweiToken
);
assertStatusCase('Huawei invalid expiry is malformed', $huaweiResult, HuaweiIapVerifier::MALFORMED);

$secondsExpiryBody = [
    'responseCode' => 0,
    'purchaseTokenData' => json_encode(array_merge($huaweiPurchase, [
        'expirationDate' => time() + 3600,
    ])),
];
$huaweiResult = HuaweiIapVerifier::validateProviderResponse(
    $secondsExpiryBody,
    $huaweiAppId,
    $huaweiPackage,
    $huaweiProduct,
    $huaweiToken
);
assertStatusCase('Huawei seconds expiry is rejected; field is milliseconds', $huaweiResult, HuaweiIapVerifier::MALFORMED);

$floatStatePurchase = array_merge($huaweiPurchase, ['purchaseState' => 0.0]);
$floatStateBody = [
    'responseCode' => 0,
    'purchaseTokenData' => json_encode($floatStatePurchase, JSON_PRESERVE_ZERO_FRACTION),
];
$huaweiResult = HuaweiIapVerifier::validateProviderResponse(
    $floatStateBody,
    $huaweiAppId,
    $huaweiPackage,
    $huaweiProduct,
    $huaweiToken
);
assertStatusCase('Huawei float purchase state is malformed', $huaweiResult, HuaweiIapVerifier::MALFORMED);

$missingResponseCodeBody = ['purchaseTokenData' => json_encode($huaweiPurchase)];
$huaweiResult = HuaweiIapVerifier::validateProviderResponse(
    $missingResponseCodeBody,
    $huaweiAppId,
    $huaweiPackage,
    $huaweiProduct,
    $huaweiToken
);
assertStatusCase('Huawei missing responseCode is malformed', $huaweiResult, HuaweiIapVerifier::MALFORMED);

$malformedResponseCodeBody = [
    'responseCode' => 'not-a-code',
    'purchaseTokenData' => json_encode($huaweiPurchase),
];
$huaweiResult = HuaweiIapVerifier::validateProviderResponse(
    $malformedResponseCodeBody,
    $huaweiAppId,
    $huaweiPackage,
    $huaweiProduct,
    $huaweiToken
);
assertStatusCase('Huawei malformed responseCode is malformed', $huaweiResult, HuaweiIapVerifier::MALFORMED);

$canceledBody = [
    'responseCode' => 0,
    'purchaseTokenData' => json_encode(array_merge($huaweiPurchase, ['purchaseState' => 1])),
];
$huaweiResult = HuaweiIapVerifier::validateProviderResponse(
    $canceledBody,
    $huaweiAppId,
    $huaweiPackage,
    $huaweiProduct,
    $huaweiToken
);
assertStatusCase('Huawei canceled purchase is revoked', $huaweiResult, HuaweiIapVerifier::REVOKED);
assertStatusCase('Huawei revoke/cancel terminal mapping is explicit', $huaweiResult, HuaweiIapVerifier::REVOKED);

$canceledWithoutExpiryBody = [
    'responseCode' => 0,
    'purchaseTokenData' => json_encode(array_merge($huaweiPurchase, ['purchaseState' => 1])),
];
$huaweiResult = HuaweiIapVerifier::validateProviderResponse(
    $canceledWithoutExpiryBody,
    $huaweiAppId,
    $huaweiPackage,
    $huaweiProduct,
    $huaweiToken
);
assertStatusCase('Huawei canceled purchase is terminal before expiry validation', $huaweiResult, HuaweiIapVerifier::REVOKED);

$refundedBody = [
    'responseCode' => 0,
    'purchaseTokenData' => json_encode(array_merge($huaweiPurchase, ['purchaseState' => 2])),
];
$huaweiResult = HuaweiIapVerifier::validateProviderResponse(
    $refundedBody,
    $huaweiAppId,
    $huaweiPackage,
    $huaweiProduct,
    $huaweiToken
);
assertStatusCase('Huawei refunded purchase is refunded', $huaweiResult, HuaweiIapVerifier::REFUNDED);
assertStatusCase('Huawei refund/chargeback terminal mapping is explicit', $huaweiResult, HuaweiIapVerifier::REFUNDED);

$refundedWithInvalidExpiryBody = [
    'responseCode' => 0,
    'purchaseTokenData' => json_encode(array_merge($huaweiPurchase, [
        'purchaseState' => 2,
        'expirationDate' => 'not-a-timestamp',
    ])),
];
$huaweiResult = HuaweiIapVerifier::validateProviderResponse(
    $refundedWithInvalidExpiryBody,
    $huaweiAppId,
    $huaweiPackage,
    $huaweiProduct,
    $huaweiToken
);
assertStatusCase('Huawei refunded purchase ignores expiry field', $huaweiResult, HuaweiIapVerifier::REFUNDED);

$mismatchedBody = [
    'responseCode' => 0,
    'purchaseTokenData' => json_encode(array_merge($huaweiPurchase, ['productId' => 'other-product'])),
];
$huaweiResult = HuaweiIapVerifier::validateProviderResponse(
    $mismatchedBody,
    $huaweiAppId,
    $huaweiPackage,
    $huaweiProduct,
    $huaweiToken
);
assertStatusCase('Huawei product mismatch is rejected from active', $huaweiResult, HuaweiIapVerifier::MISMATCH);

$voidResponseBody = [
    'responseCode' => 1001,
    'purchaseTokenData' => json_encode($activePurchase),
];
$huaweiResult = HuaweiIapVerifier::validateProviderResponse(
    $voidResponseBody,
    $huaweiAppId,
    $huaweiPackage,
    $huaweiProduct,
    $huaweiToken
);
assertStatusCase('Huawei explicit void/invalid response is rejected', $huaweiResult, HuaweiIapVerifier::REJECTED);

$unknownStatePurchase = array_merge($huaweiPurchase, ['purchaseState' => 3]);
$unknownStateBody = [
    'responseCode' => 0,
    'purchaseTokenData' => json_encode($unknownStatePurchase),
];
$huaweiResult = HuaweiIapVerifier::validateProviderResponse(
    $unknownStateBody,
    $huaweiAppId,
    $huaweiPackage,
    $huaweiProduct,
    $huaweiToken
);
assertStatusCase('Huawei unknown purchase state is malformed', $huaweiResult, HuaweiIapVerifier::MALFORMED);

assertCase(
    'Huawei provider 5xx is unavailable',
    HuaweiIapVerifier::classifyHttpResult(503) === HuaweiIapVerifier::UNAVAILABLE,
    true
);
assertCase(
    'Huawei provider 429 is unavailable',
    HuaweiIapVerifier::classifyHttpResult(429) === HuaweiIapVerifier::UNAVAILABLE,
    true
);
assertCase(
    'Huawei provider timeout is unavailable',
    HuaweiIapVerifier::classifyHttpResult(0, true) === HuaweiIapVerifier::UNAVAILABLE,
    true
);
assertCase(
    'Huawei explicit provider invalid response is rejected',
    HuaweiIapVerifier::classifyHttpResult(400) === HuaweiIapVerifier::REJECTED,
    true
);

// ── Refresh candidate identity/product-set cases ────────────────────────────

$productFilterMethod = new ReflectionMethod(EntitlementController::class, 'configuredProductFilter');
$productFilterMethod->setAccessible(true);
$configuredProductFilter = $productFilterMethod->invoke(null, [
    'play_product_id' => 'google_fixture_product',
    'huawei_product_id' => 'huawei_fixture_product',
]);
assertCase(
    'Refresh filter includes both configured store/product pairs',
    $configuredProductFilter['params'] === [
        'google_play',
        'google_fixture_product',
        'huawei',
        'huawei_fixture_product',
    ],
    true
);

$sameIdentityMethod = new ReflectionMethod(EntitlementController::class, 'sameCandidateIdentities');
$sameIdentityMethod->setAccessible(true);
$multiStoreCandidates = [
    ['store' => 'google_play', 'purchase_token' => 'fixture-google-token', 'product_id' => 'google_fixture_product'],
    ['store' => 'huawei', 'purchase_token' => 'fixture-huawei-token', 'product_id' => 'huawei_fixture_product'],
];
assertCase(
    'Refresh preserves multi-store different-product candidate identities',
    $sameIdentityMethod->invoke(null, $multiStoreCandidates, array_reverse($multiStoreCandidates)),
    true
);

// ── Migration guard contract cases ──────────────────────────────────────────

$migration002 = file_get_contents(__DIR__ . '/db/002_purchase_events_store_token.sql');
$migration003 = file_get_contents(__DIR__ . '/db/003_purchase_events_drop_legacy_unique.sql');
assertCase(
    '002 migration validates unique ordered two-column index and duplicate guard',
    is_string($migration002)
        && strpos($migration002, 'NON_UNIQUE') !== false
        && strpos($migration002, 'SEQ_IN_INDEX') !== false
        && strpos($migration002, "COLUMN_NAME = 'store'") !== false
        && strpos($migration002, "COLUMN_NAME = 'purchase_token'") !== false
        && strpos($migration002, 'c2v_duplicate_identity_groups') !== false,
    true
);
assertCase(
    '003 migration rejects wrong named legacy/composite indexes',
    is_string($migration003)
        && strpos($migration003, 'NON_UNIQUE') !== false
        && strpos($migration003, 'SEQ_IN_INDEX') !== false
        && strpos($migration003, 'c2v_wrong_legacy_index_guard_failure') !== false,
    true
);

$filterWithMalformedProduct = $productFilterMethod->invoke(null, [
    'play_product_id' => 'google fixture product',
    'huawei_product_id' => 'huawei_fixture_product',
]);
assertCase(
    'Refresh rejects malformed configured product values',
    $filterWithMalformedProduct['sql'] === '' && $filterWithMalformedProduct['params'] === [],
    true
);

$wrongPlayRow = ['product_id' => 'huawei_fixture_product'];
$playProductMethod = new ReflectionMethod(WebhookController::class, 'isConfiguredPlayProduct');
$playProductMethod->setAccessible(true);
assertCase(
    'RTDN wrong product does not qualify for Google row update',
    $playProductMethod->invoke(null, $wrongPlayRow, 'google_fixture_product'),
    false
);
assertCase(
    'RTDN configured product qualifies for Google row update',
    $playProductMethod->invoke(null, ['product_id' => 'google_fixture_product'], 'google_fixture_product'),
    true
);

$entitlementSource = file_get_contents(__DIR__ . '/src/Controllers/EntitlementController.php');
$webhookSource = file_get_contents(__DIR__ . '/src/Controllers/WebhookController.php');
assertCase(
    'Refresh checks update row count and composite identity confirmation',
    is_string($entitlementSource)
        && strpos($entitlementSource, 'rowCount()') !== false
        && strpos($entitlementSource, '$currentProductId = $currentRow[\'product_id\'];') !== false
        && strpos($entitlementSource, 'WHERE id = ? AND store = ? AND purchase_token = ?') !== false
        && strpos($entitlementSource, 'Failed to confirm entitlement state') !== false,
    true
);
assertCase(
    'RTDN raw payload policy retains only redacted marker and processed state',
    is_string($webhookSource)
        && strpos($webhookSource, '$dbToken = null;') !== false
        && strpos($webhookSource, '$redactedPayload') !== false
        && strpos($webhookSource, 'processed = $parsed ? 1 : 0') !== false,
    true
);
assertCase(
    'Entitlement and RTDN body readers map oversize separately from I/O',
    is_string($entitlementSource)
        && is_string($webhookSource)
        && strpos($entitlementSource, "\$readResult['status'] === 'oversize'") !== false
        && strpos($webhookSource, "\$readResult['status'] === 'oversize'") !== false
        && strpos($entitlementSource, "Response::error('Request body too large', 413)") !== false
        && strpos($webhookSource, "Response::error('Request body too large', 413)") !== false
        && strpos($entitlementSource, "Response::error('Request body could not be read', 500)") !== false
        && strpos($webhookSource, "Response::error('Request body could not be read', 500)") !== false,
    true
);

assertCase(
    'Both store product IDs must be valid for refresh/JWT configuration',
    EntitlementJwtIssuer::hasValidProductConfig([
        'play_product_id' => 'google_fixture_product',
        'huawei_product_id' => 'huawei_fixture_product',
    ]),
    true
);
assertCase(
    'Empty Google product fails closed',
    EntitlementJwtIssuer::hasValidProductConfig([
        'play_product_id' => '',
        'huawei_product_id' => 'huawei_fixture_product',
    ]),
    false
);
assertCase(
    'Malformed Huawei product fails closed',
    EntitlementJwtIssuer::hasValidProductConfig([
        'play_product_id' => 'google_fixture_product',
        'huawei_product_id' => 'huawei fixture product',
    ]),
    false
);

// ── JWT basic date-shape cases ───────────────────────────────────────────────

$jwtPrivateKeyPath = tempnam(sys_get_temp_dir(), 'c2v-jwt-');
if ($jwtPrivateKeyPath === false || file_put_contents($jwtPrivateKeyPath, $privateKeyPem) === false) {
    echo "FAIL: could not prepare generated JWT key fixture\n";
    exit(1);
}
$jwtConfig = [
    'entitlement_jwt_private_key_path' => $jwtPrivateKeyPath,
    'play_product_id' => $huaweiProduct,
    'huawei_product_id' => 'other-product',
];
$jwtHeader = ['alg' => 'RS256', 'typ' => 'JWT'];
$jwtUuid = '123e4567-e89b-12d3-a456-426614174000';
$jwtIat = time();
$jwtPayload = [
    'sub' => $jwtUuid,
    'pro' => true,
    'iat' => $jwtIat,
    'exp' => $jwtIat + 3600,
    'product_id' => $huaweiProduct,
];
$validEntitlementJwt = makeJwt($jwtHeader, $jwtPayload, $privateKeyPem);
$validEntitlementPayload = EntitlementJwtIssuer::verifyIgnoringExpiry($jwtConfig, $validEntitlementJwt);
assertCase('JWT valid integer dates → true', is_array($validEntitlementPayload), true);

$expiredPayload = array_merge($jwtPayload, [
    'iat' => time() - 7200,
    'exp' => time() - 3600,
]);
$expiredEntitlementJwt = makeJwt($jwtHeader, $expiredPayload, $privateKeyPem);
$expiredEntitlementPayload = EntitlementJwtIssuer::verifyIgnoringExpiry($jwtConfig, $expiredEntitlementJwt);
assertCase('JWT expired signed candidate keeps valid shape', is_array($expiredEntitlementPayload), true);

$negativeIatPayload = array_merge($jwtPayload, ['iat' => -1]);
$negativeIatJwt = makeJwt($jwtHeader, $negativeIatPayload, $privateKeyPem);
assertCase(
    'JWT negative iat → false',
    EntitlementJwtIssuer::verifyIgnoringExpiry($jwtConfig, $negativeIatJwt) !== false,
    false
);

$floatDatePayload = array_merge($jwtPayload, ['iat' => (float)$jwtIat + 0.5]);
$floatDateJwt = makeJwt($jwtHeader, $floatDatePayload, $privateKeyPem);
assertCase(
    'JWT float iat → false',
    EntitlementJwtIssuer::verifyIgnoringExpiry($jwtConfig, $floatDateJwt) !== false,
    false
);

$equalDatesPayload = array_merge($jwtPayload, ['exp' => $jwtIat]);
$equalDatesJwt = makeJwt($jwtHeader, $equalDatesPayload, $privateKeyPem);
assertCase(
    'JWT exp equal to iat → false',
    EntitlementJwtIssuer::verifyIgnoringExpiry($jwtConfig, $equalDatesJwt) !== false,
    false
);

$longLifetimePayload = array_merge($jwtPayload, ['exp' => $jwtIat + 259201]);
$longLifetimeJwt = makeJwt($jwtHeader, $longLifetimePayload, $privateKeyPem);
assertCase(
    'JWT implausibly long lifetime → false',
    EntitlementJwtIssuer::verifyIgnoringExpiry($jwtConfig, $longLifetimeJwt) !== false,
    false
);

$stringDatePayload = array_merge($jwtPayload, ['exp' => (string)($jwtIat + 3600)]);
$stringDateJwt = makeJwt($jwtHeader, $stringDatePayload, $privateKeyPem);
assertCase(
    'JWT string exp → false',
    EntitlementJwtIssuer::verifyIgnoringExpiry($jwtConfig, $stringDateJwt) !== false,
    false
);
unlink($jwtPrivateKeyPath);

// ── Bounded body and RTDN parser cases ──────────────────────────────────────

$boundedEntitlementReader = new ReflectionMethod(EntitlementController::class, 'readBoundedStream');
$boundedEntitlementReader->setAccessible(true);
$exactStream = fopen('php://temp', 'w+b');
fwrite($exactStream, str_repeat('x', 8192));
fwrite($exactStream, str_repeat('y', 8192));
rewind($exactStream);
$exactBody = $boundedEntitlementReader->invoke(null, $exactStream, 16384);
fclose($exactStream);
assertCase('Entitlement bounded chunked body at limit', is_array($exactBody)
    && $exactBody['status'] === 'ok' && strlen($exactBody['body']) === 16384, true);

$oversizeStream = fopen('php://temp', 'w+b');
fwrite($oversizeStream, str_repeat('z', 16385));
rewind($oversizeStream);
$oversizeBody = $boundedEntitlementReader->invoke(null, $oversizeStream, 16384);
fclose($oversizeStream);
assertCase('Entitlement bounded body distinguishes oversize', is_array($oversizeBody)
    && $oversizeBody['status'] === 'oversize', true);

$boundedWebhookReader = new ReflectionMethod(WebhookController::class, 'readBoundedStream');
$boundedWebhookReader->setAccessible(true);
$webhookStream = fopen('php://temp', 'w+b');
fwrite($webhookStream, str_repeat('q', 16385));
rewind($webhookStream);
$webhookOversizeBody = $boundedWebhookReader->invoke(null, $webhookStream, 16384);
fclose($webhookStream);
assertCase('Webhook bounded body distinguishes oversize', is_array($webhookOversizeBody)
    && $webhookOversizeBody['status'] === 'oversize', true);

stream_wrapper_register('c2v-empty', EmptyNonEofStreamWrapper::class);
$emptyStream = fopen('c2v-empty://bounded', 'rb');
$emptyReadBody = $boundedEntitlementReader->invoke(null, $emptyStream, 16384);
fclose($emptyStream);
$emptyWebhookStream = fopen('c2v-empty://bounded', 'rb');
$emptyWebhookBody = $boundedWebhookReader->invoke(null, $emptyWebhookStream, 16384);
fclose($emptyWebhookStream);
stream_wrapper_unregister('c2v-empty');
assertCase('Bounded readers distinguish repeated empty non-EOF reads', is_array($emptyReadBody)
    && $emptyReadBody['status'] === 'io', true);
assertCase('Webhook bounded reader distinguishes repeated empty non-EOF reads', is_array($emptyWebhookBody)
    && $emptyWebhookBody['status'] === 'io', true);

$notificationParser = new ReflectionMethod(WebhookController::class, 'parseNotification');
$notificationParser->setAccessible(true);
$validRtdnData = base64_encode(json_encode([
    'oneTimeProductNotification' => [
        'notificationType' => 2,
        'purchaseToken' => 'fixture-token',
    ],
]));
$validRtdnEnvelope = json_encode(['message' => ['data' => $validRtdnData]]);
$parsedRtdn = $notificationParser->invoke(null, $validRtdnEnvelope);
assertCase('RTDN valid notification parses', $parsedRtdn[2] === true && $parsedRtdn[0] === 2, true);

$stringTypeData = base64_encode(json_encode([
    'oneTimeProductNotification' => [
        'notificationType' => '2',
        'purchaseToken' => 'fixture-token',
    ],
]));
$stringTypeResult = $notificationParser->invoke(null, json_encode(['message' => ['data' => $stringTypeData]]));
assertCase('RTDN string notification type is malformed', $stringTypeResult[2] === true, false);

$controlTokenData = base64_encode(json_encode([
    'oneTimeProductNotification' => [
        'notificationType' => 2,
        'purchaseToken' => "bad\nvalue",
    ],
]));
$controlTokenResult = $notificationParser->invoke(null, json_encode(['message' => ['data' => $controlTokenData]]));
assertCase('RTDN control-character token is malformed', $controlTokenResult[2] === true, false);

$unknownEnvelopeResult = $notificationParser->invoke(null, '{"message":{}}');
assertCase('RTDN unknown envelope is safely ignored', $unknownEnvelopeResult[2] === true, false);

// ── Summary ──────────────────────────────────────────────────────────────────

echo "\n{$passCount} passed, {$failCount} failed.\n";
exit($failCount > 0 ? 1 : 0);
