<?php

final class PlayDeveloperApiClient
{
    private const GOOGLE_TOKEN_URI = 'https://oauth2.googleapis.com/token';

    /**
     * Exchange the configured service-account assertion for a Play access
     * token without writing an HTTP response. The controller can therefore
     * map token acquisition failures consistently with acknowledge failures.
     */
    /** @return array{status:string,access_token:string|null,httpCode:int,error:string|null} */
    public static function getAccessToken(array $config): array
    {
        $failure = static function (string $status, int $httpCode, string $error): array {
            return [
                'status' => $status,
                'access_token' => null,
                'httpCode' => $httpCode,
                'error' => $error,
            ];
        };

        $jsonPath = $config['google_service_account_json_path'] ?? null;
        if (!is_string($jsonPath) || $jsonPath === '' || !is_file($jsonPath) || !is_readable($jsonPath)) {
            return $failure('config', 0, 'service_account_unavailable');
        }
        $json = file_get_contents($jsonPath);
        if ($json === false) {
            return $failure('config', 0, 'service_account_unreadable');
        }
        try {
            $serviceAccount = json_decode($json, true, 512, JSON_THROW_ON_ERROR);
        } catch (Throwable $e) {
            return $failure('config', 0, 'service_account_malformed');
        }
        if (!is_array($serviceAccount)
            || !is_string($serviceAccount['client_email'] ?? null)
            || !is_string($serviceAccount['private_key'] ?? null)
            || $serviceAccount['client_email'] === ''
            || $serviceAccount['private_key'] === '') {
            return $failure('config', 0, 'service_account_fields_invalid');
        }

        $now = time();
        $header = self::base64urlEncode(json_encode(['alg' => 'RS256', 'typ' => 'JWT']));
        $payload = self::base64urlEncode(json_encode([
            'iss' => $serviceAccount['client_email'],
            'scope' => 'https://www.googleapis.com/auth/androidpublisher',
            'aud' => self::GOOGLE_TOKEN_URI,
            'exp' => $now + 3600,
            'iat' => $now,
        ]));
        $privateKey = openssl_pkey_get_private($serviceAccount['private_key']);
        if ($privateKey === false) {
            return $failure('config', 0, 'service_account_key_invalid');
        }
        $signature = '';
        if (!openssl_sign($header . '.' . $payload, $signature, $privateKey, OPENSSL_ALGO_SHA256)) {
            return $failure('config', 0, 'service_account_signing_failed');
        }

        $ch = curl_init(self::GOOGLE_TOKEN_URI);
        if ($ch === false) {
            return $failure('config', 0, 'oauth_client_unavailable');
        }
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_POST => true,
            CURLOPT_POSTFIELDS => http_build_query([
                'grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer',
                'assertion' => $header . '.' . $payload . '.' . self::base64urlEncode($signature),
            ], '', '&', PHP_QUERY_RFC3986),
            CURLOPT_HTTPHEADER => ['Content-Type: application/x-www-form-urlencoded'],
            CURLOPT_CONNECTTIMEOUT => 5,
            CURLOPT_TIMEOUT => 10,
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
        ]);
        $response = curl_exec($ch);
        $httpCode = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $curlErrno = curl_errno($ch);
        curl_close($ch);
        if ($curlErrno !== 0 || $response === false) {
            return $failure('transient', $httpCode, 'network_error');
        }
        if ($httpCode === 408 || $httpCode === 429 || ($httpCode >= 500 && $httpCode <= 599)) {
            return $failure('transient', $httpCode, 'oauth_http_unavailable');
        }
        if ($httpCode >= 400 && $httpCode <= 499) {
            return $failure('rejected', $httpCode, 'oauth_request_rejected');
        }
        if ($httpCode < 200 || $httpCode >= 300) {
            return $failure('config', $httpCode, 'oauth_unexpected_http_status');
        }
        try {
            $decoded = json_decode($response, true, 512, JSON_THROW_ON_ERROR);
        } catch (Throwable $e) {
            return $failure('config', $httpCode, 'oauth_response_malformed');
        }
        $accessToken = is_array($decoded) ? ($decoded['access_token'] ?? null) : null;
        if (!is_string($accessToken) || $accessToken === '') {
            return $failure('config', $httpCode, 'oauth_access_token_missing');
        }
        return [
            'status' => 'ok',
            'access_token' => $accessToken,
            'httpCode' => $httpCode,
            'error' => null,
        ];
    }

    /**
     * GET purchases.products.get
     *
     * Returns ['httpCode' => int, 'body' => array|null, 'error' => string|null]
     */
    public static function getProductPurchase(
        string $accessToken,
        string $packageName,
        string $productId,
        string $purchaseToken
    ): array {
        $url = sprintf(
            'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/%s/purchases/products/%s/tokens/%s',
            rawurlencode($packageName),
            rawurlencode($productId),
            rawurlencode($purchaseToken)
        );

        $ch = curl_init($url);
        if ($ch === false) {
            return ['httpCode' => 0, 'body' => null, 'error' => 'network_error'];
        }
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_HTTPGET        => true,
            CURLOPT_HTTPHEADER     => [
                'Authorization: Bearer ' . $accessToken,
                'Accept: application/json',
            ],
            CURLOPT_CONNECTTIMEOUT => 5,
            CURLOPT_TIMEOUT        => 10,
            CURLOPT_FOLLOWLOCATION => false,
            // Fix #7: enforce TLS certificate verification
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
        ]);

        $response  = curl_exec($ch);
        $httpCode  = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $curlErrno = curl_errno($ch);
        curl_close($ch);

        if ($curlErrno !== 0 || $response === false) {
            return ['httpCode' => 0, 'body' => null, 'error' => 'network_error'];
        }

        $body = json_decode($response, true);
        return [
            'httpCode' => $httpCode,
            'body'     => is_array($body) ? $body : null,
            'error'    => null,
        ];
    }

    /**
     * POST purchases.products.acknowledge.
     *
     * Returns ['httpCode' => int, 'error' => string|null]. Every 2xx
     * response is a successful acknowledgement; the controller owns the
     * policy for non-2xx and transport failures.
     */
    public static function acknowledgeProductPurchase(
        string $accessToken,
        string $packageName,
        string $productId,
        string $purchaseToken
    ): array {
        $url = sprintf(
            'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/%s/purchases/products/%s/tokens/%s:acknowledge',
            rawurlencode($packageName),
            rawurlencode($productId),
            rawurlencode($purchaseToken)
        );

        $ch = curl_init($url);
        if ($ch === false) {
            return ['httpCode' => 0, 'error' => 'network_error'];
        }
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_POST           => true,
            CURLOPT_POSTFIELDS     => '{}',
            CURLOPT_HTTPHEADER     => [
                'Authorization: Bearer ' . $accessToken,
                'Accept: application/json',
                'Content-Type: application/json',
            ],
            CURLOPT_CONNECTTIMEOUT => 5,
            CURLOPT_TIMEOUT        => 10,
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
        ]);

        $response = curl_exec($ch);
        $httpCode = (int)curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $curlErrno = curl_errno($ch);
        curl_close($ch);

        if ($curlErrno !== 0 || $response === false) {
            return ['httpCode' => $httpCode, 'error' => 'network_error'];
        }

        return ['httpCode' => $httpCode, 'error' => null];
    }

    private static function base64urlEncode(string $data): string
    {
        return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
    }
}
