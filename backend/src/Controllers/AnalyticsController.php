<?php

final class AnalyticsController
{
    public function ingest(): void
    {
        $body = json_decode(file_get_contents('php://input'), true) ?? [];
        $installId = $body['install_id'] ?? null;
        $eventName = $body['event_name'] ?? null;
        if (!$installId || !$eventName) {
            Response::error('install_id and event_name required', 422);
        }

        $stmt = Database::connection()->prepare(
            'INSERT INTO analytics_events (install_id, event_name, payload, app_version)
             VALUES (:install_id, :event_name, :payload, :app_version)'
        );
        $stmt->execute([
            'install_id' => $installId,
            'event_name' => $eventName,
            'payload' => isset($body['payload']) ? json_encode($body['payload']) : null,
            'app_version' => $body['app_version'] ?? null,
        ]);

        Response::success();
    }
}
