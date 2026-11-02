-- M8 read-only preflight. Every violation_count must be 0 before applying the migration.
-- This script does not create, update or delete application data.

SELECT 'unexpected_existing_m8_tables' AS check_name, COUNT(*) AS violation_count
FROM information_schema.tables
WHERE table_schema=DATABASE()
  AND table_name IN ('print_miniapp_demo_cancellation',
    'print_miniapp_demo_refund_receipt','print_miniapp_demo_refund_journal');

SELECT 'missing_required_tables' AS check_name, 7-COUNT(*) AS violation_count
FROM information_schema.tables
WHERE table_schema=DATABASE()
  AND table_name IN ('print_miniapp_demo_order','print_miniapp_demo_order_event',
    'print_miniapp_demo_order_item','print_miniapp_demo_payment_receipt',
    'print_miniapp_demo_payment_journal','print_miniapp_demo_print_task',
    'print_miniapp_demo_print_task_action');

SELECT 'unexpected_cancelled_order' AS check_name, COUNT(*) AS violation_count
FROM print_miniapp_demo_order
WHERE order_state='CANCELLED' OR refund_state='SUCCEEDED' OR print_state='CANCELLED';

SELECT 'unexpected_cancel_event' AS check_name, COUNT(*) AS violation_count
FROM print_miniapp_demo_order_event
WHERE event_type IN ('ORDER_CANCELLED_UNPAID','ORDER_CANCELLED_REFUNDED');

SELECT 'unexpected_cancelled_task' AS check_name, COUNT(*) AS violation_count
FROM print_miniapp_demo_print_task
WHERE state='CANCELLED';

SELECT 'invalid_first_queue_shape' AS check_name, COUNT(*) AS violation_count
FROM print_miniapp_demo_print_task
WHERE state='QUEUED' AND fence=0
  AND NOT (version=1 AND event_seq=1 AND lease_id IS NULL AND lease_until_ms IS NULL);

SELECT 'invalid_requeued_shape' AS check_name, COUNT(*) AS violation_count
FROM print_miniapp_demo_print_task
WHERE state='QUEUED' AND fence>0
  AND NOT (version>=3 AND event_seq=version AND lease_id IS NULL AND lease_until_ms IS NULL);

SELECT 'payment_receipt_order_mismatch' AS check_name, COUNT(*) AS violation_count
FROM print_miniapp_demo_payment_receipt r
JOIN print_miniapp_demo_order o
  ON o.tenant_id=r.tenant_id AND o.store_id=r.store_id
  AND o.principal_id=r.principal_id AND o.id=r.order_id
WHERE r.state<>'SUCCEEDED' OR r.simulation<>1 OR r.currency<>o.currency OR r.amount<>o.total_amount;

SELECT 'payment_journal_pair_mismatch' AS check_name, COUNT(*) AS violation_count
FROM (
  SELECT r.tenant_id,r.store_id,r.principal_id,r.id,r.order_id,r.currency,r.amount,
    SUM(j.side='DEBIT' AND j.account='DEMO_CUSTOMER'
      AND j.currency=r.currency AND j.amount=r.amount) AS debit_ok,
    SUM(j.side='CREDIT' AND j.account='DEMO_STORE'
      AND j.currency=r.currency AND j.amount=r.amount) AS credit_ok,
    COUNT(j.id) AS journal_count
  FROM print_miniapp_demo_payment_receipt r
  LEFT JOIN print_miniapp_demo_payment_journal j
    ON j.tenant_id=r.tenant_id AND j.store_id=r.store_id
    AND j.principal_id=r.principal_id AND j.receipt_id=r.id AND j.order_id=r.order_id
  GROUP BY r.tenant_id,r.store_id,r.principal_id,r.id,r.order_id,r.currency,r.amount
) x
WHERE COALESCE(x.debit_ok,0)<>1 OR COALESCE(x.credit_ok,0)<>1 OR x.journal_count<>2;

SELECT 'task_order_state_mismatch' AS check_name, COUNT(*) AS violation_count
FROM print_miniapp_demo_print_task t
JOIN print_miniapp_demo_order o
  ON o.tenant_id=t.tenant_id AND o.store_id=t.store_id
  AND o.principal_id=t.principal_id AND o.id=t.order_id
WHERE o.order_state<>'OPEN' OR o.payment_state<>'SUCCEEDED' OR o.refund_state<>'NONE'
  OR o.print_state<>t.state OR o.version<>t.version+2;

