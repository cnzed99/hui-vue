-- M4 development migration: duplicate-safe simulated print tasks and immutable receipts.
-- Requires the reviewed M3b isolated MINIAPP_DEMO schema. No real printer or payment integration.

ALTER TABLE print_miniapp_demo_order_item
  ADD UNIQUE KEY uk_pmdoi_task_binding
    (tenant_id, store_id, principal_id, id, order_id, file_id, file_content_sha256);

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
  );

ALTER TABLE print_miniapp_demo_order_event
  MODIFY event_type varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  DROP CHECK chk_pmdoe_fixed,
  DROP CHECK chk_pmdoe_type,
  ADD CONSTRAINT chk_pmdoe_fixed CHECK (
    simulation=1 AND (
      (actor_type='MINIAPP_DEMO' AND actor_id REGEXP '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
      OR (actor_type='STAFF_SIMULATOR' AND actor_id REGEXP '^[1-9][0-9]{0,18}$')
    )
  ),
  ADD CONSTRAINT chk_pmdoe_type CHECK (
    (event_type='ORDER_CREATED' AND aggregate_version=1 AND actor_type='MINIAPP_DEMO')
    OR (event_type='PAYMENT_SIMULATED' AND aggregate_version=2 AND actor_type='MINIAPP_DEMO')
    OR (event_type IN ('PRINT_TASK_CREATED','PRINT_TASK_CLAIMED','PRINT_TASK_REQUEUED',
      'PRINT_TASK_ACCEPTED','PRINT_TASK_EXECUTING','PRINT_TASK_SUCCEEDED',
      'PRINT_TASK_FAILED','PRINT_TASK_RESULT_UNKNOWN') AND aggregate_version>=3
      AND actor_type='STAFF_SIMULATOR')
  );

CREATE TABLE print_miniapp_demo_print_task (
  id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  job_id char(34) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_item_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_id bigint NOT NULL,
  store_id bigint NOT NULL,
  principal_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  file_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  file_content_sha256 char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  planned_copies varchar(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  planned_sheets varchar(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  state varchar(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version bigint NOT NULL,
  fence bigint NOT NULL,
  lease_id char(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  lease_until_ms bigint NULL,
  event_seq bigint NOT NULL,
  create_idempotency_sha256 char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  create_input_sha256 char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at_ms bigint NOT NULL,
  updated_at_ms bigint NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pmdpt_job (job_id),
  UNIQUE KEY uk_pmdpt_owner_id (tenant_id, store_id, principal_id, id),
  UNIQUE KEY uk_pmdpt_owner_id_order (tenant_id, store_id, principal_id, id, order_id),
  UNIQUE KEY uk_pmdpt_order (tenant_id, store_id, principal_id, order_id),
  UNIQUE KEY uk_pmdpt_item (tenant_id, store_id, principal_id, order_item_id),
  UNIQUE KEY uk_pmdpt_create_idem (tenant_id, store_id, principal_id, create_idempotency_sha256),
  UNIQUE KEY uk_pmdpt_receipt_binding
    (tenant_id, store_id, principal_id, id, order_id, order_item_id, file_content_sha256),
  CONSTRAINT fk_pmdpt_order FOREIGN KEY (tenant_id, store_id, principal_id, order_id)
    REFERENCES print_miniapp_demo_order (tenant_id, store_id, principal_id, id),
  CONSTRAINT fk_pmdpt_item FOREIGN KEY
    (tenant_id, store_id, principal_id, order_item_id, order_id, file_id, file_content_sha256)
    REFERENCES print_miniapp_demo_order_item
    (tenant_id, store_id, principal_id, id, order_id, file_id, file_content_sha256),
  CONSTRAINT chk_pmdpt_job CHECK (job_id REGEXP '^PJ[0-9A-F]{32}$'),
  CONSTRAINT chk_pmdpt_hashes CHECK (file_content_sha256 REGEXP '^[0-9a-f]{64}$'
    AND create_idempotency_sha256 REGEXP '^[0-9a-f]{64}$'
    AND create_input_sha256 REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT chk_pmdpt_counts CHECK (planned_copies REGEXP '^[1-9][0-9]{0,18}$'
    AND planned_sheets REGEXP '^[1-9][0-9]{0,18}$'),
  CONSTRAINT chk_pmdpt_state CHECK (state IN
    ('QUEUED','CLAIMED','ACCEPTED','EXECUTING','SUCCEEDED','FAILED','RESULT_UNKNOWN')),
  CONSTRAINT chk_pmdpt_version CHECK (version>=1 AND event_seq=version AND fence>=0),
  CONSTRAINT chk_pmdpt_lease CHECK (
    (state='QUEUED' AND lease_id IS NULL AND lease_until_ms IS NULL)
    OR (state IN ('CLAIMED','ACCEPTED','EXECUTING') AND lease_id IS NOT NULL
      AND lease_until_ms IS NOT NULL AND lease_until_ms>0 AND fence>=1)
    OR state IN ('SUCCEEDED','FAILED','RESULT_UNKNOWN')
  ),
  CONSTRAINT chk_pmdpt_time CHECK (created_at_ms>0 AND updated_at_ms>=created_at_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE print_miniapp_demo_print_task_action (
  id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  task_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_id bigint NOT NULL,
  store_id bigint NOT NULL,
  principal_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  idempotency_sha256 char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  input_sha256 char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action varchar(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  before_state varchar(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  after_state varchar(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  task_version_after bigint NOT NULL,
  order_version_after bigint NOT NULL,
  event_seq bigint NOT NULL,
  actor_type varchar(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_id bigint NOT NULL,
  simulation tinyint(1) NOT NULL,
  result_json json NOT NULL,
  created_at_ms bigint NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pmdpta_idem (tenant_id, store_id, principal_id, task_id, idempotency_sha256),
  UNIQUE KEY uk_pmdpta_event (tenant_id, store_id, principal_id, task_id, event_seq),
  CONSTRAINT fk_pmdpta_task FOREIGN KEY (tenant_id, store_id, principal_id, task_id, order_id)
    REFERENCES print_miniapp_demo_print_task (tenant_id, store_id, principal_id, id, order_id),
  CONSTRAINT chk_pmdpta_hashes CHECK (idempotency_sha256 REGEXP '^[0-9a-f]{64}$'
    AND input_sha256 REGEXP '^[0-9a-f]{64}$'),
  CONSTRAINT chk_pmdpta_transition CHECK (
    (action='CREATE_TASK' AND before_state='NOT_CREATED' AND after_state='QUEUED')
    OR (action='CLAIM' AND before_state='QUEUED' AND after_state='CLAIMED')
    OR (action='REQUEUE_EXPIRED_CLAIM' AND before_state='CLAIMED' AND after_state='QUEUED')
    OR (action='ACCEPT' AND before_state='CLAIMED' AND after_state='ACCEPTED')
    OR (action='START' AND before_state='ACCEPTED' AND after_state='EXECUTING')
    OR (action='SUCCEED' AND before_state='EXECUTING' AND after_state='SUCCEEDED')
    OR (action='FAIL' AND before_state IN ('ACCEPTED','EXECUTING') AND after_state='FAILED')
    OR (action IN ('LOSE_RECEIPT','MARK_TIMEOUT_UNKNOWN')
      AND before_state IN ('ACCEPTED','EXECUTING') AND after_state='RESULT_UNKNOWN')
    OR (action='ABORT_FILE_UNAVAILABLE' AND before_state IN ('QUEUED','CLAIMED') AND after_state='FAILED')
  ),
  CONSTRAINT chk_pmdpta_versions CHECK (task_version_after>=1 AND order_version_after=task_version_after+2
    AND event_seq=task_version_after),
  CONSTRAINT chk_pmdpta_actor CHECK (actor_type='STAFF_SIMULATOR' AND actor_id>0 AND simulation=1),
  CONSTRAINT chk_pmdpta_time CHECK (created_at_ms>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE print_miniapp_demo_print_receipt (
  id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  task_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  order_item_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  tenant_id bigint NOT NULL,
  store_id bigint NOT NULL,
  principal_id char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  receipt_seq bigint NOT NULL,
  result varchar(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reason varchar(48) CHARACTER SET ascii COLLATE ascii_bin NULL,
  planned_copies varchar(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  planned_sheets varchar(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  completed_copies varchar(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  completed_sheets varchar(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  file_content_sha256 char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_type varchar(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_id bigint NOT NULL,
  simulation tinyint(1) NOT NULL,
  created_at_ms bigint NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pmdptr_seq (tenant_id, store_id, principal_id, task_id, receipt_seq),
  CONSTRAINT fk_pmdptr_task FOREIGN KEY
    (tenant_id, store_id, principal_id, task_id, order_id, order_item_id, file_content_sha256)
    REFERENCES print_miniapp_demo_print_task
    (tenant_id, store_id, principal_id, id, order_id, order_item_id, file_content_sha256),
  CONSTRAINT chk_pmdptr_seq CHECK (receipt_seq=1),
  CONSTRAINT chk_pmdptr_result CHECK (
    (result='SIMULATED_SUCCESS' AND reason IS NULL
      AND completed_copies=planned_copies AND completed_sheets=planned_sheets)
    OR (result='SIMULATED_FAILED' AND reason IN
      ('SIMULATED_DEVICE_FAILURE','FILE_UNAVAILABLE_BEFORE_ACCEPT')
      AND reason IS NOT NULL
      AND completed_copies='0' AND completed_sheets='0')
  ),
  CONSTRAINT chk_pmdptr_counts CHECK (planned_copies REGEXP '^[1-9][0-9]{0,18}$'
    AND planned_sheets REGEXP '^[1-9][0-9]{0,18}$'
    AND completed_copies REGEXP '^(0|[1-9][0-9]{0,18})$'
    AND completed_sheets REGEXP '^(0|[1-9][0-9]{0,18})$'),
  CONSTRAINT chk_pmdptr_fixed CHECK (file_content_sha256 REGEXP '^[0-9a-f]{64}$'
    AND actor_type='STAFF_SIMULATOR' AND actor_id>0 AND simulation=1),
  CONSTRAINT chk_pmdptr_time CHECK (created_at_ms>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

DELIMITER //
CREATE TRIGGER trg_pmdpta_no_update BEFORE UPDATE ON print_miniapp_demo_print_task_action
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='print task actions are immutable'//
CREATE TRIGGER trg_pmdpta_no_delete BEFORE DELETE ON print_miniapp_demo_print_task_action
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='print task actions are immutable'//
CREATE TRIGGER trg_pmdptr_no_update BEFORE UPDATE ON print_miniapp_demo_print_receipt
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='print receipts are immutable'//
CREATE TRIGGER trg_pmdptr_no_delete BEFORE DELETE ON print_miniapp_demo_print_receipt
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='print receipts are immutable'//
DELIMITER ;
