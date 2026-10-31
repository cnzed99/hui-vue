package cn.iocoder.yudao.server.print.miniappdemo.printtask;

import cn.iocoder.yudao.server.print.scope.PrintScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MiniappDemoPrintTaskRepository {
    record Order(String id, String principalId, String currency, String totalAmount,
                 String orderState, String paymentState, String refundState, String printState, long version) {}
    record Item(String id, String fileId, String fileSha256, String snapshotJson, String amount) {}
    record File(String id, String sha256, Integer pages, String status, long expiresAtMs) {}
    record Task(String id, String jobId, String orderId, String orderItemId, String principalId,
                String fileId, String fileSha256, String plannedCopies, String plannedSheets,
                String state, long version, long fence, String leaseId, Long leaseUntilMs,
                long eventSeq, String createKeyHash, String createInputHash,
                long createdAtMs, long updatedAtMs) {}
    record StoredAction(String inputHash, String resultJson) {}
    record Receipt(String id, long receiptSeq, String result, String reason, String plannedCopies,
                   String plannedSheets, String completedCopies, String completedSheets,
                   boolean simulation, long createdAtMs) {}

    private static final String TASK_FIELDS = "id,job_id,order_id,order_item_id,principal_id,file_id,"
            + "file_content_sha256,planned_copies,planned_sheets,state,version,fence,lease_id,lease_until_ms,"
            + "event_seq,create_idempotency_sha256,create_input_sha256,created_at_ms,updated_at_ms";
    private static final RowMapper<Task> TASK_MAPPER = (rs, n) -> new Task(rs.getString("id"),
            rs.getString("job_id"), rs.getString("order_id"), rs.getString("order_item_id"),
            rs.getString("principal_id"), rs.getString("file_id"), rs.getString("file_content_sha256"),
            rs.getString("planned_copies"), rs.getString("planned_sheets"), rs.getString("state"),
            rs.getLong("version"), rs.getLong("fence"), rs.getString("lease_id"),
            rs.getObject("lease_until_ms", Long.class), rs.getLong("event_seq"),
            rs.getString("create_idempotency_sha256"), rs.getString("create_input_sha256"),
            rs.getLong("created_at_ms"), rs.getLong("updated_at_ms"));

    private final JdbcTemplate jdbc;
    public MiniappDemoPrintTaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    boolean lockScope(PrintScope scope) {
        List<Integer> tenant = jdbc.query("SELECT status,deleted,expire_time>CURRENT_TIMESTAMP AS active "
                        + "FROM system_tenant WHERE id=? FOR UPDATE",
                (rs, n) -> rs.getInt("status") == 0 && !rs.getBoolean("deleted") && rs.getBoolean("active") ? 1 : 0,
                scope.tenantId());
        if (tenant.size() != 1 || tenant.get(0) != 1) return false;
        List<Integer> store = jdbc.query("SELECT status,deleted FROM print_store WHERE tenant_id=? AND id=? FOR UPDATE",
                (rs, n) -> rs.getInt("status") == 0 && !rs.getBoolean("deleted") ? 1 : 0,
                scope.tenantId(), scope.storeId());
        if (store.size() != 1 || store.get(0) != 1) return false;
        List<Integer> user = jdbc.query("SELECT status,deleted FROM system_users WHERE tenant_id=? AND id=? FOR UPDATE",
                (rs, n) -> rs.getInt("status") == 0 && !rs.getBoolean("deleted") ? 1 : 0,
                scope.tenantId(), scope.userId());
        if (user.size() != 1 || user.get(0) != 1) return false;
        List<Integer> mapping = jdbc.query("SELECT status,deleted FROM print_staff_store "
                        + "WHERE tenant_id=? AND store_id=? AND user_id=? FOR UPDATE",
                (rs, n) -> rs.getInt("status") == 0 && !rs.getBoolean("deleted") ? 1 : 0,
                scope.tenantId(), scope.storeId(), scope.userId());
        return mapping.size() == 1 && mapping.get(0) == 1;
    }

    Optional<Order> orderForUpdate(PrintScope scope, String id) {
        return jdbc.query("SELECT id,principal_id,currency,total_amount,order_state,payment_state,refund_state,"
                        + "print_state,version FROM print_miniapp_demo_order WHERE tenant_id=? AND store_id=? AND id=? FOR UPDATE",
                (rs, n) -> new Order(rs.getString("id"), rs.getString("principal_id"), rs.getString("currency"),
                        rs.getString("total_amount"), rs.getString("order_state"), rs.getString("payment_state"),
                        rs.getString("refund_state"), rs.getString("print_state"), rs.getLong("version")),
                scope.tenantId(), scope.storeId(), id).stream().findFirst();
    }

    Optional<Item> itemForUpdate(PrintScope scope, Order order) {
        return jdbc.query("SELECT id,file_id,file_content_sha256,quote_snapshot_json,amount "
                        + "FROM print_miniapp_demo_order_item WHERE tenant_id=? AND store_id=? AND principal_id=? "
                        + "AND order_id=? FOR UPDATE", (rs, n) -> new Item(rs.getString("id"),
                        rs.getString("file_id"), rs.getString("file_content_sha256"),
                        rs.getString("quote_snapshot_json"), rs.getString("amount")),
                scope.tenantId(), scope.storeId(), order.principalId(), order.id()).stream().findFirst();
    }

    Optional<File> fileForUpdate(PrintScope scope, String principalId, String id) {
        return jdbc.query("SELECT id,content_sha256,page_count,status,expires_at_ms FROM print_miniapp_demo_file "
                        + "WHERE tenant_id=? AND store_id=? AND principal_id=? AND id=? FOR UPDATE",
                (rs, n) -> new File(rs.getString("id"), rs.getString("content_sha256"),
                        rs.getObject("page_count", Integer.class), rs.getString("status"), rs.getLong("expires_at_ms")),
                scope.tenantId(), scope.storeId(), principalId, id).stream().findFirst();
    }

    Optional<Task> taskByOrderForUpdate(PrintScope scope, Order order) {
        return jdbc.query("SELECT " + TASK_FIELDS + " FROM print_miniapp_demo_print_task WHERE tenant_id=? "
                        + "AND store_id=? AND principal_id=? AND order_id=? FOR UPDATE", TASK_MAPPER,
                scope.tenantId(), scope.storeId(), order.principalId(), order.id()).stream().findFirst();
    }

    Optional<Task> taskByOrder(PrintScope scope, String orderId) {
        return jdbc.query("SELECT " + TASK_FIELDS + " FROM print_miniapp_demo_print_task WHERE tenant_id=? "
                        + "AND store_id=? AND order_id=?", TASK_MAPPER,
                scope.tenantId(), scope.storeId(), orderId).stream().findFirst();
    }

    Optional<Task> createIdempotentForUpdate(PrintScope scope, String principalId, String keyHash) {
        return jdbc.query("SELECT " + TASK_FIELDS + " FROM print_miniapp_demo_print_task WHERE tenant_id=? "
                        + "AND store_id=? AND principal_id=? AND create_idempotency_sha256=? FOR UPDATE", TASK_MAPPER,
                scope.tenantId(), scope.storeId(), principalId, keyHash).stream().findFirst();
    }

    Optional<String> taskOrderId(PrintScope scope, String taskId) {
        return jdbc.query("SELECT order_id FROM print_miniapp_demo_print_task WHERE tenant_id=? AND store_id=? AND id=?",
                (rs, n) -> rs.getString("order_id"), scope.tenantId(), scope.storeId(), taskId).stream().findFirst();
    }

    Optional<Task> taskForUpdate(PrintScope scope, String taskId) {
        return jdbc.query("SELECT " + TASK_FIELDS + " FROM print_miniapp_demo_print_task WHERE tenant_id=? "
                        + "AND store_id=? AND id=? FOR UPDATE", TASK_MAPPER,
                scope.tenantId(), scope.storeId(), taskId).stream().findFirst();
    }

    Optional<Task> taskForOwner(long tenantId, long storeId, String principalId, String orderId) {
        return jdbc.query("SELECT " + TASK_FIELDS + " FROM print_miniapp_demo_print_task WHERE tenant_id=? "
                        + "AND store_id=? AND principal_id=? AND order_id=?", TASK_MAPPER,
                tenantId, storeId, principalId, orderId).stream().findFirst();
    }

    Optional<StoredAction> actionForUpdate(PrintScope scope, Task task, String keyHash) {
        return jdbc.query("SELECT input_sha256,result_json FROM print_miniapp_demo_print_task_action WHERE tenant_id=? "
                        + "AND store_id=? AND principal_id=? AND task_id=? AND idempotency_sha256=? FOR UPDATE",
                (rs, n) -> new StoredAction(rs.getString("input_sha256"), rs.getString("result_json")),
                scope.tenantId(), scope.storeId(), task.principalId(), task.id(), keyHash).stream().findFirst();
    }

    Optional<Receipt> receipt(PrintScope scope, Task task) {
        return receipt(scope.tenantId(), scope.storeId(), task.principalId(), task.id());
    }

    Optional<Receipt> receipt(long tenantId, long storeId, String principalId, String taskId) {
        return jdbc.query("SELECT id,receipt_seq,result,reason,planned_copies,planned_sheets,completed_copies,"
                        + "completed_sheets,simulation,created_at_ms FROM print_miniapp_demo_print_receipt "
                        + "WHERE tenant_id=? AND store_id=? AND principal_id=? AND task_id=? ORDER BY receipt_seq",
                (rs, n) -> new Receipt(rs.getString("id"), rs.getLong("receipt_seq"), rs.getString("result"),
                        rs.getString("reason"), rs.getString("planned_copies"), rs.getString("planned_sheets"),
                        rs.getString("completed_copies"), rs.getString("completed_sheets"),
                        rs.getBoolean("simulation"), rs.getLong("created_at_ms")),
                tenantId, storeId, principalId, taskId).stream().findFirst();
    }

    Task insertCreated(PrintScope scope, Order order, Item item, String fileHash,
                       String plannedCopies, String plannedSheets, String keyHash, String inputHash,
                       String resultJson, long now) {
        String id = UUID.randomUUID().toString();
        String jobId = "PJ" + id.replace("-", "").toUpperCase(java.util.Locale.ROOT);
        jdbc.update("INSERT INTO print_miniapp_demo_print_task(id,job_id,order_id,order_item_id,tenant_id,store_id,"
                        + "principal_id,file_id,file_content_sha256,planned_copies,planned_sheets,state,version,fence,"
                        + "lease_id,lease_until_ms,event_seq,create_idempotency_sha256,create_input_sha256,created_at_ms,updated_at_ms) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,'QUEUED',1,0,NULL,NULL,1,?,?,?,?)",
                id, jobId, order.id(), item.id(), scope.tenantId(), scope.storeId(), order.principalId(),
                item.fileId(), fileHash, plannedCopies, plannedSheets, keyHash, inputHash, now, now);
        insertAction(scope, order, id, keyHash, inputHash, "CREATE_TASK", "NOT_CREATED", "QUEUED",
                1, 3, 1, resultJson, now);
        int updated = jdbc.update("UPDATE print_miniapp_demo_order SET print_state='QUEUED',version=3,updated_at_ms=? "
                        + "WHERE tenant_id=? AND store_id=? AND principal_id=? AND id=? AND order_state='OPEN' "
                        + "AND payment_state='SUCCEEDED' AND refund_state='NONE' AND print_state='NOT_CREATED' AND version=2",
                now, scope.tenantId(), scope.storeId(), order.principalId(), order.id());
        if (updated != 1) throw failure();
        insertOrderEvent(scope, order, "PRINT_TASK_CREATED", 3, now);
        return taskForUpdate(scope, id).orElseThrow(MiniappDemoPrintTaskRepository::failure);
    }

    Task transition(PrintScope scope, Order order, Task task, String action, String afterState,
                    long nextTaskVersion, long nextOrderVersion, long nextEventSeq,
                    long nextFence, String nextLeaseId, Long nextLeaseUntilMs,
                    String keyHash, String inputHash, String resultJson,
                    ReceiptWrite receipt, long now) {
        int taskUpdated = jdbc.update("UPDATE print_miniapp_demo_print_task SET state=?,version=?,fence=?,lease_id=?,"
                        + "lease_until_ms=?,event_seq=?,updated_at_ms=? WHERE tenant_id=? AND store_id=? "
                        + "AND principal_id=? AND id=? AND state=? AND version=? AND event_seq=?",
                afterState, nextTaskVersion, nextFence, nextLeaseId, nextLeaseUntilMs, nextEventSeq, now,
                scope.tenantId(), scope.storeId(), task.principalId(), task.id(), task.state(), task.version(), task.eventSeq());
        int orderUpdated = jdbc.update("UPDATE print_miniapp_demo_order SET print_state=?,version=?,updated_at_ms=? "
                        + "WHERE tenant_id=? AND store_id=? AND principal_id=? AND id=? AND print_state=? AND version=? "
                        + "AND order_state='OPEN' AND payment_state='SUCCEEDED' AND refund_state='NONE'",
                afterState, nextOrderVersion, now, scope.tenantId(), scope.storeId(), order.principalId(), order.id(),
                order.printState(), order.version());
        if (taskUpdated != 1 || orderUpdated != 1) throw failure();
        insertAction(scope, order, task.id(), keyHash, inputHash, action, task.state(), afterState,
                nextTaskVersion, nextOrderVersion, nextEventSeq, resultJson, now);
        insertOrderEvent(scope, order, eventType(action, afterState), nextOrderVersion, now);
        if (receipt != null) insertReceipt(scope, order, task, receipt, now);
        return taskForUpdate(scope, task.id()).orElseThrow(MiniappDemoPrintTaskRepository::failure);
    }

    private void insertAction(PrintScope scope, Order order, String taskId, String keyHash, String inputHash,
                              String action, String beforeState, String afterState, long taskVersionAfter,
                              long orderVersionAfter, long eventSeq, String resultJson, long now) {
        jdbc.update("INSERT INTO print_miniapp_demo_print_task_action(id,task_id,order_id,tenant_id,store_id,"
                        + "principal_id,idempotency_sha256,input_sha256,action,before_state,after_state,"
                        + "task_version_after,order_version_after,event_seq,actor_type,actor_id,simulation,result_json,created_at_ms) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'STAFF_SIMULATOR',?,1,?,?)",
                UUID.randomUUID().toString(), taskId, order.id(), scope.tenantId(), scope.storeId(), order.principalId(),
                keyHash, inputHash, action, beforeState, afterState, taskVersionAfter, orderVersionAfter, eventSeq,
                scope.userId(), resultJson, now);
    }

    private void insertOrderEvent(PrintScope scope, Order order, String type, long version, long now) {
        jdbc.update("INSERT INTO print_miniapp_demo_order_event(id,order_id,tenant_id,store_id,principal_id,event_type,"
                        + "actor_type,actor_id,simulation,aggregate_version,created_at_ms) "
                        + "VALUES(?,?,?,?,?,?, 'STAFF_SIMULATOR',?,1,?,?)",
                UUID.randomUUID().toString(), order.id(), scope.tenantId(), scope.storeId(), order.principalId(),
                type, Long.toString(scope.userId()), version, now);
    }

    private void insertReceipt(PrintScope scope, Order order, Task task, ReceiptWrite value, long now) {
        jdbc.update("INSERT INTO print_miniapp_demo_print_receipt(id,task_id,order_id,order_item_id,tenant_id,store_id,"
                        + "principal_id,receipt_seq,result,reason,planned_copies,planned_sheets,completed_copies,"
                        + "completed_sheets,file_content_sha256,actor_type,actor_id,simulation,created_at_ms) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?)",
                UUID.randomUUID().toString(), task.id(), order.id(), task.orderItemId(), scope.tenantId(),
                scope.storeId(), order.principalId(), value.receiptSeq(), value.result(), value.reason(),
                task.plannedCopies(), task.plannedSheets(), value.completedCopies(), value.completedSheets(),
                task.fileSha256(), "STAFF_SIMULATOR", scope.userId(), now);
    }

    private static String eventType(String action, String state) {
        return switch (action) {
            case "REQUEUE_EXPIRED_CLAIM" -> "PRINT_TASK_REQUEUED";
            case "MARK_TIMEOUT_UNKNOWN", "LOSE_RECEIPT" -> "PRINT_TASK_RESULT_UNKNOWN";
            case "ABORT_FILE_UNAVAILABLE", "FAIL" -> "PRINT_TASK_FAILED";
            default -> "PRINT_TASK_" + state;
        };
    }

    record ReceiptWrite(long receiptSeq, String result, String reason,
                        String completedCopies, String completedSheets) {}
    private static MiniappDemoPrintTaskException failure() {
        return new MiniappDemoPrintTaskException(MiniappDemoPrintTaskException.Reason.STORAGE_UNAVAILABLE);
    }
}
