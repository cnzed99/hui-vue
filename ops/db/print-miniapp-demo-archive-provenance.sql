-- Candidate only. Apply before starting a backend that reads the new columns.
ALTER TABLE print_miniapp_demo_file
    ADD COLUMN archive_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    ADD COLUMN archive_entry_ordinal INT NULL,
    ADD CONSTRAINT chk_print_demo_file_archive_source CHECK (
        (archive_sha256 IS NULL AND archive_entry_ordinal IS NULL)
        OR
        (archive_sha256 IS NOT NULL AND archive_entry_ordinal IS NOT NULL
            AND archive_entry_ordinal BETWEEN 0 AND 19)
    );
