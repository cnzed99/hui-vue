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
import java.util.Locale;
import java.util.UUID;

import static cn.iocoder.yudao.server.print.miniappdemo.order.MiniappDemoOrderException.Reason.*;

@Service
public class MiniappDemoOrderService {
    private final MiniappDemoOrderProperties properties;
    private final MiniappDemoOrderRepository repository;
    private final MiniappDemoSessionRepository sessions;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;

    public MiniappDemoOrderService(MiniappDemoOrderProperties properties, MiniappDemoOrderRepository repository,
            MiniappDemoSessionRepository sessions, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.properties = properties;
        this.repository = repository;
        this.sessions = sessions;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public MiniappDemoOrderView create(MiniappDemoIdentity identity, JsonNode body, String idempotencyKey) {
        properties.requireEnabled();
        if (identity == null || body == null || !body.isObject() || body.size() != 1
                || !body.has("quoteId") || !body.get("quoteId").isTextual()
                || !uuid(body.get("quoteId").textValue()) || idempotencyKey == null
                || !idempotencyKey.matches("[!-~]{1,128}")) throw failure(INVALID_INPUT);
        String quoteId = body.get("quoteId").textValue();
        String keyHash = hash(idempotencyKey);
        String inputHash = hash("order-v1\n" + quoteId);
        return transaction.execute(status -> {
            var locked = sessions.lockIdentity(identity).orElseThrow(MiniappDemoOrderService::sessionInvalid);
            long now = System.currentTimeMillis();
            if (!locked.activeAt(now)) throw sessionInvalid();
            var existing = repository.idempotentForUpdate(identity, keyHash);
            if (existing.isPresent()) {
                if (!existing.get().inputHash().equals(inputHash)) throw failure(IDEMPOTENCY_CONFLICT);
                if (!locked.activeAt(System.currentTimeMillis())) throw sessionInvalid();
                return detail(identity, existing.get());
            }
            var quote = repository.quoteForUpdate(identity, quoteId).orElseThrow(() -> failure(QUOTE_NOT_FOUND));
            if (repository.consumed(identity, quoteId)) throw failure(QUOTE_CONSUMED);
            if (quote.expiresAtMs() <= System.currentTimeMillis()) throw failure(QUOTE_EXPIRED);
            MiniappDemoQuoteSnapshot snapshot = readSnapshot(quote.snapshotJson());
            verifyQuoteRow(snapshot, quote);
            MiniappDemoQuoteVerifier.Verified verified = verifySnapshot(snapshot);
            var file = repository.fileForUpdate(identity, quote.fileId()).orElseThrow(() -> failure(FILE_NOT_FOUND));
            now = System.currentTimeMillis();
            if (!locked.activeAt(now)) throw sessionInvalid();
            if (quote.expiresAtMs() <= now) throw failure(QUOTE_EXPIRED);
            requireFile(snapshot, file, now);
            if (repository.count(identity) >= properties.getMaxOrdersPerPrincipal()) throw failure(PRINCIPAL_LIMIT);
            String id = UUID.randomUUID().toString();
            String orderNo = "PM" + id.replace("-", "").toUpperCase(Locale.ROOT);
            repository.insert(identity, id, orderNo, quote, file, quote.snapshotJson(), verified.totalAmount(),
                    verified.currency(), keyHash, inputHash, now);
            return detail(identity, repository.owned(identity, id).orElseThrow(() -> failure(STORAGE_UNAVAILABLE)));
        });
    }

    public MiniappDemoOrderView get(MiniappDemoIdentity identity, String id) {
        properties.requireEnabled();
        if (identity == null || !uuid(id)) throw failure(INVALID_INPUT);
        return detail(identity, repository.owned(identity, id).orElseThrow(() -> failure(ORDER_NOT_FOUND)));
    }

    private MiniappDemoOrderView detail(MiniappDemoIdentity identity, MiniappDemoOrderRepository.Order order) {
        MiniappDemoOrderRepository.Item item = repository.item(identity, order.id())
                .orElseThrow(() -> failure(STORAGE_UNAVAILABLE));
        MiniappDemoQuoteSnapshot snapshot = readSnapshot(item.snapshotJson());
        MiniappDemoQuoteVerifier.Verified verified = verifySnapshot(snapshot);
        if (!"MINIAPP_DEMO".equals(order.source()) || !order.simulation() || !"CNY".equals(order.currency())
                || !order.totalAmount().equals(item.amount()) || !item.amount().equals(verified.totalAmount())
                || !item.fileId().equals(snapshot.fileId()) || !item.fileSha256().equals(snapshot.fileSha256())) {
            throw failure(STORAGE_UNAVAILABLE);
        }
        return new MiniappDemoOrderView(order.id(), order.orderNo(), order.source(), order.simulation(),
                order.currency(), order.totalAmount(), order.orderState(), order.paymentState(), order.refundState(),
                order.printState(), order.version(), order.createdAtMs(), order.updatedAtMs(),
                new MiniappDemoOrderView.Item(item.id(), item.quoteId(), item.fileId(), item.fileSha256(),
                        item.amount(), snapshot));
    }

    private MiniappDemoQuoteSnapshot readSnapshot(String json) {
        try {
            return mapper.readValue(json, MiniappDemoQuoteSnapshot.class);
        } catch (JsonProcessingException ex) {
            throw failure(SNAPSHOT_INVALID);
        }
    }

    private MiniappDemoQuoteVerifier.Verified verifySnapshot(MiniappDemoQuoteSnapshot snapshot) {
        try {
            return MiniappDemoQuoteVerifier.verify(snapshot);
        } catch (IllegalArgumentException ex) {
            throw failure(SNAPSHOT_INVALID);
        }
    }

    private static void verifyQuoteRow(MiniappDemoQuoteSnapshot snapshot, MiniappDemoOrderRepository.Quote quote) {
        if (snapshot == null || !quote.fileId().equals(snapshot.fileId())
                || !quote.profileId().equals(snapshot.priceProfileId())
                || quote.createdAtMs() != snapshot.createdAtMs() || quote.expiresAtMs() != snapshot.expiresAtMs()) {
            throw failure(SNAPSHOT_INVALID);
        }
    }

    private static void requireFile(MiniappDemoQuoteSnapshot snapshot, MiniappDemoOrderRepository.File file, long now) {
        if (!"READY".equals(file.status()) || file.expiresAtMs() <= now || file.pages() == null
                || file.pages() <= 0 || !file.id().equals(snapshot.fileId())
                || !file.sha256().equals(snapshot.fileSha256()) || file.pages() != snapshot.pagesPerCopy()) {
            throw failure(FILE_NOT_READY);
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
    private static MiniappDemoOrderException failure(MiniappDemoOrderException.Reason reason) {
        return new MiniappDemoOrderException(reason);
    }
}
