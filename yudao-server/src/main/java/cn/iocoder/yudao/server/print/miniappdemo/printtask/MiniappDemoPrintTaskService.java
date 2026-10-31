package cn.iocoder.yudao.server.print.miniappdemo.printtask;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.quote.MiniappDemoQuoteSnapshot;
import cn.iocoder.yudao.server.print.miniappdemo.quote.MiniappDemoQuoteVerifier;
import cn.iocoder.yudao.server.print.scope.PrintScope;
import cn.iocoder.yudao.server.print.scope.PrintScopeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import static cn.iocoder.yudao.server.print.miniappdemo.printtask.MiniappDemoPrintTaskException.Reason.*;

@Service
public class MiniappDemoPrintTaskService {
    private static final Set<String> ACTIONS = Set.of("CLAIM", "REQUEUE_EXPIRED_CLAIM", "ACCEPT", "START",
            "SUCCEED", "FAIL", "LOSE_RECEIPT", "MARK_TIMEOUT_UNKNOWN", "ABORT_FILE_UNAVAILABLE");
    private final MiniappDemoPrintTaskProperties properties;
    private final MiniappDemoPrintTaskRepository repository;
    private final PrintScopeService scopes;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final TransactionTemplate readTransaction;

    public MiniappDemoPrintTaskService(MiniappDemoPrintTaskProperties properties,
            MiniappDemoPrintTaskRepository repository, PrintScopeService scopes, ObjectMapper mapper,
            PlatformTransactionManager manager) {
        this.properties = properties;
        this.repository = repository;
        this.scopes = scopes;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.readTransaction = new TransactionTemplate(manager);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTransaction.setReadOnly(true);
    }

    public MiniappDemoPrintTaskView create(String orderId, JsonNode body, String idempotencyKey) {
        properties.requireEnabled();
        if (!uuid(orderId) || body == null || !body.isObject() || body.size() != 0 || !key(idempotencyKey)) {
            throw failure(INVALID_INPUT);
        }
        PrintScope scope = scopes.requireCurrent();
        String keyHash = hash(idempotencyKey);
        String inputHash = hash("print-task-create-v1\n" + orderId);
        return transaction.execute(status -> {
            requireScope(scope);
            var order = repository.orderForUpdate(scope, orderId).orElseThrow(() -> failure(ORDER_NOT_FOUND));
            var idempotent = repository.createIdempotentForUpdate(scope, order.principalId(), keyHash);
            if (idempotent.isPresent()) {
                if (!inputHash.equals(idempotent.get().createInputHash())) throw failure(IDEMPOTENCY_CONFLICT);
                requireTaskOrder(order, idempotent.get());
                return view(scope, idempotent.get());
            }
            var existingTask = repository.taskByOrderForUpdate(scope, order);
            if (existingTask.isPresent()) {
                requireTaskOrder(order, existingTask.get());
                return view(scope, existingTask.get());
            }
            requirePaidNotCreated(order);
            var item = repository.itemForUpdate(scope, order).orElseThrow(() -> failure(STORAGE_UNAVAILABLE));
            var evidence = evidence(item);
            var file = repository.fileForUpdate(scope, order.principalId(), item.fileId());
            long now = System.currentTimeMillis();
            requireAvailable(evidence, item, file.orElse(null), now);
            var result = new MiniappDemoPrintActionView.ActionResult("CREATE_TASK", "NOT_CREATED", "QUEUED",
                    1, 3, 1, null, now);
            var task = repository.insertCreated(scope, order, item, item.fileSha256(), evidence.snapshot().copies(),
                    evidence.snapshot().totalSheets(), keyHash, inputHash, write(result), now);
            return view(scope, task);
        });
    }

    public MiniappDemoPrintActionView act(String taskId, JsonNode body, String idempotencyKey) {
        properties.requireEnabled();
        ActionInput input = parseAction(taskId, body, idempotencyKey);
        PrintScope scope = scopes.requireCurrent();
        String keyHash = hash(idempotencyKey);
        String inputHash = hash(input.canonical());
        return transaction.execute(status -> {
            requireScope(scope);
            String orderId = repository.taskOrderId(scope, taskId).orElseThrow(() -> failure(TASK_NOT_FOUND));
            var order = repository.orderForUpdate(scope, orderId).orElseThrow(() -> failure(TASK_NOT_FOUND));
            var task = repository.taskForUpdate(scope, taskId).orElseThrow(() -> failure(TASK_NOT_FOUND));
            requireTaskOrder(order, task);
            var stored = repository.actionForUpdate(scope, task, keyHash);
            if (stored.isPresent()) {
                if (!inputHash.equals(stored.get().inputHash())) throw failure(IDEMPOTENCY_CONFLICT);
                return new MiniappDemoPrintActionView(readResult(stored.get().resultJson()), view(scope, task));
            }
            if (task.version() != input.expectedVersion()) throw failure(STALE_VERSION);
            Transition next = transition(scope, order, task, input, System.currentTimeMillis());
            long now = next.occurredAtMs();
            long taskVersion = task.version() + 1;
            long orderVersion = order.version() + 1;
            long eventSeq = task.eventSeq() + 1;
            var original = new MiniappDemoPrintActionView.ActionResult(input.action(), task.state(), next.state(),
                    taskVersion, orderVersion, eventSeq, next.receipt() == null ? null
                    : next.receipt().receiptSeq(), now);
            var current = repository.transition(scope, order, task, input.action(), next.state(), taskVersion,
                    orderVersion, eventSeq, next.fence(), next.leaseId(), next.leaseUntilMs(), keyHash, inputHash,
                    write(original), next.receipt(), now);
            return new MiniappDemoPrintActionView(original, view(scope, current));
        });
    }

    public MiniappDemoPrintTaskView getAdmin(String taskId) {
        properties.requireEnabled();
        if (!uuid(taskId)) throw failure(INVALID_INPUT);
        PrintScope scope = scopes.requireCurrent();
        return readTransaction.execute(status -> {
            String orderId = repository.taskOrderId(scope, taskId).orElseThrow(() -> failure(TASK_NOT_FOUND));
            var order = repository.order(scope, orderId).orElseThrow(() -> failure(TASK_NOT_FOUND));
            var task = repository.task(scope, taskId).orElseThrow(() -> failure(TASK_NOT_FOUND));
            requireTaskOrder(order, task);
            return view(scope, task);
        });
    }

    public MiniappDemoPrintTaskView getAdminByOrder(String orderId) {
        properties.requireEnabled();
        if (!uuid(orderId)) throw failure(INVALID_INPUT);
        return getAdminByOrder(scopes.requireCurrent(), orderId);
    }

    public MiniappDemoPrintTaskView getAdminByOrder(PrintScope scope, String orderId) {
        properties.requireEnabled();
        if (scope == null || !uuid(orderId)) throw failure(INVALID_INPUT);
        return readTransaction.execute(status -> repository.taskByOrder(scope, orderId)
                .map(task -> view(scope, task)).orElse(null));
    }

    public MiniappDemoPrintTaskView getForOwner(MiniappDemoIdentity identity, String orderId) {
        properties.requireEnabled();
        if (identity == null || !uuid(orderId)) throw failure(INVALID_INPUT);
        return readTransaction.execute(status -> {
            var order = repository.orderForOwner(identity.tenantId(), identity.storeId(), identity.principalId(), orderId)
                    .orElseThrow(() -> failure(ORDER_NOT_FOUND));
            var task = repository.taskForOwner(identity.tenantId(), identity.storeId(), identity.principalId(), orderId);
            if (task.isEmpty()) {
                if (!"NOT_CREATED".equals(order.printState()) || order.version() > 2) throw failure(STORAGE_UNAVAILABLE);
                return null;
            }
            requireTaskOrder(order, task.get());
            return view(identity, task.get());
        });
    }

    private Transition transition(PrintScope scope, MiniappDemoPrintTaskRepository.Order order,
                                  MiniappDemoPrintTaskRepository.Task task, ActionInput input, long now) {
        long leaseMs = properties.getLeaseSeconds() * 1000L;
        return switch (input.action()) {
            case "CLAIM" -> {
                requireState(task, "QUEUED");
                long checkedAt = requireFileAvailable(scope, order, task);
                yield new Transition("CLAIMED", task.fence() + 1, UUID.randomUUID().toString(),
                        checkedAt + leaseMs, null, checkedAt);
            }
            case "REQUEUE_EXPIRED_CLAIM" -> {
                requireState(task, "CLAIMED");
                requireExpired(task, now);
                yield new Transition("QUEUED", task.fence(), null, null, null, now);
            }
            case "ACCEPT" -> {
                requireState(task, "CLAIMED"); requireLease(task, input, now);
                long checkedAt = requireFileAvailable(scope, order, task);
                requireLease(task, input, checkedAt);
                yield new Transition("ACCEPTED", task.fence(), task.leaseId(), checkedAt + leaseMs, null, checkedAt);
            }
            case "START" -> {
                requireState(task, "ACCEPTED"); requireLease(task, input, now);
                yield new Transition("EXECUTING", task.fence(), task.leaseId(), now + leaseMs, null, now);
            }
            case "SUCCEED" -> {
                requireState(task, "EXECUTING"); requireLease(task, input, now); requireReceiptSeq(input);
                yield new Transition("SUCCEEDED", task.fence(), task.leaseId(), task.leaseUntilMs(),
                        new MiniappDemoPrintTaskRepository.ReceiptWrite(1, "SIMULATED_SUCCESS", null,
                                task.plannedCopies(), task.plannedSheets()), now);
            }
            case "FAIL" -> {
                requireOneOf(task, "ACCEPTED", "EXECUTING"); requireLease(task, input, now); requireReceiptSeq(input);
                yield new Transition("FAILED", task.fence(), task.leaseId(), task.leaseUntilMs(),
                        new MiniappDemoPrintTaskRepository.ReceiptWrite(1, "SIMULATED_FAILED",
                                "SIMULATED_DEVICE_FAILURE", "0", "0"), now);
            }
            case "LOSE_RECEIPT" -> {
                requireOneOf(task, "ACCEPTED", "EXECUTING"); requireLease(task, input, now);
                yield new Transition("RESULT_UNKNOWN", task.fence(), task.leaseId(), task.leaseUntilMs(), null, now);
            }
            case "MARK_TIMEOUT_UNKNOWN" -> {
                requireOneOf(task, "ACCEPTED", "EXECUTING"); requireExpired(task, now);
                yield new Transition("RESULT_UNKNOWN", task.fence(), task.leaseId(), task.leaseUntilMs(), null, now);
            }
            case "ABORT_FILE_UNAVAILABLE" -> {
                requireOneOf(task, "QUEUED", "CLAIMED"); requireReceiptSeq(input);
                var item = repository.itemForUpdate(scope, order).orElseThrow(() -> failure(STORAGE_UNAVAILABLE));
                var evidence = evidence(item);
                var file = repository.fileForUpdate(scope, order.principalId(), task.fileId()).orElse(null);
                long checkedAt = System.currentTimeMillis();
                if (available(evidence, item, file, checkedAt)) throw failure(INVALID_STATE);
                yield new Transition("FAILED", task.fence(), task.leaseId(), task.leaseUntilMs(),
                        new MiniappDemoPrintTaskRepository.ReceiptWrite(1, "SIMULATED_FAILED",
                                "FILE_UNAVAILABLE_BEFORE_ACCEPT", "0", "0"), checkedAt);
            }
            default -> throw failure(INVALID_INPUT);
        };
    }

    private long requireFileAvailable(PrintScope scope, MiniappDemoPrintTaskRepository.Order order,
                                      MiniappDemoPrintTaskRepository.Task task) {
        var item = repository.itemForUpdate(scope, order).orElseThrow(() -> failure(STORAGE_UNAVAILABLE));
        var evidence = evidence(item);
        if (!item.id().equals(task.orderItemId()) || !evidence.snapshot().copies().equals(task.plannedCopies())
                || !evidence.snapshot().totalSheets().equals(task.plannedSheets())) throw failure(SNAPSHOT_INVALID);
        var file = repository.fileForUpdate(scope, order.principalId(), task.fileId()).orElse(null);
        long checkedAt = System.currentTimeMillis();
        requireAvailable(evidence, item, file, checkedAt);
        return checkedAt;
    }

    private Evidence evidence(MiniappDemoPrintTaskRepository.Item item) {
        try {
            MiniappDemoQuoteSnapshot snapshot = mapper.readValue(item.snapshotJson(), MiniappDemoQuoteSnapshot.class);
            MiniappDemoQuoteVerifier.Verified verified = MiniappDemoQuoteVerifier.verify(snapshot);
            if (!item.amount().equals(verified.totalAmount()) || !item.fileId().equals(snapshot.fileId())
                    || !item.fileSha256().equals(snapshot.fileSha256())) throw failure(SNAPSHOT_INVALID);
            return new Evidence(snapshot);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw failure(SNAPSHOT_INVALID);
        }
    }

    private static boolean available(Evidence evidence, MiniappDemoPrintTaskRepository.Item item,
                                     MiniappDemoPrintTaskRepository.File file, long now) {
        var snapshot = evidence.snapshot();
        return file != null && "READY".equals(file.status()) && file.expiresAtMs() > now
                && file.id().equals(item.fileId()) && file.sha256().equals(item.fileSha256())
                && file.pages() != null && file.pages() == snapshot.pagesPerCopy();
    }

    private static void requireAvailable(Evidence evidence, MiniappDemoPrintTaskRepository.Item item,
                                         MiniappDemoPrintTaskRepository.File file, long now) {
        if (!available(evidence, item, file, now)) throw failure(FILE_UNAVAILABLE);
    }

    private MiniappDemoPrintTaskView view(PrintScope scope, MiniappDemoPrintTaskRepository.Task task) {
        var receipt = repository.receipt(scope, task).map(MiniappDemoPrintTaskService::viewReceipt).orElse(null);
        return view(task, receipt);
    }
    private MiniappDemoPrintTaskView view(MiniappDemoIdentity identity, MiniappDemoPrintTaskRepository.Task task) {
        var receipt = repository.receipt(identity.tenantId(), identity.storeId(), identity.principalId(), task.id())
                .map(MiniappDemoPrintTaskService::viewReceipt).orElse(null);
        return view(task, receipt);
    }
    private static MiniappDemoPrintTaskView view(MiniappDemoPrintTaskRepository.Task task,
                                                  MiniappDemoPrintTaskView.Receipt receipt) {
        boolean completed = "SUCCEEDED".equals(task.state()) || "FAILED".equals(task.state());
        if (completed != (receipt != null)
                || receipt != null && ("SUCCEEDED".equals(task.state())
                != "SIMULATED_SUCCESS".equals(receipt.result()))) throw failure(STORAGE_UNAVAILABLE);
        return new MiniappDemoPrintTaskView(task.id(), task.jobId(), task.orderId(), task.orderItemId(), task.state(),
                task.version(), task.fence(), task.leaseId(), task.leaseUntilMs(), task.plannedCopies(),
                task.plannedSheets(), task.fileId(), task.fileSha256(), task.eventSeq(),
                task.createdAtMs(), task.updatedAtMs(), receipt);
    }
    private static MiniappDemoPrintTaskView.Receipt viewReceipt(MiniappDemoPrintTaskRepository.Receipt row) {
        return new MiniappDemoPrintTaskView.Receipt(row.id(), row.receiptSeq(), row.result(), row.reason(),
                row.plannedCopies(), row.plannedSheets(), row.completedCopies(), row.completedSheets(),
                row.simulation(), row.createdAtMs());
    }

    private ActionInput parseAction(String taskId, JsonNode body, String idempotencyKey) {
        if (!uuid(taskId) || body == null || !body.isObject() || !key(idempotencyKey)
                || !body.has("action") || !body.get("action").isTextual()
                || !ACTIONS.contains(body.get("action").textValue())
                || !body.has("expectedVersion") || !body.get("expectedVersion").isIntegralNumber()
                || !body.get("expectedVersion").canConvertToLong()) {
            throw failure(INVALID_INPUT);
        }
        String action = body.get("action").textValue();
        long expected = body.get("expectedVersion").longValue();
        if (expected < 1) throw failure(INVALID_INPUT);
        boolean leaseAction = Set.of("ACCEPT", "START", "SUCCEED", "FAIL", "LOSE_RECEIPT").contains(action);
        boolean receiptAction = Set.of("SUCCEED", "FAIL", "ABORT_FILE_UNAVAILABLE").contains(action);
        int expectedSize = 2 + (leaseAction ? 2 : 0) + (receiptAction ? 1 : 0);
        if (body.size() != expectedSize) throw failure(INVALID_INPUT);
        String leaseId = leaseAction && body.has("leaseId") && body.get("leaseId").isTextual()
                ? body.get("leaseId").textValue() : null;
        Long fence = leaseAction && body.has("fence") && body.get("fence").isIntegralNumber()
                && body.get("fence").canConvertToLong()
                ? body.get("fence").longValue() : null;
        Long receiptSeq = receiptAction && body.has("receiptSeq") && body.get("receiptSeq").isIntegralNumber()
                && body.get("receiptSeq").canConvertToLong()
                ? body.get("receiptSeq").longValue() : null;
        if (leaseAction && (!uuid(leaseId) || fence == null || fence < 1)
                || receiptAction && (receiptSeq == null || receiptSeq != 1)) throw failure(INVALID_INPUT);
        return new ActionInput(taskId, action, expected, leaseId, fence, receiptSeq);
    }

    private static void requirePaidNotCreated(MiniappDemoPrintTaskRepository.Order order) {
        if (!"OPEN".equals(order.orderState()) || !"SUCCEEDED".equals(order.paymentState())
                || !"NONE".equals(order.refundState()) || !"NOT_CREATED".equals(order.printState())
                || order.version() != 2 || !"CNY".equals(order.currency())) throw failure(ORDER_NOT_PAYABLE);
    }
    private static void requireTaskOrder(MiniappDemoPrintTaskRepository.Order order,
                                         MiniappDemoPrintTaskRepository.Task task) {
        if (!task.orderId().equals(order.id()) || !task.principalId().equals(order.principalId())
                || !task.state().equals(order.printState()) || order.version() < 3
                || !"OPEN".equals(order.orderState()) || !"SUCCEEDED".equals(order.paymentState())
                || !"NONE".equals(order.refundState())) throw failure(STORAGE_UNAVAILABLE);
    }
    private void requireScope(PrintScope scope) {
        if (!repository.lockScope(scope)) throw failure(STORAGE_UNAVAILABLE);
    }
    private static void requireState(MiniappDemoPrintTaskRepository.Task task, String state) {
        if (!state.equals(task.state())) throw failure(INVALID_STATE);
    }
    private static void requireOneOf(MiniappDemoPrintTaskRepository.Task task, String first, String second) {
        if (!first.equals(task.state()) && !second.equals(task.state())) throw failure(INVALID_STATE);
    }
    private static void requireLease(MiniappDemoPrintTaskRepository.Task task, ActionInput input, long now) {
        if (task.leaseId() == null || !task.leaseId().equals(input.leaseId()) || input.fence() == null
                || task.fence() != input.fence() || task.leaseUntilMs() == null || now >= task.leaseUntilMs()) {
            throw failure(STALE_LEASE);
        }
    }
    private static void requireExpired(MiniappDemoPrintTaskRepository.Task task, long now) {
        if (task.leaseUntilMs() == null || now < task.leaseUntilMs()) throw failure(INVALID_STATE);
    }
    private static void requireReceiptSeq(ActionInput input) {
        if (input.receiptSeq() == null || input.receiptSeq() != 1) throw failure(INVALID_INPUT);
    }

    private String write(MiniappDemoPrintActionView.ActionResult result) {
        try { return mapper.writeValueAsString(result); }
        catch (JsonProcessingException ex) { throw failure(STORAGE_UNAVAILABLE); }
    }
    private MiniappDemoPrintActionView.ActionResult readResult(String json) {
        try { return mapper.readValue(json, MiniappDemoPrintActionView.ActionResult.class); }
        catch (JsonProcessingException ex) { throw failure(STORAGE_UNAVAILABLE); }
    }
    private static boolean uuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    private static boolean key(String value) { return value != null && value.matches("[!-~]{1,128}"); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static MiniappDemoPrintTaskException failure(MiniappDemoPrintTaskException.Reason reason) {
        return new MiniappDemoPrintTaskException(reason);
    }

    private record ActionInput(String taskId, String action, long expectedVersion,
                               String leaseId, Long fence, Long receiptSeq) {
        String canonical() { return "print-task-action-v1\n" + taskId + "\n" + action + "\n" + expectedVersion
                + "\n" + value(leaseId) + "\n" + value(fence) + "\n" + value(receiptSeq); }
        private static String value(Object value) { return value == null ? "-" : value.toString(); }
    }
    private record Transition(String state, long fence, String leaseId, Long leaseUntilMs,
                              MiniappDemoPrintTaskRepository.ReceiptWrite receipt, long occurredAtMs) {}
    private record Evidence(MiniappDemoQuoteSnapshot snapshot) {}

}
