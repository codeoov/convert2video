<?php

final class HuaweiIapVerifier
{
    private const MIN_EPOCH_MILLIS = 946684800000; // 2000-01-01 UTC.
    private const MAX_EPOCH_MILLIS = 4102444800000; // 2100-01-01 UTC.

    public const ACTIVE = 'active';
    public const REFUNDED = 'refunded';
    public const REVOKED = 'revoked';
    public const REJECTED = 'rejected';
    public const UNAVAILABLE = 'unavailable';
    public const MALFORMED = 'malformed';
    public const MISMATCH = 'mismatch';

    public static function classifyHttpResult(int $httpCode, bool $networkError = false): string
    {
        if ($networkError || $httpCode === 408 || $httpCode === 429 || $httpCode >= 500) {
            return self::UNAVAILABLE;
        }
        if ($httpCode < 200 || $httpCode >= 300) {
            return self::REJECTED;
        }
        return 'ok';
    }

    /**
     * Verify a purchase against Huawei's server. The client never supplies
     * provider data to this method; the authenticated server response is the
     * source of truth.
     *
     * @return array{status:string,receipt:array|null,purchased_at:int|null}
     */
    public static function verify(
        array $config,
        string $packageName,
        string $productId,
        string $purchaseToken
    ): array {
        $oauthUrl = $config['huawei_oauth_token_url'] ?? null;
        $orderUrl = $config['huawei_order_verify_url'] ?? null;
        $appId = $config['huawei_app_id'] ?? null;
        $appSecret = $config['huawei_app_secret'] ?? null;

        if (!self::isHttpsUrl($oauthUrl) || !self::isHttpsUrl($orderUrl)
            || !self::isSafeString($appId, 512) || !self::isSafeString($appSecret, 512)) {
            return self::result(self::UNAVAILABLE);
        }

        $accessToken = self::requestAccessToken($oauthUrl, $appId, $appSecret);
        if ($accessToken === null) {
            return self::result(self::UNAVAILABLE);
        }

        $response = self::requestOrder($orderUrl, $accessToken, $productId, $purchaseToken);
        if ($response['status'] === 'network') {
            return self::result(self::UNAVAILABLE);
        }
        if ($response['status'] === 'unavailable') {
            return self::result(self::UNAVAILABLE);
        }
        if ($response['status'] === 'rejected') {
            return self::result(self::REJECTED);
        }
        if ($response['status'] !== 'ok' || $response['body'] === null) {
            return self::result(self::MALFORMED);
        }

        return self::validateProviderResponse(
            $response['body'],
            $appId,
            $packageName,
            $productId,
            $purchaseToken
        );
    }

    /**
     * Pure response validation seam used by the CLI contract tests.
     *
     * @return array{status:string,receipt:array|null,purchased_at:int|null}
     */
    public static function validateProviderResponse(
        array $body,
        string $appId,
        string $packageName,
        string $productId,
        string $purchaseToken
    ): array {
        if (!array_key_exists('responseCode', $body)
            || (!is_int($body['responseCode'])
                && (!is_string($body['responseCode'])
                    || preg_match('/\A[0-9]+\z/', $body['responseCode']) !== 1))) {
            return self::result(self::MALFORMED);
        }
        if ($body['responseCode'] !== 0 && $body['responseCode'] !== '0') {
            return self::result(self::REJECTED);
        }
        if (!array_key_exists('purchaseTokenData', $body)
            || !self::isSafeString($body['purchaseTokenData'], 65536)) {
            return self::result(self::MALFORMED);
        }

        try {
            $purchase = json_decode($body['purchaseTokenData'], true, 512, JSON_THROW_ON_ERROR);
        } catch (Throwable $e) {
            return self::result(self::MALFORMED);
        }
        if (!is_array($purchase)) {
            return self::result(self::MALFORMED);
        }

        return self::validatePurchase(
            $purchase,
            $appId,
            $packageName,
            $productId,
            $purchaseToken
        );
    }

    private static function requestAccessToken(string $url, string $appId, string $appSecret): ?string
    {
        $ch = curl_init($url);
        if ($ch === false) {
            return null;
        }
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => http_build_query([
                'grant_type' => 'client_credentials',
                'client_id' => $appId,
                'client_secret' => $appSecret,
            ], '', '&', PHP_QUERY_RFC3986),
            CURLOPT_HTTPHEADER => ['Content-Type: application/x-www-form-urlencoded; charset=UTF-8'],
            CURLOPT_CONNECTTIMEOUT => 5,
            CURLOPT_TIMEOUT => 10,
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
        ]);
        $body = curl_exec($ch);
        $httpCode = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $curlErrno = curl_errno($ch);
        curl_close($ch);

        if ($curlErrno !== 0 || $body === false || $httpCode < 200 || $httpCode >= 300) {
            return null;
        }
        $decoded = json_decode($body, true);
        $token = is_array($decoded) ? ($decoded['access_token'] ?? null) : null;
        return self::isSafeString($token, 4096) ? $token : null;
    }

    /** @return array{status:string,body:array|null} */
    private static function requestOrder(
        string $url,
        string $accessToken,
        string $productId,
        string $purchaseToken
    ): array {
        $payload = json_encode([
            'productId' => $productId,
            'purchaseToken' => $purchaseToken,
        ], JSON_UNESCAPED_SLASHES);
        if ($payload === false) {
            return ['status' => 'malformed', 'body' => null];
        }

        $ch = curl_init($url);
        if ($ch === false) {
            return ['status' => 'network', 'body' => null];
        }
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => $payload,
            CURLOPT_HTTPHEADER => [
                'Authorization: Basic ' . base64_encode('APPAT:' . $accessToken),
                'Content-Type: application/json; charset=UTF-8',
                'Accept: application/json',
            ],
            CURLOPT_CONNECTTIMEOUT => 5,
            CURLOPT_TIMEOUT => 10,
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
        ]);
        $body = curl_exec($ch);
        $httpCode = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $curlErrno = curl_errno($ch);
        curl_close($ch);

        if ($curlErrno !== 0 || $body === false) {
            return ['status' => 'network', 'body' => null];
        }
        $httpStatus = self::classifyHttpResult($httpCode);
        if ($httpStatus === self::UNAVAILABLE) {
            return ['status' => 'unavailable', 'body' => null];
        }
        $decoded = json_decode($body, true);
        if (!is_array($decoded)) {
            return ['status' => 'malformed', 'body' => null];
        }
        if ($httpStatus === self::REJECTED) {
            return ['status' => 'rejected', 'body' => null];
        }
        return ['status' => 'ok', 'body' => $decoded];
    }

    private static function validatePurchase(
        array $purchase,
        string $appId,
        string $packageName,
        string $productId,
        string $purchaseToken
    ): array {
        $providerAppId = $purchase['applicationId'] ?? null;
        $providerPackage = $purchase['packageName'] ?? null;
        $providerProduct = $purchase['productId'] ?? null;
        $providerToken = $purchase['purchaseToken'] ?? null;
        $orderId = $purchase['orderId'] ?? ($purchase['payOrderId'] ?? null);

        if (!self::isProviderIdentity($providerAppId, 512)
            || !self::isSafeString($providerPackage, 512)
            || !self::isSafeString($providerProduct, 64)
            || !self::isSafeString($providerToken, 512)
            || !self::isSafeString($orderId, 512)) {
            return self::result(self::MALFORMED, $purchase);
        }
        if ((string)$providerAppId !== $appId || $providerPackage !== $packageName
            || $providerProduct !== $productId || $providerToken !== $purchaseToken) {
            return self::result(self::MISMATCH, $purchase);
        }

        // Huawei's documented purchase states are integer PURCHASED (0),
        // CANCELED (1), and REFUNDED (2). Unknown values are malformed and
        // must never become an active entitlement.
        if (!array_key_exists('purchaseState', $purchase) || !is_int($purchase['purchaseState'])
            || !in_array($purchase['purchaseState'], [0, 1, 2], true)) {
            return self::result(self::MALFORMED, $purchase);
        }
        $state = match ($purchase['purchaseState']) {
            0 => self::ACTIVE,
            1 => self::REVOKED,
            2 => self::REFUNDED,
        };

        // Huawei InAppPurchaseData.purchaseTime and expirationDate are UTC
        // epoch milliseconds. Do not accept seconds or infer units by size.
        $purchaseTimeValue = $purchase['purchaseTime'] ?? null;
        $purchaseTimeMillis = self::exactEpochMillis($purchaseTimeValue);
        if ($purchaseTimeMillis === null) {
            return self::result(self::MALFORMED, $purchase);
        }
        $purchasedAt = intdiv($purchaseTimeMillis, 1000);

        // Terminal lifecycle states are authoritative and do not need an
        // expiry field. Only an active purchase may proceed to expiry checks.
        if ($state === self::REFUNDED || $state === self::REVOKED) {
            return self::result($state, $purchase, $purchasedAt);
        }

        $hasExpiry = array_key_exists('expirationDate', $purchase);
        $expiryValue = $purchase['expirationDate'] ?? null;
        if (!$hasExpiry) {
            return self::result(self::MALFORMED, $purchase, $purchasedAt);
        }
        $expiryMillis = $hasExpiry ? self::exactEpochMillis($expiryValue) : null;
        if ($hasExpiry && $expiryMillis === null) {
            return self::result(self::MALFORMED, $purchase, $purchasedAt);
        }
        if ($expiryMillis !== null && $expiryMillis <= (int)floor(microtime(true) * 1000)) {
            return self::result(self::REVOKED, $purchase, $purchasedAt);
        }
        return self::result(self::ACTIVE, $purchase, $purchasedAt);
    }

    /** Return only a documented UTC epoch-millisecond value. */
    private static function exactEpochMillis($value): ?int
    {
        if (!is_int($value)
            || $value < self::MIN_EPOCH_MILLIS
            || $value > self::MAX_EPOCH_MILLIS) {
            return null;
        }
        return $value;
    }

    private static function result(string $status, ?array $receipt = null, ?int $purchasedAt = null): array
    {
        return ['status' => $status, 'receipt' => $receipt, 'purchased_at' => $purchasedAt];
    }

    private static function isSafeString($value, int $maxLength): bool
    {
        return is_string($value)
            && $value !== ''
            && strlen($value) <= $maxLength
            && preg_match('/[\x00-\x1F\x7F]/', $value) !== 1;
    }

    private static function isProviderIdentity($value, int $maxLength): bool
    {
        return (is_string($value) || is_int($value))
            && (string)$value !== ''
            && strlen((string)$value) <= $maxLength
            && preg_match('/[\x00-\x1F\x7F]/', (string)$value) !== 1;
    }

    private static function isHttpsUrl($value): bool
    {
        if (!self::isSafeString($value, 2048)) {
            return false;
        }
        $parts = parse_url($value);
        return filter_var($value, FILTER_VALIDATE_URL) !== false
            && is_array($parts)
            && ($parts['scheme'] ?? '') === 'https'
            && !empty($parts['host'])
            && !isset($parts['user'], $parts['pass'], $parts['fragment']);
    }
}
