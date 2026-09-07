<?php

final class GoogleServiceAccountAuth
{
    public static function getAccessToken(array $config): string
    {
        $saJsonPath = $config['google_service_account_json_path'] ?? null;
        if (empty($saJsonPath) || !is_file($saJsonPath) || !is_readable($saJsonPath)) {
            Response::error('Service configuration unavailable', 503);
        }

        $saJsonContent = file_get_contents($saJsonPath);
        if ($saJsonContent === false) {
            Response::error('Service configuration unavailable', 503);
        }

        $sa = json_decode($saJsonContent, true);
        if (
            !is_array($sa) ||
            empty($sa['client_email']) ||
            empty($sa['private_key']) ||
            empty($sa['token_uri'])
        ) {
            Response::error('Service configuration unavailable', 503);
        }

        $now = time();
        $jwtHeader = self::base64urlEncode(json_encode(['alg' => 'RS256', 'typ' => 'JWT']));
        $jwtPayload = self::base64urlEncode(json_encode([
            'iss'   => $sa['client_email'],
            'scope' => 'https://www.googleapis.com/auth/androidpublisher',
            'aud'   => $sa['token_uri'],
            'exp'   => $now + 3600,
            'iat'   => $now,
        ]));

        $signingInput = $jwtHeader . '.' . $jwtPayload;
        $privateKey = openssl_pkey_get_private($sa['private_key']);
        if ($privateKey === false) {
            Response::error('Service configuration unavailable', 503);
        }

        $sig = '';
        if (!openssl_sign($signingInput, $sig, $privateKey, OPENSSL_ALGO_SHA256)) {
            Response::error('Service configuration unavailable', 503);
        }

        $assertion = $signingInput . '.' . self::base64urlEncode($sig);

        $ch = curl_init($sa['token_uri']);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_POST           => true,
            CURLOPT_POSTFIELDS     => http_build_query([
                'grant_type' => 'urn:ietf:params:oauth:grant-type:jwt-bearer',
                'assertion'  => $assertion,
            ]),
            CURLOPT_HTTPHEADER     => ['Content-Type: application/x-www-form-urlencoded'],
            CURLOPT_TIMEOUT        => 10,
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
        ]);

        $response  = curl_exec($ch);
        $curlErrno = curl_errno($ch);
        curl_close($ch);

        if ($curlErrno !== 0 || $response === false) {
            Response::error('Service configuration unavailable', 503);
        }

        $data = json_decode($response, true);
        if (!is_array($data) || empty($data['access_token'])) {
            Response::error('Service configuration unavailable', 503);
        }

        return $data['access_token'];
    }

    private static function base64urlEncode(string $data): string
    {
        return rtrim(strtr(base64_encode($data), '+/', '-_'), '=');
    }
}
