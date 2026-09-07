<?php

final class GoogleCertsVerifier
{
    /** @var callable|null */
    private $certsFetcher;

    public function __construct(?callable $certsFetcher = null)
    {
        $this->certsFetcher = $certsFetcher;
    }

    public function verify(string $idToken, string $expectedEmail, string $expectedAudience): bool
    {
        $parts = explode('.', $idToken);
        if (count($parts) !== 3) {
            return false;
        }

        [$headerB64, $payloadB64, $sigB64] = $parts;

        $headerJson = self::base64urlDecode($headerB64);
        if ($headerJson === false) {
            return false;
        }
        $header = json_decode($headerJson, true);
        if (!is_array($header)
            || !is_string($header['kid'] ?? null)
            || $header['kid'] === ''
            || ($header['alg'] ?? null) !== 'RS256'
            || ($header['typ'] ?? null) !== 'JWT') {
            return false;
        }
        $kid = $header['kid'];

        $certs = $this->fetchCerts();
        if (!is_array($certs) || !isset($certs[$kid])) {
            return false;
        }
        $pem = $certs[$kid];

        $pubKey = openssl_pkey_get_public($pem);
        if ($pubKey === false) {
            return false;
        }

        $sig = self::base64urlDecode($sigB64);
        if ($sig === false) {
            return false;
        }

        $verified = openssl_verify($headerB64 . '.' . $payloadB64, $sig, $pubKey, OPENSSL_ALGO_SHA256);
        if ($verified !== 1) {
            return false;
        }

        $payloadJson = self::base64urlDecode($payloadB64);
        if ($payloadJson === false) {
            return false;
        }
        $payload = json_decode($payloadJson, true);
        if (!is_array($payload)) {
            return false;
        }

        if (($payload['iss'] ?? '') !== 'https://accounts.google.com') {
            return false;
        }

        if (($payload['email'] ?? '') !== $expectedEmail) {
            return false;
        }

        $emailVerified = $payload['email_verified'] ?? false;
        if ($emailVerified !== true && $emailVerified !== 'true') {
            return false;
        }

        if (!isset($payload['exp']) || (int)$payload['exp'] < time()) {
            return false;
        }

        // Pub/Sub's OIDC audience is an exact configured endpoint match.
        if ($expectedAudience === '' || ($payload['aud'] ?? null) !== $expectedAudience) {
            return false;
        }

        return true;
    }

    private function fetchCerts(): ?array
    {
        // Injected fetcher (test path): skip cache, invoke directly on every call.
        if ($this->certsFetcher !== null) {
            return ($this->certsFetcher)();
        }

        // Production path: static in-memory TTL cache, default 3600 s.
        static $cache     = null;
        static $expiresAt = 0;

        if ($cache !== null && time() < $expiresAt) {
            return $cache;
        }

        $ch = curl_init('https://www.googleapis.com/oauth2/v1/certs');
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT        => 10,
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_SSL_VERIFYHOST => 2,
        ]);
        $response  = curl_exec($ch);
        $curlErrno = curl_errno($ch);
        curl_close($ch);

        if ($curlErrno !== 0 || $response === false) {
            return null;
        }

        $certs = json_decode($response, true);
        if (!is_array($certs)) {
            return null;
        }

        $cache     = $certs;
        $expiresAt = time() + 3600;

        return $cache;
    }

    private static function base64urlDecode(string $data)
    {
        $translated = strtr($data, '-_', '+/');
        $padded = str_pad($translated, strlen($translated) + (4 - strlen($translated) % 4) % 4, '=');
        return base64_decode($padded, true);
    }
}
