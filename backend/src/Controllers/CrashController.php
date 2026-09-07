<?php

final class CrashController
{
    public function ingest(): void
    {
        $body = json_decode(file_get_contents('php://input'), true) ?? [];
        $installId = $body['install_id'] ?? null;
        $stackTrace = $body['stack_trace'] ?? null;
        if (!$installId || !$stackTrace) {
            Response::error('install_id and stack_trace required', 422);
        }

        $stmt = Database::connection()->prepare(
            'INSERT INTO crash_reports (install_id, app_version, os_version, device_model, stack_trace)
             VALUES (:install_id, :app_version, :os_version, :device_model, :stack_trace)'
        );
        $stmt->execute([
            'install_id' => $installId,
            'app_version' => $body['app_version'] ?? null,
            'os_version' => $body['os_version'] ?? null,
            'device_model' => $body['device_model'] ?? null,
            'stack_trace' => $stackTrace,
        ]);

        Response::success();
    }
}
