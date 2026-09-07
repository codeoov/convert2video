<?php

final class EntitlementController
{
    private const MAX_REQUEST_BYTES = 16384;
    private const MAX_INSTALL_ID_LENGTH = 36;
    private const MAX_STORE_LENGTH = 32;
    private const MAX_PRODUCT_ID_LENGTH = 64;
    private const MAX_PURCHASE_TOKEN_LENGTH = 512;

    public function verify(): void
    {
        $config = require __DIR__ . '/../../config.php';
        $input = self::readVerifyInput($config);
        self::requireConfig($config, $input['store'] === 'huawei' ? 'huawei' : 'google');

        $pdo = self::database();
        try {
            $preCheck = $pdo->prepare(
                'SELECT install_id, product_id
                 FROM purchase_events
                 WHERE store = ? AND purchase_token = ?
                 LIMIT 1'
            );
            $preCheck->execute([$input['store'], $input['purchase_token']]);
            $preRow = $preCheck->fetch();
        } catch (PDOException $e) {
            Response::error('Internal server error', 500);
        }

        if ($preRow !== false && $preRow['install_id'] !== $input['install_id']) {
            Response::error('Purchase token already associated with a different device', 409);
        }
        if ($preRow !== false && $preRow['product_id'] !== $input['product_id']) {
            Response::error('Purchase token is associated with a different product', 409);
        }

        try {
            $provider = self::verifyPurchase($config, $input['store'], $input['product_id'], $input['purchase_token']);
        } catch (Throwable $e) {
            Response::error('Purchase verification service unavailable', 503);
        }
        self::respondToProviderFailure($provider, 'Purchase verification failed');
        if ($provider['status'] === 'pending') {
            Response::error('Purchase is pending', 402);
        }
        if ($provider['status'] !== 'active') {
            self::recordTerminalVerify($config, $input, $provider);
            return;
        }

        self::acknowledgeIfRequired($config, $provider, $input['product_id'], $input['purchase_token']);

        $purchasedAt = self::purchaseDate($provider['purchased_at']);
        $rawReceipt = self::encodeReceipt($provider['receipt']);

        try {
            $pdo->beginTransaction();
            $lockRow = $pdo->prepare(
                'SELECT install_id, product_id, status
                 FROM purchase_events
                 WHERE store = ? AND purchase_token = ?
                 FOR UPDATE'
            );
            $lockRow->execute([$input['store'], $input['purchase_token']]);
            $existing = $lockRow->fetch();

            if ($existing !== false && $existing['install_id'] !== $input['install_id']) {
                $pdo->rollBack();
                Response::error('Purchase token already associated with a different device', 409);
            }
            if ($existing !== false && $existing['product_id'] !== $input['product_id']) {
                $pdo->rollBack();
                Response::error('Purchase token is associated with a different product', 409);
            }
            // The provider result was fetched before this lock. A terminal row
            // observed here is authoritative and must not be revived by that
            // stale active result (TOCTOU protection for the verify upsert).
            if ($existing !== false
                && in_array($existing['status'] ?? null, ['revoked', 'refunded'], true)) {
                $pdo->rollBack();
                Response::error('Purchase is no longer active', 403);
            }

            if ($existing !== false) {
                $update = $pdo->prepare(
                    'UPDATE purchase_events
                     SET install_id = ?, status = ?, purchased_at = ?, revoked_at = NULL, raw_receipt = ?
                     WHERE store = ? AND purchase_token = ?'
                );
                $update->execute([
                    $input['install_id'],
                    'active',
                    $purchasedAt,
                    $rawReceipt,
                    $input['store'],
                    $input['purchase_token'],
                ]);
            } else {
                $insert = $pdo->prepare(
                    'INSERT INTO purchase_events
                         (install_id, store, product_id, purchase_token, status, purchased_at, revoked_at, raw_receipt)
                     VALUES (?, ?, ?, ?, ?, ?, NULL, ?)'
                );
                $insert->execute([
                    $input['install_id'],
                    $input['store'],
                    $input['product_id'],
                    $input['purchase_token'],
                    'active',
                    $purchasedAt,
                    $rawReceipt,
                ]);
            }
            $pdo->commit();
        } catch (PDOException $e) {
            if ($pdo->inTransaction()) {
                $pdo->rollBack();
            }
            if ((string)$e->getCode() === '23000') {
                Response::error('Purchase token conflict', 409);
            }
            Response::error('Failed to record purchase', 500);
        }

        $jwt = EntitlementJwtIssuer::issue($config, $input['install_id'], $input['product_id']);
        Response::success(['jwt' => $jwt, 'expires_at' => EntitlementJwtIssuer::expiresAt()]);
    }

    public function refresh(): void
    {
        $authHeader = $_SERVER['HTTP_AUTHORIZATION'] ?? '';
        if (!is_string($authHeader)
            || preg_match('/\ABearer ([^\s]+)\z/', $authHeader, $authMatches) !== 1) {
            Response::error('Missing or malformed Authorization header', 401);
        }
        $token = $authMatches[1];

        $refreshInput = self::readRefreshInput();
        $installId = $refreshInput['install_id'];

        $config = require __DIR__ . '/../../config.php';
        // Token syntax/claims are an authentication concern and must be
        // classified before a broken deployment can mask an invalid JWT.
        if (!EntitlementJwtIssuer::isStructurallyValidToken($token)) {
            Response::error('Invalid or malformed token', 401);
        }
        self::requireConfig($config, 'common');
        if (!EntitlementJwtIssuer::hasValidProductConfig($config)
            || !EntitlementJwtIssuer::hasUsableVerificationKey($config)) {
            Response::error('Service configuration unavailable', 503);
        }

        $payload = EntitlementJwtIssuer::verifyIgnoringExpiry($config, $token);
        if ($payload === false) {
            Response::error('Invalid or malformed token', 401);
        }
        if (!EntitlementJwtIssuer::isBoundToInstall($payload, $installId)) {
            Response::error('Install ID does not match token subject', 409);
        }
        $productId = $payload['product_id'];
        $configuredProductFilter = self::configuredProductFilter($config);
        if ($configuredProductFilter['sql'] === '') {
            Response::error('Service configuration unavailable', 503);
        }

        $pdo = self::database();
        try {
            // Candidate discovery is install-scoped, but limited to the
            // configured Google/Huawei product set. The JWT product claim is
            // validated above and is used only to select the claim to issue;
            // it must not hide another configured store's active row.
            $snapshotStmt = self::discoverActiveEntitlementCandidates(
                $pdo,
                $installId,
                $configuredProductFilter['sql'],
                $configuredProductFilter['params']
            );
            $snapshot = $snapshotStmt->fetchAll();
        } catch (PDOException $e) {
            Response::error('Internal server error', 500);
        }
        if ($snapshot === []) {
            Response::error('No active entitlement found', 403);
        }

        $candidateStores = [];
        foreach ($snapshot as $row) {
            $store = $row['store'] ?? null;
            $rowProductId = $row['product_id'] ?? null;
            $purchaseToken = $row['purchase_token'] ?? null;
            if (!is_string($store) || !is_string($rowProductId) || !is_string($purchaseToken)
                || self::productForStore($config, $store) !== $rowProductId) {
                Response::error('Invalid stored entitlement', 500);
            }
            $candidateStores[$store] = true;
        }

        $googleAccessToken = null;
        if (isset($candidateStores['google_play'])) {
            self::requireConfig($config, 'google');
            $googleAccessToken = self::obtainGoogleAccessToken($config);
        }
        if (isset($candidateStores['huawei'])) {
            self::requireConfig($config, 'huawei');
        }

        // Verify every snapshot row before opening the short DB transaction.
        $providerResults = [];
        foreach ($snapshot as $row) {
            try {
                $provider = self::verifyPurchase(
                    $config,
                    $row['store'],
                    $row['product_id'],
                    $row['purchase_token'],
                    $googleAccessToken
                );
            } catch (Throwable $e) {
                Response::error('Purchase verification service unavailable', 503);
            }
            if ($provider['status'] === 'mismatch' || !self::providerMatchesRow($provider, $row)) {
                Response::error('Purchase verification identity mismatch', 503);
            }
            self::respondToProviderFailure($provider, 'Purchase verification failed');
            if ($provider['status'] === 'pending') {
                Response::error('Purchase is pending', 402);
            }
            if (!in_array($provider['status'], ['active', 'refunded', 'revoked'], true)) {
                Response::error('Purchase verification failed', 402);
            }
            $providerResults[self::identityKey($row['store'], $row['purchase_token'])] = $provider;
        }

        // Acknowledge every purchased Google row before opening the mutation
        // transaction. A failure therefore preserves the entire active set.
        foreach ($snapshot as $row) {
            $identity = self::identityKey($row['store'], $row['purchase_token']);
            $provider = $providerResults[$identity];
            self::acknowledgeIfRequired($config, $provider, $row['product_id'], $row['purchase_token'], $googleAccessToken);
        }

        $activeFound = false;
        $activeProductId = null;
        try {
            $pdo->beginTransaction();

            // Re-discover the active candidate set inside the short
            // transaction. It is still only candidate-set discovery; every
            // row identity is subsequently addressed by (store,
            // purchase_token). Any insert/delete/status/product change makes
            // the snapshot stale and returns 409 without retrying or issuing JWT.
            $currentDiscovery = self::discoverActiveEntitlementCandidates(
                $pdo,
                $installId,
                $configuredProductFilter['sql'],
                $configuredProductFilter['params'],
                true
            );
            $currentCandidates = $currentDiscovery->fetchAll();
            if (!self::sameCandidateIdentities($snapshot, $currentCandidates)) {
                $pdo->rollBack();
                Response::error('Entitlement changed while verifying purchase', 409);
            }

            // Re-read and lock each snapshot row by its composite identity
            // before applying the provider decision.
            $currentRows = [];
            $currentRowsByIdentity = [];
            foreach ($snapshot as $row) {
                $currentRowStmt = $pdo->prepare(
                    "SELECT id, install_id, store, product_id, purchase_token, status, purchased_at, revoked_at, updated_at
                     FROM purchase_events
                     WHERE store = ? AND purchase_token = ?
                       AND install_id = ? AND product_id = ? AND status = 'active'
                     FOR UPDATE"
                );
                $currentRowStmt->execute([
                    $row['store'],
                    $row['purchase_token'],
                    $installId,
                    $row['product_id'],
                ]);
                $currentRow = $currentRowStmt->fetch();
                if ($currentRow === false) {
                    $pdo->rollBack();
                    Response::error('Entitlement changed while verifying purchase', 409);
                }
                $currentRows[] = $currentRow;
                $currentRowsByIdentity[self::identityKey($currentRow['store'], $currentRow['purchase_token'])] = $currentRow;
            }

            if (!self::snapshotsMatch($snapshot, $currentRows)) {
                $pdo->rollBack();
                Response::error('Entitlement changed while verifying purchase', 409);
            }

            foreach ($snapshot as $row) {
                $provider = $providerResults[self::identityKey($row['store'], $row['purchase_token'])];
                $identity = self::identityKey($row['store'], $row['purchase_token']);
                $currentRow = $currentRowsByIdentity[$identity] ?? null;
                if (!is_array($currentRow)
                    || $currentRow['store'] !== $row['store']
                    || $currentRow['purchase_token'] !== $row['purchase_token']
                    || $currentRow['install_id'] !== $installId
                    || $currentRow['product_id'] !== $row['product_id']
                    || $currentRow['status'] !== 'active') {
                    $pdo->rollBack();
                    Response::error('Entitlement changed while verifying purchase', 409);
                }
                $currentStore = $currentRow['store'];
                $currentPurchaseToken = $currentRow['purchase_token'];
                $currentInstallId = $currentRow['install_id'];
                $currentProductId = $currentRow['product_id'];
                $currentRowId = $currentRow['id'];
                $status = $provider['status'] === 'active'
                    ? 'active'
                    : ($provider['status'] === 'refunded' ? 'refunded' : 'revoked');
                if ($status === 'active') {
                    $activeFound = true;
                    if ($activeProductId === null || $currentProductId === $productId) {
                        $activeProductId = $currentProductId;
                    }
                }
                $update = $pdo->prepare(
                    "UPDATE purchase_events
                     SET status = ?, revoked_at = ?, raw_receipt = ?
                     WHERE id = ? AND store = ? AND purchase_token = ?
                       AND install_id = ? AND product_id = ? AND status = 'active'"
                );
                $update->execute([
                    $status,
                    $status === 'active' ? null : date('Y-m-d H:i:s'),
                    self::encodeReceipt($provider['receipt']),
                    $currentRowId,
                    $currentStore,
                    $currentPurchaseToken,
                    $currentInstallId,
                    $currentProductId,
                ]);
                $affectedRows = $update->rowCount();
                if ($affectedRows !== 1 && !($affectedRows === 0 && $status === 'active')) {
                    $pdo->rollBack();
                    Response::error('Failed to update entitlement state', 500);
                }

                // PDO/MySQL may report zero for an active no-op update. The
                // locked row re-read is authoritative in that one narrow case;
                // every non-no-op update must affect exactly one row. The
                // confirmation still includes the composite identity and row
                // id so a mismatch or concurrent change cannot reach JWT issue.
                $confirm = $pdo->prepare(
                    'SELECT id, install_id, store, product_id, purchase_token, status
                     FROM purchase_events
                     WHERE id = ? AND store = ? AND purchase_token = ?
                     FOR UPDATE'
                );
                $confirm->execute([$currentRowId, $currentStore, $currentPurchaseToken]);
                $confirmedRow = $confirm->fetch();
                if ($confirmedRow === false
                    || $confirmedRow['id'] !== $currentRowId
                    || $confirmedRow['install_id'] !== $currentInstallId
                    || $confirmedRow['store'] !== $currentStore
                    || $confirmedRow['product_id'] !== $currentProductId
                    || $confirmedRow['purchase_token'] !== $currentPurchaseToken
                    || $confirmedRow['status'] !== $status) {
                    $pdo->rollBack();
                    Response::error('Failed to confirm entitlement state', 500);
                }
            }
            $pdo->commit();
        } catch (PDOException $e) {
            if ($pdo->inTransaction()) {
                $pdo->rollBack();
            }
            Response::error('Internal server error', 500);
        }

        if (!$activeFound) {
            Response::error('No active entitlement found', 403);
        }

        $newJwt = EntitlementJwtIssuer::issue($config, $installId, $activeProductId ?? $productId);
        Response::success(['jwt' => $newJwt, 'expires_at' => EntitlementJwtIssuer::expiresAt()]);
    }

    /** @return array{install_id:string,store:string,purchase_token:string,product_id:string} */
    private static function readVerifyInput(array $config): array
    {
        $contentLength = $_SERVER['CONTENT_LENGTH'] ?? null;
        if (is_string($contentLength) && ctype_digit($contentLength)
            && (int)$contentLength > self::MAX_REQUEST_BYTES) {
            Response::error('Request body too large', 413);
        }

        $readResult = self::readBoundedBody(self::MAX_REQUEST_BYTES);
        if ($readResult['status'] === 'oversize') {
            Response::error('Request body too large', 413);
        }
        if ($readResult['status'] !== 'ok') {
            Response::error('Request body could not be read', 500);
        }
        $raw = $readResult['body'];
        try {
            $body = json_decode($raw, true, 512, JSON_THROW_ON_ERROR);
        } catch (Throwable $e) {
            Response::error('Malformed JSON body', 400);
        }
        if (!is_array($body)) {
            Response::error('Malformed JSON body', 400);
        }

        foreach (['install_id', 'store', 'purchase_token', 'product_id'] as $field) {
            if (!array_key_exists($field, $body) || !is_string($body[$field]) || trim($body[$field]) === '') {
                Response::error('Missing or invalid required fields', 422);
            }
            if (preg_match('/[\x00-\x1F\x7F]/', $body[$field]) === 1) {
                Response::error('Invalid required fields', 422);
            }
        }

        $installId = $body['install_id'];
        $store = $body['store'];
        $purchaseToken = $body['purchase_token'];
        $productId = $body['product_id'];
        if (strlen($store) > self::MAX_STORE_LENGTH
            || strlen($productId) > self::MAX_PRODUCT_ID_LENGTH
            || strlen($purchaseToken) > self::MAX_PURCHASE_TOKEN_LENGTH) {
            Response::error('Request field too large', 413);
        }
        if (!EntitlementJwtIssuer::isCanonicalUuid($installId)) {
            Response::error('Invalid install_id', 422);
        }
        if (!in_array($store, ['google_play', 'huawei'], true)) {
            Response::error('Invalid store', 422);
        }

        $expectedProduct = self::productForStore($config, $store);
        if ($expectedProduct === null) {
            self::requireConfig($config, $store === 'huawei' ? 'huawei' : 'google');
        }
        if ($expectedProduct === null || $productId !== $expectedProduct) {
            Response::error('Invalid product for store', 422);
        }

        return [
            'install_id' => $installId,
            'store' => $store,
            'purchase_token' => $purchaseToken,
            'product_id' => $productId,
        ];
    }

    /** @return array{install_id:string} */
    private static function readRefreshInput(): array
    {
        $readResult = self::readBoundedBody(self::MAX_REQUEST_BYTES);
        if ($readResult['status'] === 'oversize') {
            Response::error('Request body too large', 413);
        }
        if ($readResult['status'] !== 'ok') {
            Response::error('Request body could not be read', 500);
        }
        try {
            $body = json_decode($readResult['body'], true, 512, JSON_THROW_ON_ERROR);
        } catch (Throwable $e) {
            Response::error('Malformed JSON body', 400);
        }
        if (!is_array($body)
            || self::hasDuplicateTopLevelJsonKeys($readResult['body'])
            || array_keys($body) !== ['install_id']) {
            Response::error('Missing or invalid install_id', 422);
        }
        $installId = $body['install_id'] ?? null;
        if (!is_string($installId) || !EntitlementJwtIssuer::isCanonicalUuid($installId)) {
            Response::error('Invalid install_id', 422);
        }
        return ['install_id' => $installId];
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

    /** Read at most maxBytes + 1 so oversize input is detected without an unbounded read. */
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

    /**
     * PHP's json_decode keeps the last value for duplicate object keys. The
     * refresh contract is stricter: duplicate top-level keys are rejected
     * before the decoded array is trusted. Nested objects are intentionally
     * not part of that contract.
     */
    private static function hasDuplicateTopLevelJsonKeys(string $json): bool
    {
        $length = strlen($json);
        $offset = 0;
        self::skipJsonWhitespace($json, $offset);
        if ($offset >= $length || $json[$offset] !== '{') {
            return false;
        }
        $offset++;
        self::skipJsonWhitespace($json, $offset);
        if ($offset < $length && $json[$offset] === '}') {
            return false;
        }

        $seen = [];
        while ($offset < $length) {
            self::skipJsonWhitespace($json, $offset);
            $keyStart = $offset;
            $keyEnd = self::consumeJsonString($json, $offset);
            if ($keyEnd === null) {
                return false;
            }
            try {
                $key = json_decode(substr($json, $keyStart, $keyEnd - $keyStart), true, 512, JSON_THROW_ON_ERROR);
            } catch (Throwable $e) {
                return false;
            }
            if (!is_string($key)) {
                return false;
            }
            if (array_key_exists($key, $seen)) {
                return true;
            }
            $seen[$key] = true;
            $offset = $keyEnd;

            self::skipJsonWhitespace($json, $offset);
            if ($offset >= $length || $json[$offset] !== ':') {
                return false;
            }
            $offset++;
            self::skipJsonWhitespace($json, $offset);
            $valueEnd = self::consumeJsonValue($json, $offset);
            if ($valueEnd === null) {
                return false;
            }
            $offset = $valueEnd;
            self::skipJsonWhitespace($json, $offset);
            if ($offset >= $length) {
                return false;
            }
            if ($json[$offset] === ',') {
                $offset++;
                continue;
            }
            return false;
        }

        return false;
    }

    private static function skipJsonWhitespace(string $json, int &$offset): void
    {
        $length = strlen($json);
        while ($offset < $length && str_contains(" \t\r\n", $json[$offset])) {
            $offset++;
        }
    }

    private static function consumeJsonString(string $json, int $offset): ?int
    {
        $length = strlen($json);
        if ($offset >= $length || $json[$offset] !== '"') {
            return null;
        }
        $offset++;
        $escaped = false;
        while ($offset < $length) {
            $character = $json[$offset];
            if ($escaped) {
                $escaped = false;
            } elseif ($character === '\\') {
                $escaped = true;
            } elseif ($character === '"') {
                return $offset + 1;
            }
            $offset++;
        }
        return null;
    }

    /** Return the offset of the next top-level comma or closing brace. */
    private static function consumeJsonValue(string $json, int $offset): ?int
    {
        $length = strlen($json);
        $depth = 0;
        $inString = false;
        $escaped = false;
        while ($offset < $length) {
            $character = $json[$offset];
            if ($inString) {
                if ($escaped) {
                    $escaped = false;
                } elseif ($character === '\\') {
                    $escaped = true;
                } elseif ($character === '"') {
                    $inString = false;
                }
                $offset++;
                continue;
            }
            if ($character === '"') {
                $inString = true;
                $offset++;
                continue;
            }
            if ($character === '{' || $character === '[') {
                $depth++;
                $offset++;
                continue;
            }
            if ($character === '}' || $character === ']') {
                if ($depth > 0) {
                    $depth--;
                    $offset++;
                    continue;
                }
                return $character === '}' ? $offset : null;
            }
            if ($depth === 0 && $character === ',') {
                return $offset;
            }
            $offset++;
        }
        return null;
    }

    private static function requireConfig(array $config, string $scope): void
    {
        if (!empty($config['validation'][$scope])) {
            Response::error('Service configuration unavailable', 503);
        }
    }

    private static function respondToProviderFailure(array $provider, string $rejectedMessage): void
    {
        $status = $provider['status'] ?? null;
        if (in_array($status, ['unavailable', 'malformed'], true)) {
            Response::error('Purchase verification service unavailable', 503);
        }
        if ($status === 'mismatch') {
            Response::error('Purchase verification identity mismatch', 503);
        }
        if ($status === 'rejected') {
            $httpCode = self::providerHttpCode($provider);
            if ($httpCode !== null
                && $httpCode >= 400
                && $httpCode <= 499
                && $httpCode !== 408
                && $httpCode !== 429
                && !self::providerHasTransportError($provider)) {
                Response::error($rejectedMessage, 402);
            }
            Response::error('Purchase verification service unavailable', 503);
        }
        if (!in_array($status, ['active', 'pending', 'refunded', 'revoked'], true)) {
            Response::error('Purchase verification service unavailable', 503);
        }
    }

    private static function obtainGoogleAccessToken(array $config): string
    {
        try {
            $result = PlayDeveloperApiClient::getAccessToken($config);
        } catch (Throwable $e) {
            Response::error('Purchase verification service unavailable', 503);
        }
        if (($result['status'] ?? null) === 'rejected') {
            Response::error('Purchase verification failed', 402);
        }
        if (($result['status'] ?? null) !== 'ok'
            || !is_string($result['access_token'] ?? null)
            || $result['access_token'] === '') {
            Response::error('Purchase verification service unavailable', 503);
        }
        return $result['access_token'];
    }

    private static function providerHttpCode(array $provider): ?int
    {
        foreach (['httpCode', 'http_code', 'providerHttpCode', 'provider_http_code', 'statusCode', 'status_code'] as $key) {
            $value = $provider[$key] ?? null;
            if (is_int($value)) {
                return $value;
            }
            if (is_string($value) && preg_match('/\A[0-9]{3}\z/', $value) === 1) {
                return (int)$value;
            }
        }
        return null;
    }

    private static function providerHasTransportError(array $provider): bool
    {
        $error = $provider['error'] ?? null;
        return is_string($error)
            && in_array($error, ['network_error', 'timeout', 'transport_error'], true);
    }

    private static function preserveProviderMetadata(array $provider): array
    {
        $preserved = [
            'status' => $provider['status'] ?? 'unavailable',
            'receipt' => is_array($provider['receipt'] ?? null) ? $provider['receipt'] : null,
            'purchased_at' => is_int($provider['purchased_at'] ?? null) ? $provider['purchased_at'] : null,
        ];
        foreach (['httpCode', 'http_code', 'providerHttpCode', 'provider_http_code', 'statusCode', 'status_code', 'error'] as $key) {
            if (array_key_exists($key, $provider)) {
                $preserved[$key] = $provider[$key];
            }
        }
        return $preserved;
    }

    private static function acknowledgeIfRequired(
        array $config,
        array $provider,
        string $productId,
        string $purchaseToken,
        ?string $googleAccessToken = null
    ): void {
        if (($provider['status'] ?? null) !== 'active' || ($provider['store'] ?? null) !== 'google_play') {
            return;
        }
        $receipt = $provider['receipt'] ?? null;
        $acknowledgementState = is_array($receipt) ? ($receipt['acknowledgementState'] ?? null) : null;
        if (!is_int($acknowledgementState) || !in_array($acknowledgementState, [0, 1], true)) {
            Response::error('Purchase verification service unavailable', 503);
        }
        if ($acknowledgementState === 1) {
            return;
        }

        try {
            if ($googleAccessToken === null) {
                $tokenResult = PlayDeveloperApiClient::getAccessToken($config);
                if (($tokenResult['status'] ?? null) !== 'ok'
                    || !is_string($tokenResult['access_token'] ?? null)
                    || $tokenResult['access_token'] === '') {
                    // Acknowledgement failures are deliberately not exposed
                    // as purchase rejection, including OAuth 4xx failures.
                    Response::error('Purchase acknowledgement service unavailable', 503);
                }
                $googleAccessToken = $tokenResult['access_token'];
            }
            if (!is_string($googleAccessToken) || $googleAccessToken === '') {
                Response::error('Purchase acknowledgement service unavailable', 503);
            }
            $result = PlayDeveloperApiClient::acknowledgeProductPurchase(
                $googleAccessToken,
                $config['android_package_name'],
                $productId,
                $purchaseToken
            );
        } catch (Throwable $e) {
            Response::error('Purchase acknowledgement service unavailable', 503);
        }
        if (!is_array($result)
            || !is_int($result['httpCode'] ?? null)
            || $result['httpCode'] < 200
            || $result['httpCode'] >= 300) {
            Response::error('Purchase acknowledgement service unavailable', 503);
        }
    }

    private static function recordTerminalVerify(array $config, array $input, array $provider): void
    {
        $filter = self::configuredProductFilter($config);
        if ($filter['sql'] === '') {
            Response::error('Service configuration unavailable', 503);
        }

        $pdo = self::database();
        try {
            $snapshot = self::discoverActiveEntitlementCandidates(
                $pdo,
                $input['install_id'],
                $filter['sql'],
                $filter['params']
            )->fetchAll();
        } catch (PDOException $e) {
            Response::error('Internal server error', 500);
        }

        // The requested terminal purchase has just been verified by the
        // provider, so it is excluded from the fallback set. Every other
        // active row must be checked against its provider before it can
        // support a JWT; a stale active row is never sufficient.
        $requestedIdentity = self::identityKey($input['store'], $input['purchase_token']);
        $candidates = array_values(array_filter(
            $snapshot,
            static fn (array $row): bool => self::identityKey(
                (string)($row['store'] ?? ''),
                (string)($row['purchase_token'] ?? '')
            ) !== $requestedIdentity
        ));

        $candidateStores = [];
        foreach ($candidates as $row) {
            $store = $row['store'] ?? null;
            $rowProductId = $row['product_id'] ?? null;
            $purchaseToken = $row['purchase_token'] ?? null;
            if (!is_string($store) || !is_string($rowProductId) || !is_string($purchaseToken)
                || self::productForStore($config, $store) !== $rowProductId) {
                Response::error('Invalid stored entitlement', 500);
            }
            $candidateStores[$store] = true;
        }

        $googleAccessToken = null;
        if (isset($candidateStores['google_play'])) {
            self::requireConfig($config, 'google');
            $googleAccessToken = self::obtainGoogleAccessToken($config);
        }
        if (isset($candidateStores['huawei'])) {
            self::requireConfig($config, 'huawei');
        }

        $providerResults = [];
        foreach ($candidates as $row) {
            try {
                $candidateProvider = self::verifyPurchase(
                    $config,
                    $row['store'],
                    $row['product_id'],
                    $row['purchase_token'],
                    $googleAccessToken
                );
            } catch (Throwable $e) {
                Response::error('Purchase verification service unavailable', 503);
            }
            if ($candidateProvider['status'] === 'mismatch'
                || !self::providerMatchesRow($candidateProvider, $row)) {
                Response::error('Purchase verification identity mismatch', 503);
            }
            self::respondToProviderFailure($candidateProvider, 'Purchase verification failed');
            if ($candidateProvider['status'] === 'pending') {
                Response::error('Purchase is pending', 402);
            }
            $providerResults[self::identityKey($row['store'], $row['purchase_token'])] = $candidateProvider;
        }

        // Acknowledge every provider-confirmed Google PURCHASED candidate
        // before any terminal/active DB mutation or JWT issuance.
        foreach ($candidates as $row) {
            $identity = self::identityKey($row['store'], $row['purchase_token']);
            self::acknowledgeIfRequired(
                $config,
                $providerResults[$identity],
                $row['product_id'],
                $row['purchase_token'],
                $googleAccessToken
            );
        }

        $activeProductId = null;
        try {
            $pdo->beginTransaction();
            $lockRow = $pdo->prepare(
                'SELECT id, install_id, product_id, status
                 FROM purchase_events
                 WHERE store = ? AND purchase_token = ?
                 FOR UPDATE'
            );
            $lockRow->execute([$input['store'], $input['purchase_token']]);
            $existing = $lockRow->fetch();
            if ($existing !== false && $existing['install_id'] !== $input['install_id']) {
                $pdo->rollBack();
                Response::error('Purchase token already associated with a different device', 409);
            }
            if ($existing !== false && $existing['product_id'] !== $input['product_id']) {
                $pdo->rollBack();
                Response::error('Purchase token is associated with a different product', 409);
            }

            $terminalStatus = ($provider['status'] ?? null) === 'refunded' ? 'refunded' : 'revoked';
            $terminalValues = [
                $terminalStatus,
                self::purchaseDate($provider['purchased_at'] ?? null),
                date('Y-m-d H:i:s'),
                self::encodeReceipt($provider['receipt'] ?? null),
            ];
            if ($existing !== false) {
                $statement = $pdo->prepare(
                    'UPDATE purchase_events
                     SET status = ?, purchased_at = ?, revoked_at = ?, raw_receipt = ?
                     WHERE id = ? AND store = ? AND purchase_token = ?'
                );
                $statement->execute(array_merge($terminalValues, [
                    $existing['id'],
                    $input['store'],
                    $input['purchase_token'],
                ]));
            } else {
                $statement = $pdo->prepare(
                    'INSERT INTO purchase_events
                         (install_id, store, product_id, purchase_token, status, purchased_at, revoked_at, raw_receipt)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)'
                );
                $statement->execute([
                    $input['install_id'],
                    $input['store'],
                    $input['product_id'],
                    $input['purchase_token'],
                    $terminalValues[0],
                    $terminalValues[1],
                    $terminalValues[2],
                    $terminalValues[3],
                ]);
            }

            $currentCandidates = self::discoverActiveEntitlementCandidates(
                $pdo,
                $input['install_id'],
                $filter['sql'],
                $filter['params'],
                true
            )->fetchAll();
            $currentCandidates = array_values(array_filter(
                $currentCandidates,
                static fn (array $row): bool => self::identityKey(
                    (string)($row['store'] ?? ''),
                    (string)($row['purchase_token'] ?? '')
                ) !== $requestedIdentity
            ));
            if (!self::snapshotsMatch($candidates, $currentCandidates)) {
                $pdo->rollBack();
                Response::error('Entitlement changed while verifying purchase', 409);
            }

            foreach ($candidates as $row) {
                $identity = self::identityKey($row['store'], $row['purchase_token']);
                $candidateProvider = $providerResults[$identity];
                $currentRow = null;
                foreach ($currentCandidates as $candidate) {
                    if (self::identityKey($candidate['store'], $candidate['purchase_token']) === $identity) {
                        $currentRow = $candidate;
                        break;
                    }
                }
                if (!is_array($currentRow)) {
                    $pdo->rollBack();
                    Response::error('Entitlement changed while verifying purchase', 409);
                }

                $status = $candidateProvider['status'] === 'active'
                    ? 'active'
                    : ($candidateProvider['status'] === 'refunded' ? 'refunded' : 'revoked');
                $update = $pdo->prepare(
                    'UPDATE purchase_events
                     SET status = ?, revoked_at = ?, raw_receipt = ?
                     WHERE id = ? AND install_id = ? AND store = ? AND product_id = ?
                       AND purchase_token = ? AND status = \'active\''
                );
                $update->execute([
                    $status,
                    $status === 'active' ? null : date('Y-m-d H:i:s'),
                    self::encodeReceipt($candidateProvider['receipt']),
                    $currentRow['id'],
                    $input['install_id'],
                    $currentRow['store'],
                    $currentRow['product_id'],
                    $currentRow['purchase_token'],
                ]);
                $affectedRows = $update->rowCount();
                if ($affectedRows !== 1 && !($affectedRows === 0 && $status === 'active')) {
                    $pdo->rollBack();
                    Response::error('Failed to update entitlement state', 500);
                }
                if ($status === 'active' && ($activeProductId === null || $currentRow['product_id'] === $input['product_id'])) {
                    $activeProductId = $currentRow['product_id'];
                }
            }
            $pdo->commit();
        } catch (PDOException $e) {
            if ($pdo->inTransaction()) {
                $pdo->rollBack();
            }
            if ((string)$e->getCode() === '23000') {
                Response::error('Purchase token conflict', 409);
            }
            Response::error('Failed to update entitlement state', 500);
        }

        if ($activeProductId === null) {
            Response::error('No active entitlement found', 403);
        }
        $jwt = EntitlementJwtIssuer::issue($config, $input['install_id'], $activeProductId);
        Response::success(['jwt' => $jwt, 'expires_at' => EntitlementJwtIssuer::expiresAt()]);
    }

    private static function selectActiveProductId(array $rows, string $preferredProductId): string
    {
        foreach ($rows as $row) {
            if (($row['product_id'] ?? null) === $preferredProductId) {
                return $preferredProductId;
            }
        }
        foreach ($rows as $row) {
            if (is_string($row['product_id'] ?? null) && $row['product_id'] !== '') {
                return $row['product_id'];
            }
        }
        Response::error('Invalid stored entitlement', 500);
    }

    private static function database(): PDO
    {
        try {
            return Database::connection();
        } catch (PDOException $e) {
            Response::error('Internal server error', 500);
        }
    }

    /**
     * @return array{status:string,receipt:array|null,purchased_at:int|null,
     *     store?:string,product_id?:string,purchase_token?:string,httpCode?:int,error?:string|null}
     */
    private static function verifyPurchase(
        array $config,
        string $store,
        string $productId,
        string $purchaseToken,
        ?string $googleAccessToken = null
    ): array {
        if ($store === 'huawei') {
            $huaweiResult = HuaweiIapVerifier::verify(
                $config,
                $config['android_package_name'],
                $productId,
                $purchaseToken
            );
            // HuaweiIapVerifier currently exposes only a normalized status;
            // when it does not return an HTTP code, a rejected result cannot
            // be proven to be a non-transient 4xx here and remains 503.
            // Explicit provider HTTP metadata, when available, is preserved
            // and classified by respondToProviderFailure().
            return self::withProviderIdentity(
                self::preserveProviderMetadata($huaweiResult),
                $store,
                $productId,
                $purchaseToken
            );
        }
        if ($store !== 'google_play') {
            return self::withProviderIdentity(
                ['status' => 'mismatch', 'receipt' => null, 'purchased_at' => null],
                $store,
                $productId,
                $purchaseToken
            );
        }

        if ($googleAccessToken === null) {
            $tokenResult = PlayDeveloperApiClient::getAccessToken($config);
            if (($tokenResult['status'] ?? null) !== 'ok'
                || !is_string($tokenResult['access_token'] ?? null)
                || $tokenResult['access_token'] === '') {
                return self::withProviderIdentity(
                    [
                        'status' => ($tokenResult['status'] ?? null) === 'rejected'
                            ? 'rejected'
                            : 'unavailable',
                        'receipt' => null,
                        'purchased_at' => null,
                        'httpCode' => is_int($tokenResult['httpCode'] ?? null)
                            ? $tokenResult['httpCode']
                            : 0,
                        'error' => is_string($tokenResult['error'] ?? null)
                            ? $tokenResult['error']
                            : null,
                    ],
                    $store,
                    $productId,
                    $purchaseToken
                );
            }
            $googleAccessToken = $tokenResult['access_token'];
        }
        $result = PlayDeveloperApiClient::getProductPurchase(
            $googleAccessToken,
            $config['android_package_name'],
            $productId,
            $purchaseToken
        );
        $httpCode = $result['httpCode'] ?? null;
        $httpClass = self::classifyProviderHttpCode($httpCode, ($result['error'] ?? null) !== null);
        if ($httpClass === 'unavailable') {
            return self::withProviderIdentity(
                [
                    'status' => 'unavailable',
                    'receipt' => null,
                    'purchased_at' => null,
                    'httpCode' => is_int($httpCode) ? $httpCode : 0,
                    'error' => is_string($result['error'] ?? null) ? $result['error'] : null,
                ],
                $store,
                $productId,
                $purchaseToken
            );
        }
        if ($httpClass === 'rejected') {
            return self::withProviderIdentity(
                [
                    'status' => 'rejected',
                    'receipt' => null,
                    'purchased_at' => null,
                    'httpCode' => is_int($httpCode) ? $httpCode : 0,
                    'error' => is_string($result['error'] ?? null) ? $result['error'] : null,
                ],
                $store,
                $productId,
                $purchaseToken
            );
        }
        if (!is_array($result['body'])) {
            return self::withProviderIdentity(
                [
                    'status' => 'malformed',
                    'receipt' => null,
                    'purchased_at' => null,
                    'httpCode' => is_int($httpCode) ? $httpCode : 0,
                    'error' => is_string($result['error'] ?? null) ? $result['error'] : null,
                ],
                $store,
                $productId,
                $purchaseToken
            );
        }
        $playData = $result['body'];
        foreach ([
            'productId' => [$productId, self::MAX_PRODUCT_ID_LENGTH],
            'purchaseToken' => [$purchaseToken, self::MAX_PURCHASE_TOKEN_LENGTH],
        ] as $field => [$expected, $maxLength]) {
            $actual = $playData[$field] ?? null;
            if (!array_key_exists($field, $playData)
                || !is_string($actual)
                || $actual === ''
                || strlen($actual) > $maxLength
                || preg_match('/[\x00-\x1F\x7F]/', $actual) === 1) {
                return self::withProviderIdentity(
                    ['status' => 'malformed', 'receipt' => $playData, 'purchased_at' => null],
                    $store,
                    $productId,
                    $purchaseToken
                );
            }
            if ($actual !== $expected) {
                return self::withGoogleProviderIdentity(
                    ['status' => 'mismatch', 'receipt' => $playData, 'purchased_at' => null],
                    $store,
                    $playData
                );
            }
        }
        $milliseconds = $playData['purchaseTimeMillis'] ?? null;
        $purchasedAt = self::googlePurchaseTimeSeconds($milliseconds);
        if ($purchasedAt === null) {
            return self::withGoogleProviderIdentity(
                ['status' => 'malformed', 'receipt' => $playData, 'purchased_at' => null],
                $store,
                $playData
            );
        }
        if (!array_key_exists('purchaseState', $playData)
            || !is_int($playData['purchaseState'])
            || !in_array($playData['purchaseState'], [0, 1, 2], true)) {
            return self::withGoogleProviderIdentity(
                ['status' => 'malformed', 'receipt' => $playData, 'purchased_at' => null],
                $store,
                $playData
            );
        }
        $state = $playData['purchaseState'];
        if ($state === 1) {
            return self::withGoogleProviderIdentity(
                ['status' => 'revoked', 'receipt' => $playData, 'purchased_at' => $purchasedAt],
                $store,
                $playData
            );
        }
        if ($state === 2) {
            return self::withGoogleProviderIdentity(
                ['status' => 'pending', 'receipt' => $playData, 'purchased_at' => $purchasedAt],
                $store,
                $playData
            );
        }
        if (!array_key_exists('acknowledgementState', $playData)
            || !is_int($playData['acknowledgementState'])
            || !in_array($playData['acknowledgementState'], [0, 1], true)) {
            return self::withGoogleProviderIdentity(
                ['status' => 'malformed', 'receipt' => $playData, 'purchased_at' => null],
                $store,
                $playData
            );
        }
        return self::withGoogleProviderIdentity(
            ['status' => 'active', 'receipt' => $playData, 'purchased_at' => $purchasedAt],
            $store,
            $playData
        );
    }

    private static function classifyProviderHttpCode($httpCode, bool $hasTransportError): string
    {
        if ($hasTransportError || !is_int($httpCode)) {
            return 'unavailable';
        }
        if ($httpCode >= 200 && $httpCode <= 299) {
            return 'ok';
        }
        if ($httpCode === 408 || $httpCode === 429 || ($httpCode >= 500 && $httpCode <= 599)) {
            return 'unavailable';
        }
        if ($httpCode >= 400 && $httpCode <= 499) {
            return 'rejected';
        }
        return 'unavailable';
    }

    private static function googlePurchaseTimeSeconds($value): ?int
    {
        if (is_int($value)) {
            $milliseconds = $value;
        } elseif (is_string($value) && preg_match('/\A(?:0|[1-9][0-9]*)\z/', $value) === 1) {
            // A digit string is accepted only in its canonical JSON form;
            // coercion of leading-zero, float, exponent, or signed values is
            // intentionally forbidden.
            if (strlen($value) > 13) {
                return null;
            }
            $milliseconds = (int)$value;
        } else {
            return null;
        }
        if ($milliseconds <= 0
            || $milliseconds < 946684800000
            || $milliseconds > 4102444800000) {
            return null;
        }
        return intdiv($milliseconds, 1000);
    }

    private static function productForStore(array $config, string $store): ?string
    {
        $product = match ($store) {
            'google_play' => $config['play_product_id'] ?? null,
            'huawei' => $config['huawei_product_id'] ?? null,
            default => null,
        };
        return is_string($product) && $product !== '' ? $product : null;
    }

    private static function encodeReceipt(?array $receipt): ?string
    {
        if ($receipt === null) {
            return null;
        }
        $encoded = json_encode($receipt, JSON_UNESCAPED_SLASHES);
        return $encoded === false ? null : $encoded;
    }

    private static function discoverActiveEntitlementCandidates(
        PDO $pdo,
        string $installId,
        string $productFilter,
        array $productParams,
        bool $forUpdate = false
    ): PDOStatement {
        $sql = "SELECT id, install_id, store, product_id, purchase_token, status, purchased_at, revoked_at, updated_at
                FROM purchase_events
                WHERE install_id = ? AND status = 'active'
                  AND (" . $productFilter . ")
                ORDER BY store, purchase_token";
        if ($forUpdate) {
            $sql .= ' FOR UPDATE';
        }
        $statement = $pdo->prepare($sql);
        $statement->execute(array_merge([$installId], $productParams));
        return $statement;
    }

    private static function withProviderIdentity(
        array $result,
        string $store,
        string $productId,
        string $purchaseToken
    ): array {
        // Preserve provider-sourced identity. The request identity may fill
        // the Huawei verifier's legacy result shape, but must never replace a
        // value returned by a provider response.
        if (!array_key_exists('store', $result)) {
            $result['store'] = $store;
        }
        if (!array_key_exists('product_id', $result)) {
            $result['product_id'] = $productId;
        }
        if (!array_key_exists('purchase_token', $result)) {
            $result['purchase_token'] = $purchaseToken;
        }
        return $result;
    }

    private static function withGoogleProviderIdentity(array $result, string $store, array $provider): array
    {
        $result['store'] = $store;
        $result['product_id'] = $provider['productId'];
        $result['purchase_token'] = $provider['purchaseToken'];
        return $result;
    }

    private static function providerMatchesRow(array $provider, array $row): bool
    {
        return ($provider['store'] ?? null) === ($row['store'] ?? null)
            && ($provider['product_id'] ?? null) === ($row['product_id'] ?? null)
            && ($provider['purchase_token'] ?? null) === ($row['purchase_token'] ?? null);
    }

    /** @return array{sql:string,params:array<int,string>} */
    private static function configuredProductFilter(array $config): array
    {
        $conditions = [];
        $params = [];
        foreach (EntitlementJwtIssuer::configuredProductPairs($config) as $pair) {
            $conditions[] = '(store = ? AND product_id = ?)';
            $params[] = $pair['store'];
            $params[] = $pair['product_id'];
        }
        return ['sql' => implode(' OR ', $conditions), 'params' => $params];
    }

    private static function identityKey(string $store, string $purchaseToken): string
    {
        return $store . "\0" . $purchaseToken;
    }

    private static function sameCandidateIdentities(array $expected, array $actual): bool
    {
        $identities = static function (array $rows): array {
            $keys = [];
            foreach ($rows as $row) {
                if (!is_array($row)
                    || !is_string($row['store'] ?? null)
                    || !is_string($row['purchase_token'] ?? null)) {
                    return [];
                }
                $keys[] = self::identityKey($row['store'], $row['purchase_token']);
            }
            sort($keys, SORT_STRING);
            return $keys;
        };

        return $identities($expected) === $identities($actual);
    }

    private static function snapshotsMatch(array $expected, array $actual): bool
    {
        $normalize = static function (array $rows): array {
            $normalized = [];
            foreach ($rows as $row) {
                if (!is_array($row)) {
                    return [];
                }
                $normalized[] = [
                    'id' => (string)($row['id'] ?? ''),
                    'install_id' => (string)($row['install_id'] ?? ''),
                    'store' => (string)($row['store'] ?? ''),
                    'product_id' => (string)($row['product_id'] ?? ''),
                    'purchase_token' => (string)($row['purchase_token'] ?? ''),
                    'status' => (string)($row['status'] ?? ''),
                    'purchased_at' => (string)($row['purchased_at'] ?? ''),
                    'revoked_at' => (string)($row['revoked_at'] ?? ''),
                    'updated_at' => (string)($row['updated_at'] ?? ''),
                ];
            }
            usort($normalized, static fn(array $a, array $b): int =>
                strcmp($a['store'] . "\0" . $a['purchase_token'], $b['store'] . "\0" . $b['purchase_token']));
            return $normalized;
        };

        return $normalize($expected) === $normalize($actual);
    }

    private static function purchaseDate(?int $unixSeconds): string
    {
        return date('Y-m-d H:i:s', $unixSeconds !== null && $unixSeconds > 0 ? $unixSeconds : time());
    }

}
