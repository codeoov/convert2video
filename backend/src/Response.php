<?php

final class Response
{
    public static function json(array $data, int $statusCode = 200): void
    {
        http_response_code($statusCode);
        header('Content-Type: application/json; charset=utf-8');
        echo json_encode($data);
        exit;
    }

    public static function success(array $data = []): void
    {
        self::json(['ok' => true, 'data' => $data]);
    }

    public static function error(string $message, int $statusCode = 400): void
    {
        self::json(['ok' => false, 'error' => $message], $statusCode);
    }
}
