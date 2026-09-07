<?php

require_once __DIR__ . '/../src/EnvLoader.php';
require_once __DIR__ . '/../src/Database.php';
require_once __DIR__ . '/../src/Response.php';
require_once __DIR__ . '/../src/GoogleServiceAccountAuth.php';
require_once __DIR__ . '/../src/PlayDeveloperApiClient.php';
require_once __DIR__ . '/../src/EntitlementJwtIssuer.php';
require_once __DIR__ . '/../src/HuaweiIapVerifier.php';
require_once __DIR__ . '/../src/Controllers/EntitlementController.php';
require_once __DIR__ . '/../src/GoogleCertsVerifier.php';
require_once __DIR__ . '/../src/Controllers/WebhookController.php';
require_once __DIR__ . '/../src/Controllers/AnalyticsController.php';
require_once __DIR__ . '/../src/Controllers/CrashController.php';

$path = parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH);
$method = $_SERVER['REQUEST_METHOD'];

$routes = [
    'POST /entitlement/verify' => [EntitlementController::class, 'verify'],
    'POST /entitlement/refresh' => [EntitlementController::class, 'refresh'],
    'POST /webhooks/rtdn' => [WebhookController::class, 'handleRtdn'],
    'POST /events' => [AnalyticsController::class, 'ingest'],
    'POST /crash' => [CrashController::class, 'ingest'],
];

$key = "$method $path";
if (!isset($routes[$key])) {
    Response::error('Not found', 404);
}

[$class, $methodName] = $routes[$key];

try {
    (new $class())->$methodName();
} catch (Throwable $e) {
    error_log(sprintf('[%s] %s: %s', $key, get_class($e), $e->getMessage()));
    Response::error('Internal server error', 500);
}
