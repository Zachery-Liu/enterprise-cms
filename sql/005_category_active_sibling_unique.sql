-- Incremental upgrade ONLY for databases that already have cms_category without
-- active_parent_key / active_name. A fresh installation using the current 002
-- already contains them and MUST NOT execute this script again.
-- Inspect and resolve duplicate active siblings before the ALTER. No rows are
-- deleted, renamed or merged by this script. Back up and review before applying.

SELECT COALESCE(parent_id, 0) AS sibling_parent_key, name, COUNT(*) AS duplicate_count
FROM cms_category
WHERE deleted = 0
GROUP BY COALESCE(parent_id, 0), name
HAVING COUNT(*) > 1;

ALTER TABLE cms_category
    ADD COLUMN active_parent_key BIGINT UNSIGNED GENERATED ALWAYS AS
        (CASE WHEN deleted = 0 THEN COALESCE(parent_id, 0) ELSE NULL END),
    ADD COLUMN active_name VARCHAR(64) GENERATED ALWAYS AS
        (CASE WHEN deleted = 0 THEN name ELSE NULL END),
    ADD CONSTRAINT uq_category_active_sibling UNIQUE (active_parent_key, active_name);
