-- Current locking reads remain file -> binding -> profile; no consistent read.
-- First-install candidate corrected after Stage C; executed historical SHA remains 28889a04225006e6a5186f1e63a1e96996b3e521577b1d01ab355d69448df8dd.
SET NAMES utf8mb4 COLLATE utf8mb4_bin;
-- DRAFT. Requires final Astra High review and a stopped/drained write window.
-- Explicit single-store seed. A different scope needs a newly reviewed seed, never reuse a profile UUID.
DELIMITER //
CREATE PROCEDURE photo_6in_preflight()
BEGIN
 IF DATABASE() IS NULL OR
    (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('print_photo_price_binding','print_photo_upload_session','print_photo_upload_item','print_photo_price_event')) <> 0 OR
    (SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='print_miniapp_demo_file' AND constraint_name IN ('chk_print_demo_file_source','chk_print_miniapp_file_pages')) <> 2 OR
    (SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name IN ('trg_print_demo_quote_document_only','trg_print_price_profile_immutable')) <> 2 OR
    (SELECT COUNT(*) FROM system_menu WHERE id IN (2010,2011) OR permission IN ('print:photo-price:query','print:photo-price:update')) <> 0 OR
    (SELECT COUNT(*) FROM system_role WHERE tenant_id=1 AND code='tenant_admin' AND deleted=0) <> 1 OR
    (SELECT COUNT(*) FROM system_role WHERE tenant_id=1 AND code='print_reader' AND deleted=0) <> 1 OR
    (SELECT COUNT(*) FROM print_store WHERE tenant_id=1 AND id=1 AND status=0 AND deleted=0) <> 1 OR
    (SELECT COUNT(*) FROM print_price_profile WHERE profile_key='MINIAPP_DEMO_PHOTO_6IN') <> 0
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Photo migration preflight mismatch'; END IF;
END//
CALL photo_6in_preflight()//
DROP PROCEDURE photo_6in_preflight//
DELIMITER ;
CREATE TABLE print_photo_price_binding (
 tenant_id BIGINT NOT NULL, store_id BIGINT NOT NULL,
 active_profile_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 revision BIGINT NOT NULL, quote_enabled TINYINT NOT NULL DEFAULT 0,
 updated_by BIGINT NOT NULL, updated_at_ms BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,store_id),
 CONSTRAINT fk_photo_binding_profile FOREIGN KEY(tenant_id,store_id,active_profile_id) REFERENCES print_price_profile(tenant_id,store_id,id),
 CONSTRAINT chk_photo_binding CHECK(revision>0 AND quote_enabled IN(0,1) AND updated_by>0 AND updated_at_ms>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
SHOW CREATE TABLE print_photo_price_binding;
CREATE TABLE print_photo_price_event (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 tenant_id BIGINT NOT NULL,store_id BIGINT NOT NULL,actor_user_id BIGINT NOT NULL,
 old_profile_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 new_profile_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 old_revision BIGINT NOT NULL,new_revision BIGINT NOT NULL,created_at_ms BIGINT NOT NULL,
 CONSTRAINT fk_photo_event_old FOREIGN KEY(tenant_id,store_id,old_profile_id) REFERENCES print_price_profile(tenant_id,store_id,id),
 CONSTRAINT fk_photo_event_new FOREIGN KEY(tenant_id,store_id,new_profile_id) REFERENCES print_price_profile(tenant_id,store_id,id),
 CONSTRAINT chk_photo_event CHECK(old_revision>0 AND new_revision=old_revision+1 AND actor_user_id>0 AND created_at_ms>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
SHOW CREATE TABLE print_photo_price_event;
CREATE TABLE print_photo_upload_session (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tenant_id BIGINT NOT NULL,store_id BIGINT NOT NULL,principal_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 idempotency_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 variant VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 expected_count INT NOT NULL,render_version VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 final_file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 expires_at_ms BIGINT NOT NULL,created_at_ms BIGINT NOT NULL,updated_at_ms BIGINT NOT NULL,
 PRIMARY KEY(id),UNIQUE KEY uk_photo_upload_key(tenant_id,store_id,principal_id,idempotency_sha256),
 UNIQUE KEY uk_photo_upload_owner(tenant_id,store_id,principal_id,id),
 KEY idx_photo_upload_expiry(expires_at_ms),
 CONSTRAINT fk_photo_upload_owner FOREIGN KEY(tenant_id,store_id,principal_id) REFERENCES print_miniapp_demo_principal(tenant_id,store_id,id),
 CONSTRAINT fk_photo_upload_final FOREIGN KEY(tenant_id,store_id,principal_id,final_file_id) REFERENCES print_miniapp_demo_file(tenant_id,store_id,principal_id,id),
 CONSTRAINT chk_photo_upload CHECK(variant IN('PHOTO_3IN','PHOTO_4IN','PHOTO_5IN','PHOTO_6IN','PHOTO_6IN_COLLAGE') AND expected_count BETWEEN 1 AND IF(variant='PHOTO_6IN_COLLAGE',9,30)
  AND render_version='photo-6in-v1' AND state IN('OPEN','FINALIZING','COMPLETE')
  AND ((state='OPEN' AND manifest_sha256 IS NULL AND final_file_id IS NULL) OR (state='FINALIZING' AND manifest_sha256 IS NOT NULL AND final_file_id IS NULL) OR (state='COMPLETE' AND manifest_sha256 IS NOT NULL AND final_file_id IS NOT NULL))
  AND created_at_ms>0 AND expires_at_ms>created_at_ms AND updated_at_ms>=created_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
SHOW CREATE TABLE print_photo_upload_session;
CREATE TABLE print_photo_upload_item (
 session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tenant_id BIGINT NOT NULL,store_id BIGINT NOT NULL,principal_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 ordinal INT NOT NULL,content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,size_bytes BIGINT NOT NULL,
 storage_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(session_id,ordinal),UNIQUE KEY uk_photo_item_storage(storage_key),
 CONSTRAINT fk_photo_item_session FOREIGN KEY(tenant_id,store_id,principal_id,session_id) REFERENCES print_photo_upload_session(tenant_id,store_id,principal_id,id),
 CONSTRAINT chk_photo_item CHECK(ordinal BETWEEN 0 AND 29 AND size_bytes BETWEEN 1 AND 2097152)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
SHOW CREATE TABLE print_photo_upload_item;
START TRANSACTION;
SET @photo_profile_id=UUID();
SET @photo_now=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED);
INSERT INTO print_price_profile(id,tenant_id,store_id,profile_key,version,mode,currency,simplex_price_per_sheet,duplex_price_per_sheet,tier_json,created_at_ms)
 VALUES(@photo_profile_id,1,1,'MINIAPP_DEMO_PHOTO_6IN','1','DEMO','CNY','1.00','1.00',NULL,@photo_now);
INSERT INTO print_photo_price_binding VALUES(1,1,@photo_profile_id,1,0,1,@photo_now);
INSERT INTO system_menu(id,name,permission,type,sort,parent_id,status,visible,keep_alive,always_show)
 VALUES(2010,'六寸照片纸价格查询','print:photo-price:query',3,10,2000,0,b'1',b'0',b'0'),
       (2011,'六寸照片纸价格修改','print:photo-price:update',3,11,2000,0,b'1',b'0',b'0');
INSERT INTO system_role_menu(role_id,menu_id,tenant_id)
 SELECT id,2010,tenant_id FROM system_role WHERE tenant_id=1 AND code IN('tenant_admin','print_reader') AND deleted=0;
INSERT INTO system_role_menu(role_id,menu_id,tenant_id)
 SELECT id,2011,tenant_id FROM system_role WHERE tenant_id=1 AND code='tenant_admin' AND deleted=0;
COMMIT;
SELECT b.tenant_id,b.store_id,b.active_profile_id,b.revision,b.quote_enabled,p.version,p.simplex_price_per_sheet FROM print_photo_price_binding b JOIN print_price_profile p ON p.id=b.active_profile_id AND p.tenant_id=b.tenant_id AND p.store_id=b.store_id;
SELECT r.code,m.permission,rm.tenant_id FROM system_role_menu rm JOIN system_role r ON r.id=rm.role_id JOIN system_menu m ON m.id=rm.menu_id WHERE m.id IN(2010,2011) AND rm.deleted=0;
ALTER TABLE print_miniapp_demo_file
 DROP CHECK chk_print_demo_file_source, DROP CHECK chk_print_miniapp_file_pages,
 ADD CONSTRAINT chk_print_demo_file_source CHECK(source_kind IS NOT NULL AND (
  (source_kind='DOCUMENT' AND source_variant IS NULL) OR
  (source_kind='ID_PHOTO' AND source_variant IS NOT NULL AND source_variant IN('ONE_INCH','TWO_INCH','THREE_INCH')) OR
  (source_kind='PHOTO' AND source_variant IS NOT NULL AND source_variant IN('PHOTO_3IN','PHOTO_4IN','PHOTO_5IN','PHOTO_6IN','PHOTO_6IN_COLLAGE')))),
 ADD CONSTRAINT chk_print_miniapp_file_pages CHECK(page_count IS NULL OR (page_count>=1 AND (
  (source_kind='DOCUMENT' AND page_count<=20) OR
  (source_kind='ID_PHOTO' AND page_count=1) OR
  (source_kind='PHOTO' AND ((source_variant='PHOTO_6IN_COLLAGE' AND page_count=1) OR (source_variant IN('PHOTO_3IN','PHOTO_4IN','PHOTO_5IN','PHOTO_6IN') AND page_count<=30))))));
SHOW CREATE TABLE print_miniapp_demo_file;
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
