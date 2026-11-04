-- Current locking reads remain file -> binding -> profile; no consistent read.
-- FORWARD REPAIR ONLY: an already applied Stage C must never rerun first-install migration.
-- No data/table/gate changes. Requires stopped/drained writers, verified backups and exact increment review.
SET NAMES utf8mb4 COLLATE utf8mb4_bin;
-- CRITICAL GAP: once dropped, no writer may restart until CREATE and readback succeed.
DROP TRIGGER trg_print_demo_quote_document_only;
DELIMITER //
CREATE TRIGGER trg_print_demo_quote_document_only BEFORE INSERT ON print_miniapp_demo_quote FOR EACH ROW
BEGIN
 DECLARE k VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin;
 DECLARE v VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin;
 DECLARE active_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin;
 DECLARE enabled_flag TINYINT;
 DECLARE profile_key_value VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
 DECLARE profile_version_value VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
 DECLARE revision_value BIGINT;
 DECLARE profile_mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin;
 DECLARE profile_currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin;
 DECLARE missing INT DEFAULT 0;
 DECLARE CONTINUE HANDLER FOR NOT FOUND SET missing=1;
 SELECT source_kind,source_variant INTO k,v FROM print_miniapp_demo_file
  WHERE id=NEW.file_id AND tenant_id=NEW.tenant_id AND store_id=NEW.store_id AND principal_id=NEW.principal_id FOR UPDATE;
 IF missing=1 OR k IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Quote file source or owner rejected'; END IF;
 IF k='DOCUMENT' AND v IS NULL THEN
  IF NEW.price_profile_id<>'8cf69353-bc69-5039-ad47-b59e3c791c76' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Document profile rejected'; END IF;
 ELSEIF (k='ID_PHOTO' AND v IS NOT NULL AND v IN('ONE_INCH','TWO_INCH','THREE_INCH')) OR
        (k='PHOTO' AND v IS NOT NULL AND v IN('PHOTO_3IN','PHOTO_4IN','PHOTO_5IN','PHOTO_6IN','PHOTO_6IN_COLLAGE')) THEN
  SELECT active_profile_id,revision,quote_enabled INTO active_id,revision_value,enabled_flag FROM print_photo_price_binding
   WHERE tenant_id=NEW.tenant_id AND store_id=NEW.store_id FOR UPDATE;
  IF missing=1 OR enabled_flag<>1 OR active_id<>NEW.price_profile_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Photo quotation gate or active profile rejected'; END IF;
 ELSE SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Quote source rejected'; END IF;
 SELECT profile_key,version,mode,currency INTO profile_key_value,profile_version_value,profile_mode,profile_currency FROM print_price_profile
  WHERE id=NEW.price_profile_id AND tenant_id=NEW.tenant_id AND store_id=NEW.store_id FOR UPDATE;
 IF missing=1 OR profile_mode<>'DEMO' OR profile_currency<>'CNY' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Quote profile scope rejected'; END IF;
 IF k<>'DOCUMENT' AND (profile_key_value<>'MINIAPP_DEMO_PHOTO_6IN' OR profile_version_value<>(CAST(revision_value AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_bin)) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Photo profile series rejected'; END IF;
END//
DELIMITER ;
SHOW CREATE TRIGGER trg_print_demo_quote_document_only;
