<?php

/**
 * EntitlementControllerTest — CLI-only, no PHPUnit required.
 *
 * Usage:
 *   php backend/EntitlementControllerTest.php
 *
 * Exercises the EntitlementController decision layer against a fake Play
 * Developer API seam and a fake Response (throws instead of exit), so the
 * verify/refresh state machine can be checked in a single process:
 *   - Google purchaseState 0/1/2 + acknowledgementState 0/1 mapping
 *   - product / purchase-token binding and cross-store token isolation
 *   - provider transport / HTTP errors mapped to 503 vs a genuine 402 rejection
 *   - acknowledge is called once when unacknowledged, skipped when already acked,
 *     and an acknowledge failure never surfaces as a purchase rejection
 *   - EntitlementJwtIssuer::isBoundToInstall refresh binding (JWT sub vs install id)
 *
 * config.php, the database, and real network calls are never touched.
 * Exits 0 on all PASS, exits 1 if any FAIL.
 */

// ── Fakes: must be declared before requiring the controller ──────────────────

/** Thrown by the fake Response so a "terminal" controller response is observable. */
final class TestResponseExit extends RuntimeException
{
    public function __construct(public readonly int $statusCode, public readonly string $payloadMessage)
    {
        parent::__construct($payloadMessage, $statusCode);
    }
}

if (!class_exists('Response', false)) {
    final class Response
    {
        public static function json(array $data, int $statusCode = 200): void
        {
            throw new TestResponseExit($statusCode, (string)($data['error'] ?? ($data['ok'] ? 'ok' : 'error')));
        }

        public static function success(array $data = []): void
        {
            throw new TestResponseExit(200, 'ok');
        }

        public static function error(string $message, int $statusCode = 400): void
        {
            throw new TestResponseExit($statusCode, $message);
        }
    }
}

/** Fake Play Developer API. Each test sets the canned responses it needs. */
final class PlayDeveloperApiClient
{
    /** @var array{status:string,access_token:string|null,httpCode:int,error:string|null}|null */
    public static ?array $accessTokenResult = null;
    /** @var array{httpCode:int,body:array|null,error:string|null}|null */
    public static ?array $productPurchaseResult = null;
    /** @var array{httpCode:int,error:string|null}|null */
    public static ?array $acknowledgeResult = null;
    public static int $acknowledgeCalls = 0;
    /** @var list<array{token:string,package:string,product:string,purchaseToken:string}> */
    public static array $acknowledgeArgs = [];

    public static function reset(): void
    {
        self::$accessTokenResult = ['status' => 'ok', 'access_token' => 'fake-access-token', 'httpCode' => 200, 'error' => null];
        self::$productPurchaseResult = null;
        self::$acknowledgeResult = ['httpCode' => 200, 'error' => null];
        self::$acknowledgeCalls = 0;
        self::$acknowledgeArgs = [];
    }

    public static function getAccessToken(array $config): array
    {
        return self::$accessTokenResult ?? ['status' => 'ok', 'access_token' => 'fake-access-token', 'httpCode' => 200, 'error' => null];
    }

    public static function getProductPurchase(string $accessToken, string $packageName, string $productId, string $purchaseToken): array
    {
        return self::$productPurchaseResult ?? ['httpCode' => 0, 'body' => null, 'error' => 'network_error'];
    }

    public static function acknowledgeProductPurchase(string $accessToken, string $packageName, string $productId, string $purchaseToken): array
    {
        self::$acknowledgeCalls++;
        self::$acknowledgeArgs[] = [
            'token' => $accessToken,
            'package' => $packageName,
            'product' => $productId,
            'purchaseToken' => $purchaseToken,
        ];
        return self::$acknowledgeResult ?? ['httpCode' => 200, 'error' => null];
    }
}

require_once __DIR__ . '/src/HuaweiIapVerifier.php';
require_once __DIR__ . '/src/EntitlementJwtIssuer.php';
require_once __DIR__ . '/src/Controllers/EntitlementController.php';

// ── Test scaffolding ────────────────────────────────────────────────────────

$passCount = 0;
$failCount = 0;

function assertCase(string $name, bool $actual, bool $expected = true): void
{
    global $passCount, $failCount;
    if ($actual === $expected) {
        echo "PASS: $name\n";
        $passCount++;
    } else {
        $exp = $expected ? 'true' : 'false';
        $got = $actual ? 'true' : 'false';
        echo "FAIL: $name (expected=$exp, got=$got)\n";
        $failCount++;
    }
}

$controllerMethods = [];
function invokeControllerStatic(string $method, array $args)
{
    global $controllerMethods;
    if (!isset($controllerMethods[$method])) {
        $ref = new ReflectionMethod(EntitlementController::class, $method);
        $ref->setAccessible(true);
        $controllerMethods[$method] = $ref;
    }
    return $controllerMethods[$method]->invoke(null, ...$args);
}

/** Runs verifyPurchase with a pre-supplied Google access token (no token exchange). */
function verifyGoogle(array $playBody, int $httpCode = 200, ?string $error = null): array
{
    PlayDeveloperApiClient::$productPurchaseResult = ['httpCode' => $httpCode, 'body' => $playBody, 'error' => $error];
    return invokeControllerStatic('verifyPurchase', [
        ['android_package_name' => 'com.convert2video'],
        'google_play',
        'pro_lifetime_unlock',
        'token-abc',
        'fake-access-token',
    ]);
}

function basePlayBody(int $purchaseState, int $acknowledgementState = 0, array $overrides = []): array
{
    return array_merge([
        'productId' => 'pro_lifetime_unlock',
        'purchaseToken' => 'token-abc',
        'purchaseTimeMillis' => (string)((int)(microtime(true) * 1000)),
        'purchaseState' => $purchaseState,
        'acknowledgementState' => $acknowledgementState,
    ], $overrides);
}

/** Captures a TestResponseExit thrown by $fn; returns [statusCode|null, message|null]. */
function captureResponse(callable $fn): array
{
    try {
        $fn();
        return [null, null];
    } catch (TestResponseExit $exit) {
        return [$exit->statusCode, $exit->payloadMessage];
    }
}

PlayDeveloperApiClient::reset();

// ── Google purchaseState / acknowledgementState mapping ──────────────────────

$active0 = verifyGoogle(basePlayBody(0, 0));
assertCase('PURCHASED + unacknowledged → active', ($active0['status'] ?? null) === 'active');
assertCase('active result carries provider identity (store)', ($active0['store'] ?? null) === 'google_play');
assertCase('active result carries provider identity (token)', ($active0['purchase_token'] ?? null) === 'token-abc');

$active1 = verifyGoogle(basePlayBody(0, 1));
assertCase('PURCHASED + already acknowledged → active', ($active1['status'] ?? null) === 'active');

$revoked = verifyGoogle(basePlayBody(1));
assertCase('purchaseState 1 (canceled) → revoked', ($revoked['status'] ?? null) === 'revoked');

$pending = verifyGoogle(basePlayBody(2));
assertCase('purchaseState 2 → pending', ($pending['status'] ?? null) === 'pending');

$badState = verifyGoogle(basePlayBody(0, 0, ['purchaseState' => 3]));
assertCase('unknown purchaseState → malformed', ($badState['status'] ?? null) === 'malformed');

$missingState = verifyGoogle(['productId' => 'pro_lifetime_unlock', 'purchaseToken' => 'token-abc', 'purchaseTimeMillis' => '1700000000000']);
assertCase('missing purchaseState → malformed (getInt default not trusted)', ($missingState['status'] ?? null) === 'malformed');

$badAck = verifyGoogle(basePlayBody(0, 0, ['acknowledgementState' => 5]));
assertCase('PURCHASED + invalid acknowledgementState → malformed', ($badAck['status'] ?? null) === 'malformed');

// ── Product / purchase-token binding ────────────────────────────────────────

$wrongProduct = verifyGoogle(basePlayBody(0, 0, ['productId' => 'some_other_sku']));
assertCase('provider productId mismatch → mismatch', ($wrongProduct['status'] ?? null) === 'mismatch');

$wrongToken = verifyGoogle(basePlayBody(0, 0, ['purchaseToken' => 'different-token']));
assertCase('provider purchaseToken mismatch → mismatch', ($wrongToken['status'] ?? null) === 'mismatch');

// ── Cross-store token isolation ─────────────────────────────────────────────

$unknownStore = invokeControllerStatic('verifyPurchase', [
    ['android_package_name' => 'com.convert2video'],
    'amazon_appstore',
    'pro_lifetime_unlock',
    'token-abc',
    'fake-access-token',
]);
assertCase('unknown store never verified as a Google/Huawei purchase', ($unknownStore['status'] ?? null) === 'mismatch');

$googleRow = ['store' => 'google_play', 'product_id' => 'pro_lifetime_unlock', 'purchase_token' => 'token-abc'];
$huaweiProviderResult = ['status' => 'active', 'store' => 'huawei', 'product_id' => 'pro_lifetime_unlock', 'purchase_token' => 'token-abc'];
assertCase(
    'Huawei provider result does not match a Google row with the same token',
    invokeControllerStatic('providerMatchesRow', [$huaweiProviderResult, $googleRow]) === false
);
assertCase(
    'same-store same-token provider result matches its row',
    invokeControllerStatic('providerMatchesRow', [array_merge($huaweiProviderResult, ['store' => 'google_play']), $googleRow]) === true
);
assertCase(
    'identityKey namespaces the token by store (no cross-store collision)',
    invokeControllerStatic('identityKey', ['google_play', 'tok']) !== invokeControllerStatic('identityKey', ['huawei', 'tok'])
);

$crossStoreFilter = invokeControllerStatic('configuredProductFilter', [[
    'play_product_id' => 'pro_lifetime_unlock',
    'huawei_product_id' => 'pro_lifetime_unlock',
]]);
assertCase(
    'refresh candidate filter keeps store+product pairs distinct',
    $crossStoreFilter['params'] === ['google_play', 'pro_lifetime_unlock', 'huawei', 'pro_lifetime_unlock']
    && substr_count($crossStoreFilter['sql'], 'store = ? AND product_id = ?') === 2
);

// ── provider error vs genuine rejection: 503 vs 402 ─────────────────────────

assertCase("classifyProviderHttpCode: transport error → unavailable",
    invokeControllerStatic('classifyProviderHttpCode', [200, true]) === 'unavailable');
assertCase("classifyProviderHttpCode: non-int code → unavailable",
    invokeControllerStatic('classifyProviderHttpCode', [null, false]) === 'unavailable');
foreach ([408, 429, 500, 502, 503, 504] as $code) {
    assertCase("classifyProviderHttpCode: $code → unavailable",
        invokeControllerStatic('classifyProviderHttpCode', [$code, false]) === 'unavailable');
}
foreach ([400, 401, 403, 404] as $code) {
    assertCase("classifyProviderHttpCode: $code → rejected",
        invokeControllerStatic('classifyProviderHttpCode', [$code, false]) === 'rejected');
}

$transient = verifyGoogle([], 503, null);
assertCase('provider HTTP 503 → unavailable (not a rejection)', ($transient['status'] ?? null) === 'unavailable');
[$code503] = captureResponse(fn () => invokeControllerStatic('respondToProviderFailure', [$transient, 'Purchase verification failed']));
assertCase('unavailable provider result responds 503', $code503 === 503);

$networkErr = verifyGoogle([], 0, 'network_error');
assertCase('provider transport error → unavailable', ($networkErr['status'] ?? null) === 'unavailable');
[$codeNet] = captureResponse(fn () => invokeControllerStatic('respondToProviderFailure', [$networkErr, 'Purchase verification failed']));
assertCase('transport error responds 503, never 402', $codeNet === 503);

$rejected = verifyGoogle([], 403, null);
assertCase('provider HTTP 403 → rejected', ($rejected['status'] ?? null) === 'rejected');
[$code402] = captureResponse(fn () => invokeControllerStatic('respondToProviderFailure', [$rejected, 'Purchase verification failed']));
assertCase('non-transient 4xx rejection responds 402 (not 503)', $code402 === 402);

$rejected429 = verifyGoogle([], 429, null);
assertCase('provider HTTP 429 → unavailable (rate limit is not a rejection)', ($rejected429['status'] ?? null) === 'unavailable');

$malformedBody = verifyGoogle([], 200, null);
[$codeMalformed] = captureResponse(fn () => invokeControllerStatic('respondToProviderFailure', [
    array_merge($malformedBody, ['status' => 'malformed']),
    'Purchase verification failed',
]));
assertCase('malformed provider payload responds 503', $codeMalformed === 503);

// ── acknowledge: called once when unacked, skipped when already acked ───────

PlayDeveloperApiClient::reset();
$activeUnacked = verifyGoogle(basePlayBody(0, 0));
[$ackCode] = captureResponse(fn () => invokeControllerStatic('acknowledgeIfRequired', [
    ['android_package_name' => 'com.convert2video'],
    $activeUnacked,
    'pro_lifetime_unlock',
    'token-abc',
    'fake-access-token',
]));
assertCase('acknowledgeIfRequired: unacknowledged purchase triggers exactly one acknowledge call',
    PlayDeveloperApiClient::$acknowledgeCalls === 1 && $ackCode === null);
assertCase('acknowledge call targets the verified product + token',
    (PlayDeveloperApiClient::$acknowledgeArgs[0]['product'] ?? null) === 'pro_lifetime_unlock'
    && (PlayDeveloperApiClient::$acknowledgeArgs[0]['purchaseToken'] ?? null) === 'token-abc');

PlayDeveloperApiClient::reset();
$activeAcked = verifyGoogle(basePlayBody(0, 1));
captureResponse(fn () => invokeControllerStatic('acknowledgeIfRequired', [
    ['android_package_name' => 'com.convert2video'],
    $activeAcked,
    'pro_lifetime_unlock',
    'token-abc',
    'fake-access-token',
]));
assertCase('acknowledgeIfRequired: already-acknowledged purchase is not re-acknowledged',
    PlayDeveloperApiClient::$acknowledgeCalls === 0);

PlayDeveloperApiClient::reset();
$huaweiActive = ['status' => 'active', 'store' => 'huawei', 'receipt' => ['acknowledgementState' => 0], 'product_id' => 'pro_lifetime_unlock', 'purchase_token' => 'token-abc'];
captureResponse(fn () => invokeControllerStatic('acknowledgeIfRequired', [
    ['android_package_name' => 'com.convert2video'],
    $huaweiActive,
    'pro_lifetime_unlock',
    'token-abc',
    'fake-access-token',
]));
assertCase('acknowledgeIfRequired: non-Google store never calls the Play acknowledge API',
    PlayDeveloperApiClient::$acknowledgeCalls === 0);

// ── acknowledge failure never becomes a purchase rejection ──────────────────

PlayDeveloperApiClient::reset();
PlayDeveloperApiClient::$acknowledgeResult = ['httpCode' => 500, 'error' => null];
$activeForAckFail = verifyGoogle(basePlayBody(0, 0));
[$ackFailCode, $ackFailMsg] = captureResponse(fn () => invokeControllerStatic('acknowledgeIfRequired', [
    ['android_package_name' => 'com.convert2video'],
    $activeForAckFail,
    'pro_lifetime_unlock',
    'token-abc',
    'fake-access-token',
]));
assertCase('acknowledge HTTP 500 → 503 (retry pending), never a 402 rejection', $ackFailCode === 503);
assertCase('acknowledge failure uses the acknowledgement-service message',
    $ackFailMsg === 'Purchase acknowledgement service unavailable');

PlayDeveloperApiClient::reset();
PlayDeveloperApiClient::$acknowledgeResult = ['httpCode' => 0, 'error' => 'network_error'];
$activeForAckNet = verifyGoogle(basePlayBody(0, 0));
[$ackNetCode] = captureResponse(fn () => invokeControllerStatic('acknowledgeIfRequired', [
    ['android_package_name' => 'com.convert2video'],
    $activeForAckNet,
    'pro_lifetime_unlock',
    'token-abc',
    'fake-access-token',
]));
assertCase('acknowledge transport failure → 503', $ackNetCode === 503);

// ── refresh: JWT sub ↔ install-id binding (EntitlementJwtIssuer::isBoundToInstall) ──

$installA = '123e4567-e89b-12d3-a456-426614174000';
$installB = '00000000-0000-4000-8000-000000000001';

assertCase('isBoundToInstall: matching canonical sub/install → true',
    EntitlementJwtIssuer::isBoundToInstall(['sub' => $installA], $installA) === true);
assertCase('isBoundToInstall: different install id → false (refresh cannot rebind)',
    EntitlementJwtIssuer::isBoundToInstall(['sub' => $installA], $installB) === false);
assertCase('isBoundToInstall: missing sub → false',
    EntitlementJwtIssuer::isBoundToInstall(['pro' => true], $installA) === false);
assertCase('isBoundToInstall: non-string sub → false',
    EntitlementJwtIssuer::isBoundToInstall(['sub' => 12345], $installA) === false);
assertCase('isBoundToInstall: non-canonical sub → false',
    EntitlementJwtIssuer::isBoundToInstall(['sub' => 'not-a-uuid'], $installA) === false);
assertCase('isBoundToInstall: non-canonical install id → false',
    EntitlementJwtIssuer::isBoundToInstall(['sub' => $installA], 'NOT-A-UUID') === false);
assertCase('isBoundToInstall: uppercased sub is not canonical → false',
    EntitlementJwtIssuer::isBoundToInstall(['sub' => strtoupper($installA)], $installA) === false);

// ── Summary ────────────────────────────────────────────────────────────────

echo "\n{$passCount} passed, {$failCount} failed.\n";
exit($failCount > 0 ? 1 : 0);
