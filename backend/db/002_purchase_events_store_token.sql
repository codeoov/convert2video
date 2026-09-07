-- Stage 1: run 002_purchase_events_store_token_preflight.php and require exit 0.
-- Stage 2: add the composite identity while the legacy key remains in place.
-- Stage 3: deploy code that reads/writes by (store, purchase_token).
-- Stage 4: run 003_purchase_events_drop_legacy_unique.sql.
-- No table rebuild or row/data deletion is performed here.

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

-- Database-level guard: direct execution of this file must stop before any
-- ADD DDL when legacy rows contain duplicate (store, purchase_token) keys.
-- The invalid-column branch is deliberate: PREPARE/EXECUTE fails non-zero and
-- no schema change is attempted. The normal branch is a harmless no-op.
SET @c2v_duplicate_identity_groups := (
    SELECT COUNT(*)
    FROM (
        SELECT store, purchase_token
        FROM purchase_events
        GROUP BY store, purchase_token
        HAVING COUNT(*) > 1
    ) AS c2v_duplicate_identities
);

SET @c2v_duplicate_guard_sql := IF(
    @c2v_duplicate_identity_groups > 0,
    'SELECT __c2v_duplicate_identity_guard_failure__ FROM purchase_events',
    'SELECT 1'
);

PREPARE c2v_duplicate_guard FROM @c2v_duplicate_guard_sql;
EXECUTE c2v_duplicate_guard;
DEALLOCATE PREPARE c2v_duplicate_guard;

SET @c2v_add_store_token_key_sql := IF(
    @c2v_has_store_token_key = 0,
    'ALTER TABLE purchase_events ADD UNIQUE KEY uniq_store_purchase_token (store, purchase_token)',
    'SELECT 1'
);

PREPARE c2v_add_store_token_key FROM @c2v_add_store_token_key_sql;
EXECUTE c2v_add_store_token_key;
DEALLOCATE PREPARE c2v_add_store_token_key;

-- Rollback precondition: before Stage 4, rollback is code-only. After Stage 4,
-- re-adding the legacy key requires a separate duplicate preflight proving that
-- no cross-store duplicate purchase tokens exist.
