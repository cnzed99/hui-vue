package cn.iocoder.yudao.server.print.miniappdemo.order;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionRepository;
import cn.iocoder.yudao.server.print.miniappdemo.quote.MiniappDemoQuoteSnapshot;
import cn.iocoder.yudao.server.print.miniappdemo.quote.MiniappDemoQuoteVerifier;
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
import java.util.List;

import static cn.iocoder.yudao.server.print.miniappdemo.order.MiniappDemoPaymentException.Reason.*;

@Service
public class MiniappDemoPaymentService {
    private final MiniappDemoPaymentProperties properties;
    private final MiniappDemoOrderRepository orders;
    private final MiniappDemoPaymentRepository payments;
    private final MiniappDemoSessionRepository sessions;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public MiniappDemoPaymentService(MiniappDemoPaymentProperties properties, MiniappDemoOrderRepository orders,
            MiniappDemoPaymentRepository payments, MiniappDemoSessionRepository sessions, ObjectMapper mapper,
            PlatformTransactionManager manager) {
        this.properties = properties;
        this.orders = orders;
        this.payments = payments;
        this.sessions = sessions;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public MiniappDemoPaymentView create(MiniappDemoIdentity identity, JsonNode body, String idempotencyKey) {
        properties.requireEnabled();
        if (identity == null || body == null || !body.isObject() || body.size() != 2
                || !body.has("orderId") || !body.get("orderId").isTextual()
                || !uuid(body.get("orderId").textValue()) || !body.has("mode")
                || !body.get("mode").isTextual() || !"DEMO".equals(body.get("mode").textValue())
                || idempotencyKey == null || !idempotencyKey.matches("[!-~]{1,128}")) throw failure(INVALID_INPUT);
        String orderId = body.get("orderId").textValue();
        String keyHash = hash(idempotencyKey);
        String inputHash = hash("payment-v1\n" + orderId + "\nDEMO");
        return transaction.execute(status -> {
            var locked = sessions.lockIdentity(identity).orElseThrow(MiniappDemoPaymentService::sessionInvalid);
            long now = System.currentTimeMillis();
            if (!locked.activeAt(now)) throw sessionInvalid();
            var order = orders.ownedForUpdate(identity, orderId).orElseThrow(() -> failure(ORDER_NOT_FOUND));
            var existing = payments.idempotentForUpdate(identity, keyHash);
            if (existing.isPresent()) {
                if (!existing.get().inputHash().equals(inputHash)) throw failure(IDEMPOTENCY_CONFLICT);
                if (!locked.activeAt(System.currentTimeMillis())) throw sessionInvalid();
                return receiptView(identity, order, existing.get());
            }
            requireInitialOrder(order);
            var item = payments.itemForUpdate(identity, orderId).orElseThrow(() -> failure(STORAGE_UNAVAILABLE));
            MiniappDemoQuoteSnapshot snapshot = readSnapshot(item.snapshotJson());
            MiniappDemoQuoteVerifier.Verified verified = verifySnapshot(snapshot);
            var file = orders.fileForUpdate(identity, item.fileId()).orElseThrow(() -> failure(FILE_NOT_FOUND));
            now = System.currentTimeMillis();
            if (!locked.activeAt(now)) throw sessionInvalid();
            requireInitialOrder(order);
            requirePaymentEvidence(order, item, snapshot, verified, file, now);
            String receiptId = payments.insertEvidence(identity, order, keyHash, inputHash, now);
            if (!payments.markPaid(identity, order, now)) throw failure(STORAGE_UNAVAILABLE);
            var receipt = payments.byOrder(identity, orderId).filter(row -> row.id().equals(receiptId))
                    .orElseThrow(() -> failure(STORAGE_UNAVAILABLE));
            return receiptView(identity, order, receipt);
        });
    }

    public MiniappDemoPaymentView get(MiniappDemoIdentity identity, String orderId) {
        properties.requireEnabled();
        if (identity == null || !uuid(orderId)) throw failure(INVALID_INPUT);
        var order = orders.owned(identity, orderId).orElseThrow(() -> failure(ORDER_NOT_FOUND));
        var receipt = payments.byOrder(identity, orderId).orElseThrow(() -> failure(RECEIPT_NOT_FOUND));
        return receiptView(identity, order, receipt);
    }

    private MiniappDemoPaymentView receiptView(MiniappDemoIdentity identity, MiniappDemoOrderRepository.Order order,
                                                MiniappDemoPaymentRepository.Receipt receipt) {
        if (!receipt.orderId().equals(order.id()) || !"SIMULATED".equals(receipt.provider())
                || !"DEMO".equals(receipt.mode()) || !"SUCCEEDED".equals(receipt.state())
                || !receipt.simulation() || !receipt.currency().equals(order.currency())
                || !receipt.amount().equals(order.totalAmount())) throw failure(STORAGE_UNAVAILABLE);
        List<MiniappDemoPaymentRepository.Journal> journals = payments.journals(identity, receipt.id());
        if (journals.size() != 2) throw failure(STORAGE_UNAVAILABLE);
        MiniappDemoPaymentRepository.Journal debit = journal(journals, "DEBIT", "DEMO_CUSTOMER", receipt);
        MiniappDemoPaymentRepository.Journal credit = journal(journals, "CREDIT", "DEMO_STORE", receipt);
        return new MiniappDemoPaymentView(receipt.id(), receipt.orderId(), receipt.provider(), receipt.mode(),
                receipt.state(), receipt.simulation(), receipt.currency(), receipt.amount(), receipt.createdAtMs(),
                view(debit), view(credit));
    }

    private static MiniappDemoPaymentRepository.Journal journal(List<MiniappDemoPaymentRepository.Journal> rows,
            String side, String account, MiniappDemoPaymentRepository.Receipt receipt) {
        var matches = rows.stream().filter(row -> side.equals(row.side())).toList();
        if (matches.size() != 1) throw failure(STORAGE_UNAVAILABLE);
        var row = matches.get(0);
        if (!receipt.orderId().equals(row.orderId()) || !account.equals(row.account())
                || !receipt.currency().equals(row.currency())
                || !receipt.amount().equals(row.amount())) throw failure(STORAGE_UNAVAILABLE);
        return row;
    }

    private static MiniappDemoPaymentView.Journal view(MiniappDemoPaymentRepository.Journal row) {
        return new MiniappDemoPaymentView.Journal(row.side(), row.account(), row.currency(), row.amount());
    }

    private static void requireInitialOrder(MiniappDemoOrderRepository.Order order) {
        if ("SUCCEEDED".equals(order.paymentState()) || order.version() >= 2) throw failure(ALREADY_PAID);
        if (!"MINIAPP_DEMO".equals(order.source()) || !order.simulation() || !"CNY".equals(order.currency())
                || !"OPEN".equals(order.orderState()) || !"UNPAID".equals(order.paymentState())
                || !"NONE".equals(order.refundState()) || !"NOT_CREATED".equals(order.printState())
                || order.version() != 1) throw failure(INVALID_ORDER_STATE);
    }

    private static void requirePaymentEvidence(MiniappDemoOrderRepository.Order order,
            MiniappDemoOrderRepository.Item item, MiniappDemoQuoteSnapshot snapshot,
            MiniappDemoQuoteVerifier.Verified verified, MiniappDemoOrderRepository.File file, long now) {
        if (!order.currency().equals(verified.currency()) || !order.totalAmount().equals(verified.totalAmount())
                || !item.amount().equals(verified.totalAmount()) || !item.fileId().equals(snapshot.fileId())
                || !item.fileSha256().equals(snapshot.fileSha256()) || !file.id().equals(snapshot.fileId())
                || !file.sha256().equals(snapshot.fileSha256()) || file.pages() == null
                || file.pages() != snapshot.pagesPerCopy() || !"READY".equals(file.status())
                || file.expiresAtMs() <= now) throw failure(FILE_NOT_READY);
    }

    private MiniappDemoQuoteSnapshot readSnapshot(String json) {
        try {
            return mapper.readValue(json, MiniappDemoQuoteSnapshot.class);
        } catch (JsonProcessingException ex) {
            throw failure(SNAPSHOT_INVALID);
        }
    }

    private static MiniappDemoQuoteVerifier.Verified verifySnapshot(MiniappDemoQuoteSnapshot snapshot) {
        try {
            return MiniappDemoQuoteVerifier.verify(snapshot);
        } catch (IllegalArgumentException ex) {
            throw failure(SNAPSHOT_INVALID);
        }
    }

    private static boolean uuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }
    private static MiniappDemoSessionException sessionInvalid() {
        return new MiniappDemoSessionException(MiniappDemoSessionException.Reason.SESSION_INVALID);
    }
    private static MiniappDemoPaymentException failure(MiniappDemoPaymentException.Reason reason) {
        return new MiniappDemoPaymentException(reason);
    }
}
