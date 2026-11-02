package cn.iocoder.yudao.server.print.miniappdemo.cancellation;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.scope.PrintScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MiniappDemoCancellationRepository {
    record Order(String id, String principalId, String source, boolean simulation, String currency, String amount,
                 String orderState, String paymentState, String refundState, String printState, long version) {}
    record Task(String id, String state, long version, long eventSeq, long fence, String leaseId, Long leaseUntil) {}
    record Entry(String id, String orderId, String keyHash, String inputHash, String outcome, String reason,
                 long orderVersionBefore, long orderVersionAfter, String taskId, String taskStateBefore,
                 String taskStateAfter, Long taskVersionBefore, Long taskVersionAfter,
                 Long taskEventSeqBefore, Long taskEventSeqAfter, Long taskFenceBefore, Long taskFenceAfter,
                 long createdAtMs, boolean simulation) {}
    record Payment(String id, String orderId, String provider, String mode, String state, boolean simulation,
                   String currency, String amount) {}
    record Journal(String side, String account, String currency, String amount) {}
    record Refund(String id, String cancellationId, String paymentReceiptId, String orderId, String provider,
                  String mode, String state, boolean simulation, String currency, String amount, long createdAtMs) {}
    private static final String OWNER = "tenant_id=? AND store_id=? AND principal_id=?";
    private static final String ENTRY_FIELDS = "id,order_id,idempotency_sha256,input_sha256,outcome,reason,"
            + "order_version_before,order_version_after,task_id,task_state_before,task_state_after,"
            + "task_version_before,task_version_after,task_event_seq_before,task_event_seq_after,"
            + "task_fence_before,task_fence_after,created_at_ms,simulation";
    private static final RowMapper<Order> ORDER = (r, n) -> new Order(r.getString("id"), r.getString("principal_id"),
            r.getString("source"), r.getBoolean("simulation"), r.getString("currency"), r.getString("total_amount"),
            r.getString("order_state"), r.getString("payment_state"), r.getString("refund_state"), r.getString("print_state"), r.getLong("version"));
    private static final RowMapper<Entry> ENTRY = (r, n) -> new Entry(r.getString("id"), r.getString("order_id"),
            r.getString("idempotency_sha256"), r.getString("input_sha256"), r.getString("outcome"), r.getString("reason"),
            r.getLong("order_version_before"), r.getLong("order_version_after"), r.getString("task_id"),
            r.getString("task_state_before"), r.getString("task_state_after"), r.getObject("task_version_before", Long.class),
            r.getObject("task_version_after", Long.class), r.getObject("task_event_seq_before", Long.class),
            r.getObject("task_event_seq_after", Long.class), r.getObject("task_fence_before", Long.class),
            r.getObject("task_fence_after", Long.class), r.getLong("created_at_ms"), r.getBoolean("simulation"));
    private final JdbcTemplate jdbc;
    public MiniappDemoCancellationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    Optional<Order> order(MiniappDemoIdentity owner, String id, boolean lock) {
        return jdbc.query("SELECT id,principal_id,source,simulation,currency,total_amount,order_state,payment_state,refund_state,print_state,version "
                + "FROM print_miniapp_demo_order WHERE " + OWNER + " AND id=?" + (lock ? " FOR UPDATE" : ""), ORDER,
                owner.tenantId(), owner.storeId(), owner.principalId(), id).stream().findFirst();
    }
    Optional<Order> orderAdmin(PrintScope scope, String id) {
        return jdbc.query("SELECT id,principal_id,source,simulation,currency,total_amount,order_state,payment_state,refund_state,print_state,version "
                        + "FROM print_miniapp_demo_order WHERE tenant_id=? AND store_id=? AND id=?", ORDER,
                scope.tenantId(), scope.storeId(), id).stream().findFirst();
    }
    Optional<Task> task(MiniappDemoIdentity owner, String orderId, boolean lock) {
        return jdbc.query("SELECT id,state,version,event_seq,fence,lease_id,lease_until_ms FROM print_miniapp_demo_print_task WHERE "
                        + OWNER + " AND order_id=?" + (lock ? " FOR UPDATE" : ""),
                (r, n) -> new Task(r.getString("id"), r.getString("state"), r.getLong("version"), r.getLong("event_seq"),
                        r.getLong("fence"), r.getString("lease_id"), r.getObject("lease_until_ms", Long.class)),
                owner.tenantId(), owner.storeId(), owner.principalId(), orderId).stream().findFirst();
    }
    Optional<Entry> byKey(MiniappDemoIdentity owner, String keyHash) {
        return jdbc.query("SELECT " + ENTRY_FIELDS + " FROM print_miniapp_demo_cancellation WHERE " + OWNER
                        + " AND idempotency_sha256=? FOR UPDATE", ENTRY, owner.tenantId(), owner.storeId(), owner.principalId(), keyHash).stream().findFirst();
    }
    Optional<Entry> byOrder(MiniappDemoIdentity owner, String id, boolean lock) {
        return jdbc.query("SELECT " + ENTRY_FIELDS + " FROM print_miniapp_demo_cancellation WHERE " + OWNER
                        + " AND order_id=?" + (lock ? " FOR UPDATE" : ""), ENTRY,
                owner.tenantId(), owner.storeId(), owner.principalId(), id).stream().findFirst();
    }
    public String outcome(MiniappDemoIdentity owner, String id) { return byOrder(owner, id, false).map(Entry::outcome).orElse(null); }
    public String outcomeAdmin(PrintScope scope, String principalId, String id) {
        return outcome(new MiniappDemoIdentity("", principalId, scope.tenantId(), scope.storeId(), Long.MAX_VALUE), id);
    }
    Optional<Payment> payment(MiniappDemoIdentity owner, String orderId, boolean lock) {
        return jdbc.query("SELECT id,order_id,provider,mode,state,simulation,currency,amount FROM print_miniapp_demo_payment_receipt WHERE "
                        + OWNER + " AND order_id=?" + (lock ? " FOR UPDATE" : ""),
                (r,n) -> new Payment(r.getString("id"), r.getString("order_id"), r.getString("provider"),r.getString("mode"),
                        r.getString("state"),r.getBoolean("simulation"),r.getString("currency"),r.getString("amount")),
                owner.tenantId(), owner.storeId(), owner.principalId(), orderId).stream().findFirst();
    }
    List<Journal> paymentJournals(MiniappDemoIdentity owner, String id, boolean lock) {
        return jdbc.query("SELECT side,account,currency,amount FROM print_miniapp_demo_payment_journal WHERE " + OWNER
                        + " AND receipt_id=? ORDER BY side" + (lock ? " FOR UPDATE" : ""),
                (r,n) -> new Journal(r.getString("side"),r.getString("account"),r.getString("currency"),r.getString("amount")),
                owner.tenantId(), owner.storeId(), owner.principalId(), id);
    }
    Optional<Refund> refund(MiniappDemoIdentity owner, String orderId) {
        return jdbc.query("SELECT id,cancellation_id,payment_receipt_id,order_id,provider,mode,state,simulation,currency,amount,created_at_ms "
                        + "FROM print_miniapp_demo_refund_receipt WHERE " + OWNER + " AND order_id=?",
                (r,n) -> new Refund(r.getString("id"),r.getString("cancellation_id"),r.getString("payment_receipt_id"),
                        r.getString("order_id"),r.getString("provider"),r.getString("mode"),r.getString("state"),r.getBoolean("simulation"),
                        r.getString("currency"),r.getString("amount"),r.getLong("created_at_ms")),
                owner.tenantId(), owner.storeId(), owner.principalId(), orderId).stream().findFirst();
    }
    List<Journal> refundJournals(MiniappDemoIdentity owner, String id) {
        return jdbc.query("SELECT side,account,currency,amount FROM print_miniapp_demo_refund_journal WHERE " + OWNER
                        + " AND refund_receipt_id=? ORDER BY side",
                (r,n) -> new Journal(r.getString("side"),r.getString("account"),r.getString("currency"),r.getString("amount")),
                owner.tenantId(), owner.storeId(), owner.principalId(), id);
    }
    void insertEntry(MiniappDemoIdentity owner, Entry e) {
        jdbc.update("INSERT INTO print_miniapp_demo_cancellation(id,tenant_id,store_id,principal_id,order_id,idempotency_sha256,input_sha256,"
                        + "outcome,reason,order_version_before,order_version_after,task_id,task_state_before,task_state_after,task_version_before,"
                        + "task_version_after,task_event_seq_before,task_event_seq_after,task_fence_before,task_fence_after,created_at_ms,simulation) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)", e.id(),owner.tenantId(),owner.storeId(),owner.principalId(),e.orderId(),
                e.keyHash(),e.inputHash(),e.outcome(),e.reason(),e.orderVersionBefore(),e.orderVersionAfter(),e.taskId(),e.taskStateBefore(),
                e.taskStateAfter(),e.taskVersionBefore(),e.taskVersionAfter(),e.taskEventSeqBefore(),e.taskEventSeqAfter(),e.taskFenceBefore(),e.taskFenceAfter(),e.createdAtMs());
    }
    void insertRefund(MiniappDemoIdentity owner, Entry e, Payment p, Order order) {
        if (!"CANCELLED_REFUNDED".equals(e.outcome())) {
            throw new MiniappDemoCancellationException(MiniappDemoCancellationException.Reason.STORAGE_UNAVAILABLE);
        }
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO print_miniapp_demo_refund_receipt(id,cancellation_id,payment_receipt_id,order_id,tenant_id,store_id,principal_id,"
                        + "cancellation_outcome,provider,mode,state,simulation,currency,amount,created_at_ms) VALUES(?,?,?,?,?,?,?,'CANCELLED_REFUNDED','SIMULATED','DEMO','SUCCEEDED',1,?,?,?)",
                id,e.id(),p.id(),order.id(),owner.tenantId(),owner.storeId(),owner.principalId(),order.currency(),order.amount(),e.createdAtMs());
        for (String side : List.of("DEBIT","CREDIT")) {
            jdbc.update("INSERT INTO print_miniapp_demo_refund_journal(id,refund_receipt_id,order_id,tenant_id,store_id,principal_id,side,account,currency,amount,created_at_ms) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(),id,order.id(),owner.tenantId(),owner.storeId(),owner.principalId(),
                    side,"DEBIT".equals(side)?"DEMO_STORE":"DEMO_CUSTOMER",order.currency(),order.amount(),e.createdAtMs());
        }
    }
    boolean cancelOrder(MiniappDemoIdentity owner, Order order, Entry e) {
        return jdbc.update("UPDATE print_miniapp_demo_order SET order_state='CANCELLED',refund_state=?,print_state=?,version=?,updated_at_ms=? WHERE "
                        + OWNER + " AND id=? AND order_state='OPEN' AND payment_state=? AND refund_state='NONE' AND print_state=? AND version=?",
                "CANCELLED_REFUNDED".equals(e.outcome())?"SUCCEEDED":"NONE", e.taskId()==null?"NOT_CREATED":"CANCELLED",e.orderVersionAfter(),e.createdAtMs(),
                owner.tenantId(),owner.storeId(),owner.principalId(),order.id(),order.paymentState(),order.printState(),order.version()) == 1;
    }
    boolean cancelTask(MiniappDemoIdentity owner, String orderId, Task task, long now) {
        return jdbc.update("UPDATE print_miniapp_demo_print_task SET state='CANCELLED',version=2,event_seq=2,updated_at_ms=? WHERE " + OWNER
                        + " AND id=? AND order_id=? AND state='QUEUED' AND version=1 AND event_seq=1 AND fence=0 AND lease_id IS NULL AND lease_until_ms IS NULL",
                now,owner.tenantId(),owner.storeId(),owner.principalId(),task.id(),orderId)==1;
    }
    void event(MiniappDemoIdentity owner, Entry e) {
        jdbc.update("INSERT INTO print_miniapp_demo_order_event(id,order_id,tenant_id,store_id,principal_id,event_type,actor_type,actor_id,simulation,aggregate_version,created_at_ms) "
                        + "VALUES(?,?,?,?,?,?,'MINIAPP_DEMO',?,1,?,?)",UUID.randomUUID().toString(),e.orderId(),owner.tenantId(),owner.storeId(),owner.principalId(),
                "CANCELLED_UNPAID".equals(e.outcome())?"ORDER_CANCELLED_UNPAID":"ORDER_CANCELLED_REFUNDED",owner.principalId(),e.orderVersionAfter(),e.createdAtMs());
    }
}
