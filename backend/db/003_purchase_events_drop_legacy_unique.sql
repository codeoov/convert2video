-- Stage 4 only: the composite key must already exist before the legacy key is
-- removed. This changes indexes only and preserves every purchase_events row.

SET @c2v_has_store_token_key := (
    SELECT IF(
        COUNT(*) = 2
        AND SUM(NON_UNIQUE = 0) = 2
        AND SUM(SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'store') = 1
        AND SUM(SEQ_IN_INDEX = 2 AND COLUMN_NAME = 'purchase_token') = 1,
        1,
        0
    )
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'purchase_events'
      AND INDEX_NAME = 'uniq_store_purchase_token'
);

SET @c2v_has_legacy_key := (
    SELECT IF(
        COUNT(*) = 1
        AND SUM(NON_UNIQUE = 0) = 1
        AND SUM(SEQ_IN_INDEX = 1 AND COLUMN_NAME = 'purchase_token') = 1,
        1,
        0
    )
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'purchase_events'
      AND INDEX_NAME = 'uniq_purchase_token'
);

SET @c2v_legacy_index_entries := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'purchase_events'
      AND INDEX_NAME = 'uniq_purchase_token'
);

-- A missing prerequisite deliberately references a non-existent index so the
-- migration aborts rather than leaving the table without a uniqueness guard.
SET @c2v_drop_legacy_key_sql := CASE
    WHEN @c2v_has_store_token_key = 0
        THEN 'ALTER TABLE purchase_events DROP INDEX __c2v_missing_composite_prerequisite__'
    WHEN @c2v_legacy_index_entries = 0
        THEN 'SELECT 1'
    WHEN @c2v_has_legacy_key = 0
        THEN 'ALTER TABLE purchase_events DROP INDEX __c2v_wrong_legacy_index_guard_failure__'
    ELSE 'ALTER TABLE purchase_events DROP INDEX uniq_purchase_token'
END;

PREPARE c2v_drop_legacy_key FROM @c2v_drop_legacy_key_sql;
EXECUTE c2v_drop_legacy_key;
DEALLOCATE PREPARE c2v_drop_legacy_key;
