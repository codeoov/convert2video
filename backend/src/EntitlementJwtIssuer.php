<?php

final class EntitlementJwtIssuer
{
    public const TTL_SECONDS = 259200; // 3 days in seconds
    private const MAX_DATE = 4102444800; // 2100-01-01 UTC; reject implausible dates.

    public static function expiresAt(?int $issuedAt = null): int
    {
        return ($issuedAt ?? time()) + self::TTL_SECONDS;
    }

    public static function isCanonicalUuid($value): bool
    {
        return is_string($value)
            && strtolower($value) === $value
            && preg_match('/\A[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\z/', $value) === 1;
    }

    /**
     * Distinguish an invalid token from an unavailable signing-key
     * infrastructure. The controller checks this before token verification.
     */
    public static function hasUsableVerificationKey(array $config): bool
    {
        $pem = self::readPrivateKeyPem($config);
        if ($pem === false) {
            return false;
        }

        $privateKey = openssl_pkey_get_private($pem);
        if ($privateKey === false) {
            return false;
        }

        $keyDetails = openssl_pkey_get_details($privateKey);
        return is_array($keyDetails)
            && is_string($keyDetails['key'] ?? null)
            && openssl_pkey_get_public($keyDetails['key']) !== false;
    }

    public static function issue(array $config, string $installId, string $productId): string
    {
        if (!self::isCanonicalUuid($installId) || !self::isAllowedProduct($config, $productId)) {
            Response::error('Service configuration unavailable', 503);
        }

        $pem = self::readPrivateKeyPem($config);
        if ($pem === false) {
            Response::error('Service configuration unavailable', 503);
        }

        $now    = time();
        $header = self::base64urlEncode(json_encode(['alg' => 'RS256', 'typ' => 'JWT']));
        $payload = self::base64urlEncode(json_encode([
            'sub'        => $installId,
            'pro'        => true,
            'iat'        => $now,
            'exp'        => self::expiresAt($now),
            'product_id' => $productId,
        ]));

        $signingInput = $header . '.' . $payload;
        $privateKey   = openssl_pkey_get_private($pem);
        if ($privateKey === false) {
            Response::error('Service configuration unavailable', 503);
        }

        $sig = '';
        if (!openssl_sign($signingInput, $sig, $privateKey, OPENSSL_ALGO_SHA256)) {
            Response::error('Service configuration unavailable', 503);
        }

        return $signingInput . '.' . self::base64urlEncode($sig);
    }

    /**
     * Verify RS256 signature only — exp is intentionally ignored.
     * Derives the public key inline from the private PEM on each call (no static cache).
     *
     * @return array|false  Decoded payload array on success, false on invalid sig / malformed token.
     */
    public static function verifyIgnoringExpiry(array $config, string $token): array|false
    {
        $parts = explode('.', $token);
        if (count($parts) !== 3) {
            return false;
        }

        [$headerB64, $payloadB64, $sigB64] = $parts;
        $signingInput = $headerB64 . '.' . $payloadB64;

        $headerJson = self::base64urlDecode($headerB64);
        if ($headerJson === false) {
            return false;
        }
        $header = json_decode($headerJson, true);
        if (!is_array($header) || ($header['alg'] ?? null) !== 'RS256' || ($header['typ'] ?? null) !== 'JWT') {
            return false;
        }

        $sig = self::base64urlDecode($sigB64);
        if ($sig === false) {
            return false;
        }

        // Derive public key from the same private PEM — no separate config key, no static cache.
        $pem = self::readPrivateKeyPem($config);
        if ($pem === false) {
            return false;
        }

        $privateKey = openssl_pkey_get_private($pem);
        if ($privateKey === false) {
            return false;
        }

        $keyDetails = openssl_pkey_get_details($privateKey);
        if ($keyDetails === false || empty($keyDetails['key'])) {
            return false;
        }

        $publicKey = openssl_pkey_get_public($keyDetails['key']);
        if ($publicKey === false) {
            return false;
        }

        if (openssl_verify($signingInput, $sig, $publicKey, OPENSSL_ALGO_SHA256) !== 1) {
            return false;
        }

        $payloadJson = self::base64urlDecode($payloadB64);
        if ($payloadJson === false) {
            return false;
        }

        $payload = json_decode($payloadJson, true);
        if (!is_array($payload)
            || !self::hasValidPayloadShape($payload)
            || !self::isAllowedProduct($config, $payload['product_id'])) {
            return false;
        }

        return $payload;
    }

    public static function isBoundToInstall(array $payload, string $installId): bool
    {
        if (!self::isCanonicalUuid($installId)) {
            return false;
        }
        $subject = $payload['sub'] ?? null;
        return is_string($subject)
            && self::isCanonicalUuid($subject)
            && hash_equals($subject, $installId);
    }

    /**
     * Validate JWT syntax and claims without consulting deployment config or
     * verifying the signature. Refresh uses this to keep malformed JWTs as
     * 401 even when signing-key/product infrastructure is unavailable.
     */
    public static function isStructurallyValidToken(string $token): bool
    {
        $parts = explode('.', $token);
        if (count($parts) !== 3) {
            return false;
        }

        $headerJson = self::base64urlDecode($parts[0]);
        $payloadJson = self::base64urlDecode($parts[1]);
        $signature = self::base64urlDecode($parts[2]);
        if ($headerJson === false || $payloadJson === false || $signature === false || $signature === '') {
            return false;
        }

        $header = json_decode($headerJson, true);
        $payload = json_decode($payloadJson, true);
        return is_array($header)
            && ($header['alg'] ?? null) === 'RS256'
            && ($header['typ'] ?? null) === 'JWT'
            && is_array($payload)
            && self::hasValidPayloadShape($payload);
    }

    /**
     * Read the private key PEM from the path in config.
     * Returns false (fail-closed) if the file is missing, unreadable, or empty.
     * Both issue() and verifyIgnoringExpiry() use this helper exclusively.
     *
     * @return string|false
     */
    private static function readPrivateKeyPem(array $config): string|false
    {
        $pemPath = $config['entitlement_jwt_private_key_path'] ?? null;
        if (empty($pemPath) || !is_file($pemPath) || !is_readable($pemPath)) {
            return false;
        }

        $pem = file_get_contents($pemPath);
        if ($pem === false || trim($pem) === '') {
            return false;
        }

        return $pem;
    }

    private static function base64urlEncode(string $data): string
    {
        return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
    }

    private static function base64urlDecode(string $data): string|false
    {
        if (preg_match('/\A[A-Za-z0-9_-]*\z/', $data) !== 1) {
            return false;
        }
        $len    = strlen($data);
        $padded = str_pad(strtr($data, '-_', '+/'), $len + (4 - $len % 4) % 4, '=');
        return base64_decode($padded, true);
    }

    private static function isNumericDate($value): bool
    {
        return is_int($value) && $value >= 0 && $value <= self::MAX_DATE;
    }

    private static function hasValidPayloadShape(array $payload): bool
    {
        return self::isCanonicalUuid($payload['sub'] ?? null)
            && ($payload['pro'] ?? null) === true
            && self::isNumericDate($payload['iat'] ?? null)
            && self::isNumericDate($payload['exp'] ?? null)
            && $payload['exp'] > $payload['iat']
            && $payload['exp'] - $payload['iat'] <= self::TTL_SECONDS
            && self::isStrictProductId($payload['product_id'] ?? null);
    }

    private static function isAllowedProduct(array $config, string $productId): bool
    {
        if (!self::hasValidProductConfig($config)) {
            return false;
        }
        $allowed = array_map(
            static fn (array $pair): string => $pair['product_id'],
            self::configuredProductPairs($config)
        );
        return in_array($productId, $allowed, true);
    }

    public static function isStrictProductId($value): bool
    {
        return is_string($value)
            && preg_match('/\A[A-Za-z0-9._-]{1,64}\z/', $value) === 1;
    }

    public static function hasValidProductConfig(array $config): bool
    {
        return self::isStrictProductId($config['play_product_id'] ?? null)
            && self::isStrictProductId($config['huawei_product_id'] ?? null);
    }

    /** @return array<int,array{store:string,product_id:string}> */
    public static function configuredProductPairs(array $config): array
    {
        if (!self::hasValidProductConfig($config)) {
            return [];
        }
        return [
            ['store' => 'google_play', 'product_id' => $config['play_product_id']],
            ['store' => 'huawei', 'product_id' => $config['huawei_product_id']],
        ];
    }
}
