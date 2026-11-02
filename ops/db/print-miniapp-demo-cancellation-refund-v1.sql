-- M8 development migration: owner-scoped cancellation, simulated refund and manual-review evidence.
-- Apply only after preflight and final Astra High review. Keep cancellation write API disabled while applying.

ALTER TABLE print_miniapp_demo_order
  DROP CHECK chk_pmdo_state,
  ADD CONSTRAINT chk_pmdo_state CHECK (
    (order_state='OPEN' AND payment_state='UNPAID' AND refund_state='NONE'
      AND print_state='NOT_CREATED' AND version=1)
    OR (order_state='OPEN' AND payment_state='SUCCEEDED' AND refund_state='NONE'
      AND print_state='NOT_CREATED' AND version=2)
    OR (order_state='OPEN' AND payment_state='SUCCEEDED' AND refund_state='NONE'
      AND print_state IN ('QUEUED','CLAIMED','ACCEPTED','EXECUTING','SUCCEEDED','FAILED','RESULT_UNKNOWN')
      AND version>=3)
    OR (order_state='CANCELLED' AND payment_state='UNPAID' AND refund_state='NONE'
      AND print_state='NOT_CREATED' AND version=2)
    OR (order_state='CANCELLED' AND payment_state='SUCCEEDED' AND refund_state='SUCCEEDED'
      AND print_state='NOT_CREATED' AND version=3)
    OR (order_state='CANCELLED' AND payment_state='SUCCEEDED' AND refund_state='SUCCEEDED'
      AND print_state='CANCELLED' AND version=4)
  );

ALTER TABLE print_miniapp_demo_order_event
  DROP CHECK chk_pmdoe_type,
  ADD CONSTRAINT chk_pmdoe_type CHECK (
    (event_type='ORDER_CREATED' AND aggregate_version=1 AND actor_type='MINIAPP_DEMO')
    OR (event_type='PAYMENT_SIMULATED' AND aggregate_version=2 AND actor_type='MINIAPP_DEMO')
    OR (event_type='ORDER_CANCELLED_UNPAID' AND aggregate_version=2 AND actor_type='MINIAPP_DEMO')
    OR (event_type='ORDER_CANCELLED_REFUNDED' AND aggregate_version IN (3,4)
      AND actor_type='MINIAPP_DEMO')
    OR (event_type IN ('PRINT_TASK_CREATED','PRINT_TASK_CLAIMED','PRINT_TASK_REQUEUED',
      'PRINT_TASK_ACCEPTED','PRINT_TASK_EXECUTING','PRINT_TASK_SUCCEEDED',
      'PRINT_TASK_FAILED','PRINT_TASK_RESULT_UNKNOWN') AND aggregate_version>=3
      AND actor_type='STAFF_SIMULATOR')
  );

ALTER TABLE print_miniapp_demo_print_task
  DROP CHECK chk_pmdpt_state,
  DROP CHECK chk_pmdpt_lease,
  ADD CONSTRAINT chk_pmdpt_state CHECK (state IN
    ('QUEUED','CLAIMED','ACCEPTED','EXECUTING','SUCCEEDED','FAILED','RESULT_UNKNOWN','CANCELLED')),
  ADD CONSTRAINT chk_pmdpt_lease CHECK (
    (state IN ('QUEUED','CANCELLED') AND lease_id IS NULL AND lease_until_ms IS NULL)
    OR (state IN ('CLAIMED','ACCEPTED','EXECUTING') AND lease_id IS NOT NULL
      AND lease_until_ms IS NOT NULL AND lease_until_ms>0 AND fence>=1)
    OR state IN ('SUCCEEDED','FAILED','RESULT_UNKNOWN')
  ),
  ADD CONSTRAINT chk_pmdpt_cancel CHECK (
    state<>'CANCELLED'
    OR (version=2 AND event_seq=2 AND fence=0 AND lease_id IS NULL AND lease_until_ms IS NULL)
  );

CREATE TABLE print_miniapp_demo_cancellation (
  id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_id bigint NOT NULL,
  store_id bigint NOT NULL,
  principal_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  idempotency_sha256 char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  input_sha256 char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  outcome varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_version_before bigint NOT NULL,
  order_version_after bigint NOT NULL,
  task_id char(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  task_state_before varchar(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
  task_state_after varchar(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
  task_version_before bigint NULL,
  task_version_after bigint NULL,
  task_event_seq_before bigint NULL,
  task_event_seq_after bigint NULL,
  task_fence_before bigint NULL,
  task_fence_after bigint NULL,
  created_at_ms bigint NOT NULL,
  simulation tinyint(1) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pmdc_owner_id (tenant_id, store_id, principal_id, id),
  UNIQUE KEY uk_pmdc_owner_id_order (tenant_id, store_id, principal_id, id, order_id),
  UNIQUE KEY uk_pmdc_refund_binding (tenant_id, store_id, principal_id, id, order_id, outcome),
  UNIQUE KEY uk_pmdc_order (tenant_id, store_id, principal_id, order_id),
  UNIQUE KEY uk_pmdc_idem (tenant_id, store_id, principal_id, idempotency_sha256),
  UNIQUE KEY uk_pmdc_task_event
    (tenant_id, store_id, principal_id, task_id, task_event_seq_after),
  CONSTRAINT fk_pmdc_order FOREIGN KEY (tenant_id, store_id, principal_id, order_id)
    REFERENCES print_miniapp_demo_order (tenant_id, store_id, principal_id, id),
  CONSTRAINT fk_pmdc_task FOREIGN KEY (tenant_id, store_id, principal_id, task_id, order_id)
    REFERENCES print_miniapp_demo_print_task (tenant_id, store_id, principal_id, id, order_id),
  CONSTRAINT chk_pmdc_hashes CHECK (idempotency_sha256 REGEXP '^[0-9a-f]{64}$'
    AND input_sha256 REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT chk_pmdc_fixed CHECK (created_at_ms>0 AND simulation=1),
  CONSTRAINT chk_pmdc_outcome CHECK (
    (outcome='CANCELLED_UNPAID' AND reason='UNPAID'
      AND order_version_before=1 AND order_version_after=2
      AND task_id IS NULL AND task_state_before IS NULL AND task_state_after IS NULL
      AND task_version_before IS NULL AND task_version_after IS NULL
      AND task_event_seq_before IS NULL AND task_event_seq_after IS NULL
      AND task_fence_before IS NULL AND task_fence_after IS NULL)
    OR (outcome='CANCELLED_REFUNDED' AND reason='NEVER_CLAIMED'
      AND order_version_before=2 AND order_version_after=3
      AND task_id IS NULL AND task_state_before IS NULL AND task_state_after IS NULL
      AND task_version_before IS NULL AND task_version_after IS NULL
      AND task_event_seq_before IS NULL AND task_event_seq_after IS NULL
      AND task_fence_before IS NULL AND task_fence_after IS NULL)
    OR (outcome='CANCELLED_REFUNDED' AND reason='NEVER_CLAIMED'
      AND order_version_before=3 AND order_version_after=4
      AND task_id IS NOT NULL
      AND task_state_before IS NOT NULL AND task_state_after IS NOT NULL
      AND task_version_before IS NOT NULL AND task_version_after IS NOT NULL
      AND task_event_seq_before IS NOT NULL AND task_event_seq_after IS NOT NULL
      AND task_fence_before IS NOT NULL AND task_fence_after IS NOT NULL
      AND task_state_before='QUEUED' AND task_state_after='CANCELLED'
      AND task_version_before=1 AND task_version_after=2
      AND task_event_seq_before=1 AND task_event_seq_after=2
      AND task_fence_before=0 AND task_fence_after=0)
    OR (outcome='MANUAL_REVIEW_REQUIRED' AND reason='REQUIRES_MANUAL_REVIEW'
      AND order_version_before=order_version_after AND order_version_before>=3
      AND task_id IS NOT NULL
      AND task_state_before IS NOT NULL AND task_state_after IS NOT NULL
      AND task_version_before IS NOT NULL AND task_version_after IS NOT NULL
      AND task_event_seq_before IS NOT NULL AND task_event_seq_after IS NOT NULL
      AND task_fence_before IS NOT NULL AND task_fence_after IS NOT NULL
      AND task_state_before=task_state_after
      AND task_state_before IN
        ('QUEUED','CLAIMED','ACCEPTED','EXECUTING','SUCCEEDED','FAILED','RESULT_UNKNOWN')
      AND task_version_before=task_version_after AND task_version_before>=1
      AND task_event_seq_before=task_event_seq_after AND task_event_seq_before>=1
      AND task_event_seq_before=task_version_before
      AND order_version_before=task_version_before+2
      AND task_fence_before=task_fence_after AND task_fence_before>=0
      AND (task_state_before<>'QUEUED' OR task_fence_before>=1))
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE print_miniapp_demo_refund_receipt (
  id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  cancellation_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  cancellation_outcome varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payment_receipt_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_id bigint NOT NULL,
  store_id bigint NOT NULL,
  principal_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  mode varchar(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state varchar(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  simulation tinyint(1) NOT NULL,
  currency char(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at_ms bigint NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pmdrr_owner_id (tenant_id, store_id, principal_id, id),
  UNIQUE KEY uk_pmdrr_owner_id_order (tenant_id, store_id, principal_id, id, order_id),
  UNIQUE KEY uk_pmdrr_order (tenant_id, store_id, principal_id, order_id),
  UNIQUE KEY uk_pmdrr_cancel (tenant_id, store_id, principal_id, cancellation_id),
  UNIQUE KEY uk_pmdrr_payment (tenant_id, store_id, principal_id, payment_receipt_id),
  CONSTRAINT fk_pmdrr_cancel FOREIGN KEY
    (tenant_id, store_id, principal_id, cancellation_id, order_id, cancellation_outcome)
    REFERENCES print_miniapp_demo_cancellation
    (tenant_id, store_id, principal_id, id, order_id, outcome),
  CONSTRAINT fk_pmdrr_payment FOREIGN KEY
    (tenant_id, store_id, principal_id, payment_receipt_id, order_id)
    REFERENCES print_miniapp_demo_payment_receipt
    (tenant_id, store_id, principal_id, id, order_id),
  CONSTRAINT fk_pmdrr_order FOREIGN KEY (tenant_id, store_id, principal_id, order_id)
    REFERENCES print_miniapp_demo_order (tenant_id, store_id, principal_id, id),
  CONSTRAINT chk_pmdrr_fixed CHECK (provider='SIMULATED' AND mode='DEMO' AND state='SUCCEEDED'
    AND simulation=1 AND currency='CNY' AND cancellation_outcome='CANCELLED_REFUNDED'),
  CONSTRAINT chk_pmdrr_amount CHECK (amount REGEXP '^(0|[1-9][0-9]*)(\\.[0-9]+)?$'),
  CONSTRAINT chk_pmdrr_time CHECK (created_at_ms>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE print_miniapp_demo_refund_journal (
  id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  refund_receipt_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_id bigint NOT NULL,
  store_id bigint NOT NULL,
  principal_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  side varchar(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  account varchar(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  currency char(3) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  amount varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at_ms bigint NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pmdrj_side
    (tenant_id, store_id, principal_id, refund_receipt_id, side),
  KEY fk_pmdrj_order (tenant_id, store_id, principal_id, order_id),
  CONSTRAINT fk_pmdrj_receipt_order FOREIGN KEY
    (tenant_id, store_id, principal_id, refund_receipt_id, order_id)
    REFERENCES print_miniapp_demo_refund_receipt
    (tenant_id, store_id, principal_id, id, order_id),
  CONSTRAINT fk_pmdrj_order FOREIGN KEY (tenant_id, store_id, principal_id, order_id)
    REFERENCES print_miniapp_demo_order (tenant_id, store_id, principal_id, id),
  CONSTRAINT chk_pmdrj_entry CHECK ((side='DEBIT' AND account='DEMO_STORE')
    OR (side='CREDIT' AND account='DEMO_CUSTOMER')),
  CONSTRAINT chk_pmdrj_currency CHECK (currency='CNY'),
  CONSTRAINT chk_pmdrj_amount CHECK (amount REGEXP '^(0|[1-9][0-9]*)(\\.[0-9]+)?$'),
  CONSTRAINT chk_pmdrj_time CHECK (created_at_ms>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

DELIMITER //
CREATE TRIGGER trg_pmdc_no_update BEFORE UPDATE ON print_miniapp_demo_cancellation
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='cancellation records are immutable'//
CREATE TRIGGER trg_pmdc_no_delete BEFORE DELETE ON print_miniapp_demo_cancellation
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='cancellation records are immutable'//
CREATE TRIGGER trg_pmdrr_no_update BEFORE UPDATE ON print_miniapp_demo_refund_receipt
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='refund receipts are immutable'//
CREATE TRIGGER trg_pmdrr_no_delete BEFORE DELETE ON print_miniapp_demo_refund_receipt
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='refund receipts are immutable'//
CREATE TRIGGER trg_pmdrj_no_update BEFORE UPDATE ON print_miniapp_demo_refund_journal
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='refund journals are immutable'//
CREATE TRIGGER trg_pmdrj_no_delete BEFORE DELETE ON print_miniapp_demo_refund_journal
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='refund journals are immutable'//
DELIMITER ;
