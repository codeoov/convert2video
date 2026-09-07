CREATE TABLE IF NOT EXISTS purchase_events (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    install_id CHAR(36) NOT NULL,
    store VARCHAR(32) NOT NULL DEFAULT 'google_play',
    product_id VARCHAR(64) NOT NULL,
    purchase_token VARCHAR(512) NOT NULL,
    status ENUM('active','refunded','revoked') NOT NULL DEFAULT 'active',
    purchased_at DATETIME NOT NULL,
    revoked_at DATETIME NULL,
    raw_receipt JSON NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uniq_store_purchase_token (store, purchase_token),
    KEY idx_install_id (install_id),
    KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS rtdn_notifications (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    notification_type VARCHAR(64) NOT NULL,
    purchase_token VARCHAR(512) NULL,
    raw_payload JSON NOT NULL,
    processed TINYINT(1) NOT NULL DEFAULT 0,
    processed_at DATETIME NULL,
    received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_processed (processed),
    KEY idx_purchase_token (purchase_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS analytics_events (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    install_id CHAR(36) NOT NULL,
    event_name VARCHAR(64) NOT NULL,
    payload JSON NULL,
    app_version VARCHAR(32) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_install_id (install_id),
    KEY idx_event_name (event_name),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS crash_reports (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    install_id CHAR(36) NOT NULL,
    app_version VARCHAR(32) NULL,
    os_version VARCHAR(32) NULL,
    device_model VARCHAR(128) NULL,
    stack_trace MEDIUMTEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_install_id (install_id),
    KEY idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
