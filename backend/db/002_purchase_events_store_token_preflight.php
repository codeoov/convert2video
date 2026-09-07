<?php

if (PHP_SAPI !== 'cli') {
    fwrite(STDERR, "Preflight must run from CLI.\n");
    exit(2);
}

require_once __DIR__ . '/../src/Database.php';

$config = require __DIR__ . '/../config.php';
if (!empty($config['validation']['database'])) {
    fwrite(STDERR, "Preflight failed: required database configuration is invalid.\n");
    exit(2);
}

try {
    $pdo = Database::connection();
    $stmt = $pdo->query(
        'SELECT store, COUNT(*) AS duplicate_count
         FROM purchase_events
         GROUP BY store, purchase_token
         HAVING COUNT(*) > 1'
    );

    $duplicateGroups = 0;
    while ($row = $stmt->fetch()) {
        $duplicateGroups++;
        fwrite(STDERR, sprintf(
            "Preflight failed: duplicate identity for store=%s (rows=%d).\n",
            (string)$row['store'],
            (int)$row['duplicate_count']
        ));
    }

    if ($duplicateGroups > 0) {
        exit(1);
    }
} catch (Throwable $e) {
    fwrite(STDERR, "Preflight failed: database inspection unavailable.\n");
    exit(2);
}

fwrite(STDOUT, "Preflight passed: no duplicate (store, purchase_token) identities found.\n");
exit(0);
