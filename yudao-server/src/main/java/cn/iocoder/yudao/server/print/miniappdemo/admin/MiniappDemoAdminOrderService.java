package cn.iocoder.yudao.server.print.miniappdemo.admin;

import cn.iocoder.yudao.server.print.miniappdemo.printtask.MiniappDemoPrintTaskException;
import cn.iocoder.yudao.server.print.miniappdemo.printtask.MiniappDemoPrintTaskService;
import cn.iocoder.yudao.server.print.miniappdemo.quote.MiniappDemoQuoteSnapshot;
import cn.iocoder.yudao.server.print.miniappdemo.quote.MiniappDemoQuoteVerifier;
import cn.iocoder.yudao.server.print.scope.PrintScope;
import cn.iocoder.yudao.server.print.scope.PrintScopeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static cn.iocoder.yudao.server.print.miniappdemo.printtask.MiniappDemoPrintTaskException.Reason.*;

@Service
public class MiniappDemoAdminOrderService {
    private final MiniappDemoAdminOrderRepository repository;
    private final MiniappDemoPrintTaskService tasks;
    private final PrintScopeService scopes;
    private final ObjectMapper mapper;
    private final TransactionTemplate readTransaction;

    public MiniappDemoAdminOrderService(MiniappDemoAdminOrderRepository repository,
            MiniappDemoPrintTaskService tasks, PrintScopeService scopes, ObjectMapper mapper,
            PlatformTransactionManager manager) {
        this.repository = repository;
        this.tasks = tasks;
        this.scopes = scopes;
        this.mapper = mapper;
        this.readTransaction = new TransactionTemplate(manager);
        this.readTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.readTransaction.setReadOnly(true);
    }

    public MiniappDemoAdminOrderPage<MiniappDemoAdminOrderSummary> list(int pageNo, int pageSize) {
        if (pageNo < 1 || pageNo > 1_000_000 || pageSize < 1 || pageSize > 100) throw failure(INVALID_INPUT);
        PrintScope scope = scopes.requireCurrent();
        return readTransaction.execute(status -> {
            long total = repository.count(scope);
            long offset = Math.multiplyExact((long) pageNo - 1L, pageSize);
            List<MiniappDemoAdminOrderSummary> values = repository.list(scope, pageSize, offset).stream()
                    .map(MiniappDemoAdminOrderService::summary).toList();
            return new MiniappDemoAdminOrderPage<>(values, total, pageNo, pageSize);
        });
    }

    public MiniappDemoAdminOrderView get(String id) {
        if (!uuid(id)) throw failure(INVALID_INPUT);
        PrintScope scope = scopes.requireCurrent();
        return readTransaction.execute(status -> get(scope, id));
    }

    private MiniappDemoAdminOrderView get(PrintScope scope, String id) {
        var order = repository.order(scope, id).orElseThrow(() -> failure(ORDER_NOT_FOUND));
        requireOrder(order);
        var item = repository.item(scope, order).orElseThrow(() -> failure(STORAGE_UNAVAILABLE));
        MiniappDemoQuoteSnapshot snapshot = read(item.snapshotJson());
        MiniappDemoQuoteVerifier.Verified verified;
        try { verified = MiniappDemoQuoteVerifier.verify(snapshot); }
        catch (IllegalArgumentException ex) { throw failure(SNAPSHOT_INVALID); }
        if (!item.amount().equals(verified.totalAmount()) || !item.fileId().equals(snapshot.fileId())
                || !item.fileSha256().equals(snapshot.fileSha256()) || !order.totalAmount().equals(item.amount())) {
            throw failure(STORAGE_UNAVAILABLE);
        }
        var payment = repository.payment(scope, order).map(row -> new MiniappDemoAdminOrderView.Payment(
                row.id(), row.provider(), row.mode(), row.state(), row.simulation(), row.currency(),
                row.amount(), row.createdAtMs())).orElse(null);
        var events = repository.events(scope, order).stream().map(row -> new MiniappDemoAdminOrderView.Event(
                row.id(), row.type(), row.actorType(), row.actorId(), row.simulation(),
                row.aggregateVersion(), row.createdAtMs())).toList();
        var printTask = tasks.getAdminByOrder(scope, id);
        if (("NOT_CREATED".equals(order.printState())) != (printTask == null)
                || printTask != null && (!order.id().equals(printTask.orderId())
                || !order.printState().equals(printTask.state()))) throw failure(STORAGE_UNAVAILABLE);
        if (("SUCCEEDED".equals(order.paymentState())) != (payment != null)) throw failure(STORAGE_UNAVAILABLE);
        return new MiniappDemoAdminOrderView(order.id(), order.orderNo(), order.source(), order.simulation(),
                order.currency(), order.totalAmount(), order.orderState(), order.paymentState(), order.refundState(),
                order.printState(), order.version(), order.principalId(), order.createdAtMs(), order.updatedAtMs(),
                new MiniappDemoAdminOrderView.Item(item.id(), item.quoteId(), item.fileId(), item.fileSha256(),
                        item.amount(), snapshot), payment, printTask, events);
    }

    private MiniappDemoQuoteSnapshot read(String json) {
        try { return mapper.readValue(json, MiniappDemoQuoteSnapshot.class); }
        catch (JsonProcessingException ex) { throw failure(SNAPSHOT_INVALID); }
    }
    private static MiniappDemoAdminOrderSummary summary(MiniappDemoAdminOrderRepository.Order row) {
        requireOrder(row);
        return new MiniappDemoAdminOrderSummary(row.id(), row.orderNo(), row.source(), row.simulation(), row.currency(),
                row.totalAmount(), row.orderState(), row.paymentState(), row.refundState(), row.printState(),
                row.version(), row.principalId(), row.createdAtMs(), row.updatedAtMs());
    }
    private static void requireOrder(MiniappDemoAdminOrderRepository.Order row) {
        if (!"MINIAPP_DEMO".equals(row.source()) || !row.simulation() || !"CNY".equals(row.currency())) {
            throw failure(STORAGE_UNAVAILABLE);
        }
    }
    private static boolean uuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    private static MiniappDemoPrintTaskException failure(MiniappDemoPrintTaskException.Reason reason) {
        return new MiniappDemoPrintTaskException(reason);
    }
}
