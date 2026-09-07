<?php

final class WebhookController
{
    private const MAX_REQUEST_BYTES = 16384;
    private const MAX_PURCHASE_TOKEN_LENGTH = 512;

    public function handleRtdn(): void
    {
        // Authenticate before reading or processing the Pub/Sub body.
        $authHeader = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
        if (!is_string($authHeader) || !str_starts_with($authHeader, 'Bearer ')) {
            Response::error('Unauthorized', 401);
        }
        $token = substr($authHeader, 7);
        if ($token === '') {
            Response::error('Unauthorized', 401);
        }

        $config = require __DIR__ . '/../../config.php';
        $expectedEmail = $config['pubsub_push_service_account_email'] ?? null;
        $expectedAudience = $config['pubsub_push_audience'] ?? null;
        if (!empty($config['validation']['webhook'])
            || !is_string($expectedEmail) || !is_string($expectedAudience)
            || !EntitlementJwtIssuer::isStrictProductId($config['play_product_id'] ?? null)) {
            Response::error('Service configuration unavailable', 503);
        }
        $configuredPlayProduct = $config['play_product_id'];

        $verifier = new GoogleCertsVerifier();
        if (!$verifier->verify($token, $expectedEmail, $expectedAudience)) {
            Response::error('Unauthorized', 401);
        }

        $readResult = self::readBoundedBody(self::MAX_REQUEST_BYTES);
        if ($readResult['status'] === 'oversize') {
            Response::error('Request body too large', 413);
        }
        if ($readResult['status'] !== 'ok') {
            Response::error('Request body could not be read', 500);
        }
        $raw = $readResult['body'];
        [$notificationType, $purchaseToken, $parsed] = self::parseNotification($raw);

        $dbType = ($parsed && $notificationType !== null) ? (string)$notificationType : 'unknown';
        // RTDN purchase tokens are only needed transiently for the verified
        // entitlement lookup above. Do not retain the opaque token or the
        // authenticated request body in the notification audit row.
        $dbToken = null;
        $processed = $parsed ? 1 : 0;
        $processedAt = $parsed ? date('Y-m-d H:i:s') : null;
        // Keep only a fixed JSON marker for both valid and malformed
        // notifications. This preserves processed/processed_at semantics while
        // ensuring neither raw body nor token is retained.
        $redactedPayload = '{"redacted":true}';

        $pdo = null;
        try {
            $pdo = Database::connection();
            $pdo->beginTransaction();

            // Google one-time-product cancellation is a revocation. The store
            // predicate prevents a cross-store token from mutating Huawei data.
            if ($parsed && $notificationType === 2 && is_string($purchaseToken) && $purchaseToken !== '') {
                $target = $pdo->prepare(
                    'SELECT product_id
                     FROM purchase_events
                     WHERE store = ? AND purchase_token = ?
                     FOR UPDATE'
                );
                $target->execute(['google_play', $purchaseToken]);
                $targetRow = $target->fetch();
                if ($targetRow !== false && self::isConfiguredPlayProduct($targetRow, $configuredPlayProduct)) {
                    $update = $pdo->prepare(
                        "UPDATE purchase_events
                         SET status = 'revoked', revoked_at = NOW()
                         WHERE store = ? AND purchase_token = ? AND product_id = ?"
                    );
                    $update->execute(['google_play', $purchaseToken, $configuredPlayProduct]);
                }
            }

            $insert = $pdo->prepare(
                'INSERT INTO rtdn_notifications
                    (notification_type, purchase_token, raw_payload, processed, processed_at)
                 VALUES (?, ?, ?, ?, ?)'
            );
            $insert->execute([$dbType, $dbToken, $redactedPayload, $processed, $processedAt]);
            $pdo->commit();
        } catch (PDOException $e) {
            if ($pdo !== null && $pdo->inTransaction()) {
                $pdo->rollBack();
            }
            Response::error('Internal server error', 500);
        }

        Response::success();
    }

    /** @return array{0:int|null,1:string|null,2:bool} */
    private static function parseNotification(string $raw): array
    {
        $notificationType = null;
        $purchaseToken = null;
        $parsed = false;

        $envelope = json_decode($raw, true);
        if (is_array($envelope)
            && isset($envelope['message']['data'])
            && is_string($envelope['message']['data'])) {
            $dataDecoded = base64_decode($envelope['message']['data'], true);
            if ($dataDecoded !== false) {
                $notification = json_decode($dataDecoded, true);
                $otp = is_array($notification)
                    ? ($notification['oneTimeProductNotification'] ?? null)
                    : null;
                $candidateType = is_array($otp) ? ($otp['notificationType'] ?? null) : null;
                $candidateToken = is_array($otp) ? ($otp['purchaseToken'] ?? null) : null;
                $tokenValid = is_string($candidateToken)
                    && $candidateToken !== ''
                    && strlen($candidateToken) <= self::MAX_PURCHASE_TOKEN_LENGTH
                    && preg_match('/[\x00-\x1F\x7F]/', $candidateToken) !== 1;
                if (is_int($candidateType) && $candidateType >= 1 && $candidateType <= 2 && $tokenValid) {
                    $notificationType = $candidateType;
                    $purchaseToken = $candidateToken;
                    $parsed = true;
                }
            }
        }

        return [$notificationType, $purchaseToken, $parsed];
    }

    private static function isConfiguredPlayProduct(array $row, string $configuredProduct): bool
    {
        return EntitlementJwtIssuer::isStrictProductId($configuredProduct)
            && is_string($row['product_id'] ?? null)
            && $row['product_id'] === $configuredProduct;
    }

    /** @return array{status:string,body:string} */
    private static function readBoundedBody(int $maxBytes): array
    {
        $stream = fopen('php://input', 'rb');
        if ($stream === false) {
            return ['status' => 'io', 'body' => ''];
        }
        $body = self::readBoundedStream($stream, $maxBytes);
        fclose($stream);
        return $body;
    }

    /** @return array{status:string,body:string} */
    private static function readBoundedStream($stream, int $maxBytes): array
    {
        $body = '';
        $emptyReads = 0;
        while (!feof($stream) && strlen($body) <= $maxBytes) {
            $remaining = ($maxBytes + 1) - strlen($body);
            $chunk = fread($stream, min(8192, $remaining));
            if ($chunk === false) {
                return ['status' => 'io', 'body' => ''];
            }
            if ($chunk === '') {
                $emptyReads++;
                if ($emptyReads >= 3) {
                    return ['status' => 'io', 'body' => ''];
                }
                continue;
            }
            $emptyReads = 0;
            $body .= $chunk;
            if (strlen($body) > $maxBytes) {
                return ['status' => 'oversize', 'body' => ''];
            }
        }
        return ['status' => 'ok', 'body' => $body];
    }
}
