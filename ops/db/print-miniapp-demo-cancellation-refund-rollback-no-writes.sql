-- M8 compatibility rollback. DESTRUCTIVE: valid only before any M8 record or M8 state exists.
-- Requires final Astra High approval and an already verified database backup.
-- Invoke only through rollback-m8-no-writes-controlled.sh after draining all app processes/connections.

DELIMITER //
CREATE PROCEDURE assert_no_m8_writes()
BEGIN
  IF COALESCE(@m8_backend_drained,0) <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='M8 backend/traffic not drained: controlled rollback required';
  END IF;
  IF (SELECT COUNT(*) FROM print_miniapp_demo_cancellation) <> 0
    OR (SELECT COUNT(*) FROM print_miniapp_demo_refund_receipt) <> 0
    OR (SELECT COUNT(*) FROM print_miniapp_demo_refund_journal) <> 0
    OR (SELECT COUNT(*) FROM print_miniapp_demo_order
        WHERE order_state='CANCELLED' OR refund_state='SUCCEEDED' OR print_state='CANCELLED') <> 0
    OR (SELECT COUNT(*) FROM print_miniapp_demo_order_event
        WHERE event_type IN ('ORDER_CANCELLED_UNPAID','ORDER_CANCELLED_REFUNDED')) <> 0
    OR (SELECT COUNT(*) FROM print_miniapp_demo_print_task WHERE state='CANCELLED') <> 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT='M8 writes exist: rollback forbidden; disable POST and forward-fix';
  END IF;
END//
DELIMITER ;

CALL assert_no_m8_writes();
DROP PROCEDURE assert_no_m8_writes;

DROP TABLE print_miniapp_demo_refund_journal;
DROP TABLE print_miniapp_demo_refund_receipt;
DROP TABLE print_miniapp_demo_cancellation;

ALTER TABLE print_miniapp_demo_print_task
  DROP CHECK chk_pmdpt_cancel,
  DROP CHECK chk_pmdpt_state,
  DROP CHECK chk_pmdpt_lease,
  ADD CONSTRAINT chk_pmdpt_state CHECK (state IN
    ('QUEUED','CLAIMED','ACCEPTED','EXECUTING','SUCCEEDED','FAILED','RESULT_UNKNOWN')),
  ADD CONSTRAINT chk_pmdpt_lease CHECK (
    (state='QUEUED' AND lease_id IS NULL AND lease_until_ms IS NULL)
    OR (state IN ('CLAIMED','ACCEPTED','EXECUTING') AND lease_id IS NOT NULL
      AND lease_until_ms IS NOT NULL AND lease_until_ms>0 AND fence>=1)
    OR state IN ('SUCCEEDED','FAILED','RESULT_UNKNOWN')
  );

ALTER TABLE print_miniapp_demo_order_event
  DROP CHECK chk_pmdoe_type,
  ADD CONSTRAINT chk_pmdoe_type CHECK (
    (event_type='ORDER_CREATED' AND aggregate_version=1 AND actor_type='MINIAPP_DEMO')
    OR (event_type='PAYMENT_SIMULATED' AND aggregate_version=2 AND actor_type='MINIAPP_DEMO')
    OR (event_type IN ('PRINT_TASK_CREATED','PRINT_TASK_CLAIMED','PRINT_TASK_REQUEUED',
      'PRINT_TASK_ACCEPTED','PRINT_TASK_EXECUTING','PRINT_TASK_SUCCEEDED',
      'PRINT_TASK_FAILED','PRINT_TASK_RESULT_UNKNOWN') AND aggregate_version>=3
      AND actor_type='STAFF_SIMULATOR')
  );

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
