CREATE TABLE print_store(tenant_id BIGINT NOT NULL,id BIGINT NOT NULL,status INT NOT NULL,deleted BIT NOT NULL,PRIMARY KEY(tenant_id,id));
CREATE TABLE system_role(id BIGINT PRIMARY KEY,tenant_id BIGINT NOT NULL,code VARCHAR(64),deleted BIT NOT NULL);
CREATE TABLE system_menu(id BIGINT PRIMARY KEY,name VARCHAR(64),permission VARCHAR(100),type INT,sort INT,parent_id BIGINT,status INT,visible BIT,keep_alive BIT,always_show BIT);
CREATE TABLE system_role_menu(id BIGINT AUTO_INCREMENT PRIMARY KEY,role_id BIGINT,menu_id BIGINT,tenant_id BIGINT,deleted BIT NOT NULL DEFAULT b'0');
CREATE TABLE print_price_profile(
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,tenant_id BIGINT NOT NULL,store_id BIGINT NOT NULL,
 profile_key VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 version VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 simplex_price_per_sheet VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 duplex_price_per_sheet VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,tier_json JSON,created_at_ms BIGINT NOT NULL,
 UNIQUE KEY uk_scope(tenant_id,store_id,id),FOREIGN KEY(tenant_id,store_id) REFERENCES print_store(tenant_id,id));
CREATE TABLE print_miniapp_demo_principal(id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,tenant_id BIGINT NOT NULL,store_id BIGINT NOT NULL,PRIMARY KEY(tenant_id,store_id,id));
CREATE TABLE print_miniapp_demo_file(
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,tenant_id BIGINT NOT NULL,store_id BIGINT NOT NULL,
 principal_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_kind VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_variant VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin,page_count INT,
 UNIQUE KEY uk_file_owner(tenant_id,store_id,principal_id,id),
 FOREIGN KEY(tenant_id,store_id,principal_id) REFERENCES print_miniapp_demo_principal(tenant_id,store_id,id),
 CONSTRAINT chk_print_demo_file_source CHECK((source_kind='DOCUMENT' AND source_variant IS NULL) OR (source_kind='ID_PHOTO' AND source_variant IN('ONE_INCH','TWO_INCH','THREE_INCH'))),
 CONSTRAINT chk_print_miniapp_file_pages CHECK(page_count IS NULL OR page_count BETWEEN 1 AND 20));
CREATE TABLE print_miniapp_demo_quote(
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,tenant_id BIGINT NOT NULL,store_id BIGINT NOT NULL,
 principal_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 price_profile_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 FOREIGN KEY(tenant_id,store_id,principal_id,file_id) REFERENCES print_miniapp_demo_file(tenant_id,store_id,principal_id,id),
 FOREIGN KEY(tenant_id,store_id,price_profile_id) REFERENCES print_price_profile(tenant_id,store_id,id));
INSERT INTO print_store VALUES(1,1,0,b'0');
INSERT INTO system_role VALUES(1,1,'tenant_admin',b'0'),(2,1,'print_reader',b'0');
INSERT INTO print_price_profile VALUES('8cf69353-bc69-5039-ad47-b59e3c791c76',1,1,'MINIAPP_DEMO_A4','1','DEMO','CNY','0.10','0.20',NULL,1000);
DELIMITER //
CREATE TRIGGER trg_print_price_profile_immutable BEFORE UPDATE ON print_price_profile FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable profile';END//
CREATE TRIGGER trg_print_demo_quote_document_only BEFORE INSERT ON print_miniapp_demo_quote FOR EACH ROW BEGIN IF(SELECT source_kind FROM print_miniapp_demo_file WHERE id=NEW.file_id)<>'DOCUMENT' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Document only';END IF;END//
DELIMITER ;
