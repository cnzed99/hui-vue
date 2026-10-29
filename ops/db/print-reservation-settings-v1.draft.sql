-- P06 DRAFT ONLY. Not executed; no role grants or runtime activation included.
-- Reuses print:store:query/update. P03 same-name automatic-print linkage remains unconfirmed.
CREATE TABLE print_reservation_settings (
  tenant_id BIGINT NOT NULL,
  store_id BIGINT NOT NULL,
  version BIGINT NOT NULL,
  pickup_enabled BOOLEAN NOT NULL,
  timed_delivery_enabled BOOLEAN NOT NULL,
  vip_delivery_enabled BOOLEAN NOT NULL,
  pickup_code_prefix VARCHAR(2) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  automatic_print_enabled BOOLEAN NOT NULL,
  creator VARCHAR(64) NOT NULL,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updater VARCHAR(64) NOT NULL,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id,store_id),
  CONSTRAINT fk_print_reservation_settings_store FOREIGN KEY (tenant_id,store_id) REFERENCES print_store (tenant_id,id),
  CONSTRAINT ck_print_reservation_settings_version CHECK (version > 0),
  CONSTRAINT ck_print_reservation_settings_pickup CHECK (pickup_enabled IN (0,1)),
  CONSTRAINT ck_print_reservation_settings_timed CHECK (timed_delivery_enabled IN (0,1)),
  CONSTRAINT ck_print_reservation_settings_vip CHECK (vip_delivery_enabled IN (0,1)),
  CONSTRAINT ck_print_reservation_settings_auto CHECK (automatic_print_enabled IN (0,1)),
  CONSTRAINT ck_print_reservation_settings_prefix CHECK (pickup_code_prefix REGEXP '^[A-Za-z0-9]{0,2}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- No row is seeded from observed source-site values.
-- Missing configuration is reported configured=false; all modes and automatic printing are off.
-- All writes remain disabled unless print.reservation-settings-write-enabled=true is explicitly activated.
-- automatic_print_enabled is configuration only: SIMULATED_CONFIG_ONLY, no dispatch consumer.
