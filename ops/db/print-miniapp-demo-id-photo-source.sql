ALTER TABLE print_miniapp_demo_file
    ADD COLUMN source_kind varchar(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DOCUMENT' AFTER processing_attempts,
    ADD COLUMN source_variant varchar(24) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER source_kind,
    ADD CONSTRAINT chk_print_demo_file_source CHECK (
        (source_kind = 'DOCUMENT' AND source_variant IS NULL)
        OR
        (source_kind = 'ID_PHOTO' AND source_variant IS NOT NULL
            AND source_variant IN ('ONE_INCH', 'TWO_INCH', 'THREE_INCH'))
    );

DELIMITER //
CREATE TRIGGER trg_print_demo_quote_document_only
BEFORE INSERT ON print_miniapp_demo_quote
FOR EACH ROW
BEGIN
    DECLARE allowed_file_count int DEFAULT 0;
    SELECT COUNT(*) INTO allowed_file_count
      FROM print_miniapp_demo_file
     WHERE id = NEW.file_id
       AND tenant_id = NEW.tenant_id
       AND store_id = NEW.store_id
       AND principal_id = NEW.principal_id
       AND source_kind = 'DOCUMENT'
       AND source_variant IS NULL;
    IF allowed_file_count <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Quote file type or owner is not allowed';
    END IF;
END//
DELIMITER ;
