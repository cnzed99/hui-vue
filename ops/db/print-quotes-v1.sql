-- CORE-02 single-file quote foundation. No business prices and no runtime enabling.
-- Immutable update triggers are installed and verified by migrate-print-quotes.py.
CREATE TABLE IF NOT EXISTS print_price_profile (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tenant_id BIGINT NOT NULL,
 store_id BIGINT NOT NULL,
 profile_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 version VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 mode VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 currency CHAR(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 simplex_price_per_sheet VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 duplex_price_per_sheet VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tier_json JSON NULL,
 created_at_ms BIGINT NOT NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_print_price_version(tenant_id,store_id,profile_key,version),
 UNIQUE KEY uk_print_price_scope(tenant_id,store_id,id),
 CONSTRAINT fk_print_price_store FOREIGN KEY(tenant_id,store_id) REFERENCES print_store(tenant_id,id),
 CONSTRAINT chk_print_price_scope CHECK(tenant_id>0 AND store_id>0),
 CONSTRAINT chk_print_price_created CHECK(created_at_ms>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS print_quote (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 tenant_id BIGINT NOT NULL,
 store_id BIGINT NOT NULL,
 owner_user_id BIGINT NOT NULL,
 file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 price_profile_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 idempotency_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 input_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 snapshot_json JSON NOT NULL,
 created_at_ms BIGINT NOT NULL,
 expires_at_ms BIGINT NOT NULL,
 PRIMARY KEY(id),
 UNIQUE KEY uk_print_quote_idempotency(tenant_id,store_id,owner_user_id,idempotency_sha256),
 KEY idx_print_quote_owner(tenant_id,store_id,owner_user_id,created_at_ms),
 CONSTRAINT fk_print_quote_profile FOREIGN KEY(tenant_id,store_id,price_profile_id)
   REFERENCES print_price_profile(tenant_id,store_id,id),
 CONSTRAINT chk_print_quote_scope CHECK(tenant_id>0 AND store_id>0 AND owner_user_id>0),
 CONSTRAINT chk_print_quote_expiry CHECK(created_at_ms>0 AND expires_at_ms>created_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
