package cn.iocoder.yudao.server.print.miniappdemo.quote;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionRepository;
import cn.iocoder.yudao.server.print.pricing.PaperPriceSchedule;
import cn.iocoder.yudao.server.print.pricing.PaperPrintRequest;
import cn.iocoder.yudao.server.print.pricing.PaperQuote;
import cn.iocoder.yudao.server.print.pricing.PaperQuoteCalculator;
import cn.iocoder.yudao.server.print.pricing.PrintSide;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import static cn.iocoder.yudao.server.print.miniappdemo.quote.MiniappDemoQuoteException.Reason.*;

@Service
public class MiniappDemoQuoteService {
    private static final Set<String> INPUT_FIELDS = Set.of("fileId", "paper", "color", "copies", "side");
    private static final Set<String> TIER_FIELDS = Set.of("strictlyMoreThanSheets", "simplexPricePerSheet", "duplexPricePerSheet");
    private static final String PAPER = "A4";
    private static final String COLOR = "BLACK_WHITE";
    private static final String AGGREGATION = "PER_FILE_ALL_COPIES";
    private final MiniappDemoQuoteProperties properties;
    private final MiniappDemoQuoteRepository repository;
    private final MiniappDemoSessionRepository sessions;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final PaperQuoteCalculator calculator = new PaperQuoteCalculator();

    private record Input(String fileId, String paper, String color, long copies, PrintSide side) {}

    public MiniappDemoQuoteService(MiniappDemoQuoteProperties properties, MiniappDemoQuoteRepository repository,
            MiniappDemoSessionRepository sessions, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.properties = properties;
        this.repository = repository;
        this.sessions = sessions;
        this.mapper = mapper;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public MiniappDemoQuoteView create(MiniappDemoIdentity identity, JsonNode body, String idempotencyKey) {
        long lifetime = properties.requireLifetimeMillis(System.currentTimeMillis());
        Input input = parse(body);
        if (identity == null || idempotencyKey == null || !idempotencyKey.matches("[!-~]{1,128}")) {
            throw failure(INVALID_INPUT);
        }
        String profileId = properties.getPriceProfileId();
        String keyHash = hash(idempotencyKey);
        String inputHash = hash("quote-v1\n" + input.fileId() + "\n" + input.paper() + "\n" + input.color()
                + "\n" + input.copies() + "\n" + input.side().name() + "\n" + profileId);

        return transaction.execute(status -> {
            var locked = sessions.lockIdentity(identity).orElseThrow(MiniappDemoQuoteService::sessionInvalid);
            long now = System.currentTimeMillis();
            if (!locked.activeAt(now)) throw sessionInvalid();

            var existing = repository.idempotentForUpdate(identity, keyHash);
            if (existing.isPresent() && !existing.get().inputHash().equals(inputHash)) {
                throw failure(IDEMPOTENCY_CONFLICT);
            }
            if (existing.isPresent() && existing.get().expiresAtMs() <= System.currentTimeMillis()) {
                throw failure(QUOTE_EXPIRED);
            }
            MiniappDemoQuoteRepository.File file = requireFile(identity, input.fileId());
            MiniappDemoQuoteRepository.Profile profile = repository.profileForUpdate(identity, profileId)
                    .orElseThrow(() -> failure(PROFILE_UNAVAILABLE));
            PaperPriceSchedule schedule = schedule(profile);
            now = System.currentTimeMillis();
            if (!locked.activeAt(now)) throw sessionInvalid();
            requireReady(file, now);

            if (existing.isPresent()) {
                MiniappDemoQuoteRepository.Quote row = existing.get();
                if (!row.profileId().equals(profileId)) throw failure(STORAGE_UNAVAILABLE);
                if (row.expiresAtMs() <= now) throw failure(QUOTE_EXPIRED);
                return view(row, now);
            }
            if (repository.activeCount(identity, now) >= properties.getMaxActiveQuotesPerPrincipal()) {
                throw failure(PRINCIPAL_LIMIT);
            }
            PaperQuote calculation;
            try {
                calculation = calculator.quote(new PaperPrintRequest(file.pages(), input.copies(), input.side()), schedule);
            } catch (IllegalArgumentException ex) {
                throw failure(INVALID_INPUT);
            }
            long ttlExpiry;
            try {
                ttlExpiry = Math.addExact(now, lifetime);
            } catch (ArithmeticException ex) {
                throw failure(CONFIGURATION_INVALID);
            }
            long expiresAt = Math.min(ttlExpiry, Math.min(file.expiresAtMs(), locked.expiresAtMs()));
            if (expiresAt <= now) throw failure(FILE_NOT_READY);
            PaperPriceSchedule.SingleTier tier = schedule.tier();
            MiniappDemoQuoteSnapshot.Tier tierSnapshot = tier == null ? null : new MiniappDemoQuoteSnapshot.Tier(
                    Long.toString(tier.strictlyMoreThanSheets()), tier.simplexPricePerSheet().toPlainString(),
                    tier.duplexPricePerSheet().toPlainString());
            MiniappDemoQuoteSnapshot snapshot = new MiniappDemoQuoteSnapshot(1, "DEMO", profile.currency(), AGGREGATION,
                    file.id(), file.sha256(), PAPER, COLOR, profile.id(), profile.key(), profile.version(), file.pages(),
                    input.side().name(), Long.toString(input.copies()), Long.toString(calculation.sheetsPerCopy()),
                    Long.toString(calculation.totalSheets()), schedule.simplexPricePerSheet().toPlainString(),
                    schedule.duplexPricePerSheet().toPlainString(), tierSnapshot,
                    calculation.pricePerSheet().toPlainString(), calculation.totalAmount().toPlainString(),
                    calculation.tierApplied(), calculation.appliedTierThreshold() == null ? null
                    : calculation.appliedTierThreshold().toString(), now, expiresAt);
            String snapshotJson;
            try {
                snapshotJson = mapper.writeValueAsString(snapshot);
            } catch (JsonProcessingException ex) {
                throw failure(STORAGE_UNAVAILABLE);
            }
            String id = UUID.randomUUID().toString();
            repository.insert(identity, id, file.id(), profile.id(), keyHash, inputHash, snapshotJson, now, expiresAt);
            return new MiniappDemoQuoteView(id, "VALID", snapshot);
        });
    }

    public MiniappDemoQuoteView get(MiniappDemoIdentity identity, String id) {
        properties.requireLifetimeMillis(System.currentTimeMillis());
        if (identity == null || !isUuid(id)) throw failure(INVALID_INPUT);
        MiniappDemoQuoteRepository.Quote quote = repository.owned(identity, id)
                .orElseThrow(() -> failure(QUOTE_NOT_FOUND));
        return view(quote, System.currentTimeMillis());
    }

    private MiniappDemoQuoteView view(MiniappDemoQuoteRepository.Quote row, long now) {
        try {
            MiniappDemoQuoteSnapshot snapshot = mapper.readValue(row.snapshotJson(), MiniappDemoQuoteSnapshot.class);
            if (snapshot == null || snapshot.schemaVersion() != 1 || !"DEMO".equals(snapshot.mode())
                    || !"CNY".equals(snapshot.currency()) || !AGGREGATION.equals(snapshot.aggregationPolicy())
                    || !row.fileId().equals(snapshot.fileId()) || !row.profileId().equals(snapshot.priceProfileId())
                    || !PAPER.equals(snapshot.paper()) || !COLOR.equals(snapshot.color())
                    || snapshot.createdAtMs() != row.createdAtMs() || snapshot.expiresAtMs() != row.expiresAtMs()) {
                throw failure(STORAGE_UNAVAILABLE);
            }
            return new MiniappDemoQuoteView(row.id(), row.expiresAtMs() > now ? "VALID" : "EXPIRED", snapshot);
        } catch (JsonProcessingException ex) {
            throw failure(STORAGE_UNAVAILABLE);
        }
    }

    private MiniappDemoQuoteRepository.File requireFile(MiniappDemoIdentity identity, String id) {
        return repository.fileForUpdate(identity, id).orElseThrow(() -> failure(FILE_NOT_FOUND));
    }

    private static void requireReady(MiniappDemoQuoteRepository.File file, long now) {
        if (!"READY".equals(file.status()) || file.expiresAtMs() <= now || file.pages() == null
                || file.pages() <= 0 || file.pages() > 20) throw failure(FILE_NOT_READY);
    }

    private Input parse(JsonNode body) {
        if (!exactFields(body, INPUT_FIELDS) || !body.get("fileId").isTextual()
                || !body.get("paper").isTextual() || !body.get("color").isTextual()
                || !body.get("side").isTextual() || !body.get("copies").isIntegralNumber()
                || !body.get("copies").canConvertToLong()) throw failure(INVALID_INPUT);
        String fileId = body.get("fileId").textValue();
        String paper = body.get("paper").textValue();
        String color = body.get("color").textValue();
        long copies = body.get("copies").longValue();
        if (!isUuid(fileId) || !PAPER.equals(paper) || !COLOR.equals(color) || copies < 1 || copies > 99) {
            throw failure(INVALID_INPUT);
        }
        PrintSide side;
        try {
            side = PrintSide.valueOf(body.get("side").textValue());
        } catch (IllegalArgumentException ex) {
            throw failure(INVALID_INPUT);
        }
        return new Input(fileId, paper, color, copies, side);
    }

    private PaperPriceSchedule schedule(MiniappDemoQuoteRepository.Profile profile) {
        if (!"DEMO".equals(profile.mode()) || !"CNY".equals(profile.currency())) throw failure(PROFILE_UNAVAILABLE);
        BigDecimal simplex = price(profile.simplex());
        BigDecimal duplex = price(profile.duplex());
        PaperPriceSchedule.SingleTier tier = null;
        if (profile.tierJson() != null) {
            JsonNode node;
            try {
                node = mapper.readTree(profile.tierJson());
            } catch (JsonProcessingException ex) {
                throw failure(PROFILE_UNAVAILABLE);
            }
            if (!exactFields(node, TIER_FIELDS) || !node.get("strictlyMoreThanSheets").isIntegralNumber()
                    || !node.get("strictlyMoreThanSheets").canConvertToLong()
                    || node.get("strictlyMoreThanSheets").longValue() < 0
                    || !node.get("simplexPricePerSheet").isTextual()
                    || !node.get("duplexPricePerSheet").isTextual()) throw failure(PROFILE_UNAVAILABLE);
            tier = new PaperPriceSchedule.SingleTier(node.get("strictlyMoreThanSheets").longValue(),
                    price(node.get("simplexPricePerSheet").textValue()),
                    price(node.get("duplexPricePerSheet").textValue()));
        }
        try {
            return new PaperPriceSchedule(profile.version(), simplex, duplex, tier);
        } catch (IllegalArgumentException ex) {
            throw failure(PROFILE_UNAVAILABLE);
        }
    }

    private static BigDecimal price(String value) {
        if (value == null || value.length() > 64 || !value.matches("(0|[1-9][0-9]*)(\\.[0-9]+)?")) {
            throw failure(PROFILE_UNAVAILABLE);
        }
        return new BigDecimal(value);
    }

    private static boolean exactFields(JsonNode node, Set<String> names) {
        if (node == null || !node.isObject() || node.size() != names.size()) return false;
        var fields = node.fieldNames();
        while (fields.hasNext()) if (!names.contains(fields.next())) return false;
        return true;
    }

    private static boolean isUuid(String value) {
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

    private static MiniappDemoQuoteException failure(MiniappDemoQuoteException.Reason reason) {
        return new MiniappDemoQuoteException(reason);
    }
}
