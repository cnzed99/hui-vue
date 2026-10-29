-- P35 DRAFT ONLY. Not executed; no role grants or runtime activation included.
-- Reuses print:store:query/update. Real SMTP transport and TLS/SSL configuration are not implemented.
CREATE TABLE print_email_settings (
  tenant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  version BIGINT NOT NULL,
  username VARCHAR(320) NOT NULL,
  smtp_host VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  smtp_port INT NOT NULL,
  password_ciphertext VARCHAR(2048) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  password_nonce CHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  password_key_version VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  creator VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updater VARCHAR(64) NOT NULL,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id,store_id),
  CONSTRAINT fk_print_email_settings_store FOREIGN KEY (tenant_id,store_id) REFERENCES print_store (tenant_id,id),
  CONSTRAINT ck_print_email_settings_version CHECK (version > 0),
  CONSTRAINT ck_print_email_settings_username CHECK (CHAR_LENGTH(username) BETWEEN 1 AND 320),
  CONSTRAINT ck_print_email_settings_host CHECK (CHAR_LENGTH(smtp_host) BETWEEN 1 AND 255),
  CONSTRAINT ck_print_email_settings_port CHECK (smtp_port BETWEEN 1 AND 65535),
  CONSTRAINT ck_print_email_settings_cipher CHECK (CHAR_LENGTH(password_ciphertext) BETWEEN 24 AND 2048),
  CONSTRAINT ck_print_email_settings_nonce CHECK (CHAR_LENGTH(password_nonce)=16),
  CONSTRAINT ck_print_email_settings_key_version CHECK (password_key_version REGEXP '^[A-Za-z0-9._-]{1,32}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- Password plaintext is never stored or returned. Ciphertext uses AES-256-GCM with a deployment key reference.
-- Missing configuration is configured=false. Writes require print.email-settings-write-enabled=true and a valid 32-byte key.
-- Sending status remains SIMULATED_NOT_SENT; no SMTP connection or test message is performed.
