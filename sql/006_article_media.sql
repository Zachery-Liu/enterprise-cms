CREATE TABLE cms_media_image (
    id CHAR(36) PRIMARY KEY,
    storage_key CHAR(36) NOT NULL UNIQUE,
    mime_type VARCHAR(32) NOT NULL,
    width INT UNSIGNED NOT NULL,
    height INT UNSIGNED NOT NULL,
    byte_size BIGINT UNSIGNED NOT NULL,
    uploader_id BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(3) NOT NULL,
    FOREIGN KEY (uploader_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
