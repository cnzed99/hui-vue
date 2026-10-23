package cn.iocoder.yudao.server.print.quote;

import cn.iocoder.yudao.server.print.pricing.*;
import cn.iocoder.yudao.server.print.scope.PrintScope;
import cn.iocoder.yudao.server.print.scope.PrintScopeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import static cn.iocoder.yudao.server.print.quote.PrintQuoteException.Reason.*;

@Service
public class PrintQuoteService {
    private static final Set<String> INPUT_FIELDS=Set.of("fileId","priceProfileId","copies","side");
    private static final Set<String> TIER_FIELDS=Set.of("strictlyMoreThanSheets","simplexPricePerSheet","duplexPricePerSheet");
    private static final String AGGREGATION="PER_FILE_ALL_COPIES";
    private final PrintQuoteProperties properties;
    private final PrintScopeService scopes;
    private final PrintQuoteRepository repository;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    private final PaperQuoteCalculator calculator=new PaperQuoteCalculator();
    private record Input(String fileId,String profileId,long copies,PrintSide side) { }
    public PrintQuoteService(PrintQuoteProperties properties, PrintScopeService scopes,
            PrintQuoteRepository repository, ObjectMapper mapper, PlatformTransactionManager manager) {
        this.properties=properties;this.scopes=scopes;this.repository=repository;this.mapper=mapper;
        this.transaction=new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public PrintQuoteView create(JsonNode body, String key) {
        properties.requireLifetimeMillis(System.currentTimeMillis());
        Input input=parse(body);
        if (key==null || !key.matches("[!-~]{1,128}")) throw failure(INVALID_INPUT);
        String keyHash=hash(key);
        // Canonical typed values, not JSON field order or number rendering, define request identity.
        String inputHash=hash("quote-v1\n"+input.fileId()+"\n"+input.profileId()+"\n"+input.copies()+"\n"+input.side().name());
        return transaction.execute(status -> {
            PrintScope scope=scopes.requireCurrent();
            repository.serializeOwner(scope);
            long now=System.currentTimeMillis();
            long lifetime=properties.requireLifetimeMillis(now);
            var existing=repository.idempotent(scope,keyHash);
            if (existing.isPresent()) {
                var row=existing.get();
                if (!row.inputHash().equals(inputHash)) throw failure(IDEMPOTENCY_CONFLICT);
                if (row.expiresAtMs()<=now) throw failure(QUOTE_EXPIRED);
                requireFile(scope,input.fileId());
                now=System.currentTimeMillis();
                if (row.expiresAtMs()<=now) throw failure(QUOTE_EXPIRED);
                return view(row,now);
            }
            var file=requireFile(scope,input.fileId());
            var profile=repository.profile(scope,input.profileId()).orElseThrow(() -> failure(PROFILE_NOT_FOUND));
            PaperPriceSchedule schedule=schedule(profile);
            PaperQuote calculation;
            try { calculation=calculator.quote(new PaperPrintRequest(file.pages(),input.copies(),input.side()),schedule); }
            catch (IllegalArgumentException ex) { throw failure(INVALID_INPUT); }
            now=System.currentTimeMillis();
            lifetime=properties.requireLifetimeMillis(now);
            if (file.expiresAtMs()<=now) throw failure(FILE_NOT_READY);
            long expires=Math.min(Math.addExact(now,lifetime),file.expiresAtMs());
            var tier=schedule.tier();
            var tierSnapshot=tier==null ? null : new PrintQuoteSnapshot.Tier(Long.toString(tier.strictlyMoreThanSheets()),
                    tier.simplexPricePerSheet().toPlainString(),tier.duplexPricePerSheet().toPlainString());
            var snapshot=new PrintQuoteSnapshot(1,"DEMO",profile.currency(),AGGREGATION,file.id(),file.sha256(),
                    profile.id(),profile.key(),profile.version(),file.pages(),input.side().name(),Long.toString(input.copies()),
                    Long.toString(calculation.sheetsPerCopy()),Long.toString(calculation.totalSheets()),
                    schedule.simplexPricePerSheet().toPlainString(),schedule.duplexPricePerSheet().toPlainString(),tierSnapshot,
                    calculation.pricePerSheet().toPlainString(),calculation.totalAmount().toPlainString(),calculation.tierApplied(),
                    calculation.appliedTierThreshold()==null ? null : calculation.appliedTierThreshold().toString(),now,expires);
            String json;
            try { json=mapper.writeValueAsString(snapshot); }
            catch (JsonProcessingException ex) { throw failure(STORAGE_UNAVAILABLE); }
            String id=UUID.randomUUID().toString();
            repository.insert(scope,id,file.id(),profile.id(),keyHash,inputHash,json,now,expires);
            return new PrintQuoteView(id,"VALID",snapshot);
        });
    }
    public PrintQuoteView get(String id) {
        properties.requireLifetimeMillis(System.currentTimeMillis());
        if (!isUuid(id)) throw failure(INVALID_INPUT);
        PrintScope scope=scopes.requireCurrent();
        var quote=repository.owned(scope,id).orElseThrow(() -> failure(QUOTE_NOT_FOUND));
        return view(quote,System.currentTimeMillis());
    }
    private PrintQuoteView view(PrintQuoteRepository.Quote row,long now) {
        try {
            PrintQuoteSnapshot snapshot=mapper.readValue(row.json(),PrintQuoteSnapshot.class);
            if (snapshot==null || snapshot.schemaVersion()!=1 || snapshot.expiresAtMs()!=row.expiresAtMs())
                throw failure(STORAGE_UNAVAILABLE);
            return new PrintQuoteView(row.id(),row.expiresAtMs()>now ? "VALID" : "EXPIRED",snapshot);
        } catch (JsonProcessingException ex) { throw failure(STORAGE_UNAVAILABLE); }
    }
    private PrintQuoteRepository.File requireFile(PrintScope scope,String id) {
        var file=repository.fileForUpdate(scope,id).orElseThrow(() -> failure(FILE_NOT_FOUND));
        if (!"READY".equals(file.status()) || file.expiresAtMs()<=System.currentTimeMillis() || file.pages()==null || file.pages()<=0)
            throw failure(FILE_NOT_READY);
        return file;
    }
    private Input parse(JsonNode body) {
        if (!exactFields(body,INPUT_FIELDS) || !body.get("fileId").isTextual() || !body.get("priceProfileId").isTextual()
                || !body.get("side").isTextual() || !body.get("copies").isIntegralNumber()
                || !body.get("copies").canConvertToLong() || body.get("copies").longValue()<=0)
            throw failure(INVALID_INPUT);
        String file=body.get("fileId").textValue(),profile=body.get("priceProfileId").textValue();
        if (!isUuid(file) || !isUuid(profile)) throw failure(INVALID_INPUT);
        PrintSide side;
        try { side=PrintSide.valueOf(body.get("side").textValue()); }
        catch (IllegalArgumentException ex) { throw failure(INVALID_INPUT); }
        return new Input(file,profile,body.get("copies").longValue(),side);
    }
    private PaperPriceSchedule schedule(PrintQuoteRepository.Profile profile) {
        if (!"DEMO".equals(profile.mode()) || !"CNY".equals(profile.currency())) throw failure(PROFILE_UNSUPPORTED);
        BigDecimal simplex=price(profile.simplex()),duplex=price(profile.duplex());
        PaperPriceSchedule.SingleTier tier=null;
        if (profile.tierJson()!=null) {
            JsonNode node;
            try { node=mapper.readTree(profile.tierJson()); }
            catch (JsonProcessingException ex) { throw failure(PROFILE_UNSUPPORTED); }
            if (!exactFields(node,TIER_FIELDS) || !node.get("strictlyMoreThanSheets").isIntegralNumber()
                    || !node.get("strictlyMoreThanSheets").canConvertToLong() || node.get("strictlyMoreThanSheets").longValue()<0
                    || !node.get("simplexPricePerSheet").isTextual() || !node.get("duplexPricePerSheet").isTextual())
                throw failure(PROFILE_UNSUPPORTED);
            tier=new PaperPriceSchedule.SingleTier(node.get("strictlyMoreThanSheets").longValue(),
                    price(node.get("simplexPricePerSheet").textValue()),price(node.get("duplexPricePerSheet").textValue()));
        }
        try { return new PaperPriceSchedule(profile.version(),simplex,duplex,tier); }
        catch (IllegalArgumentException ex) { throw failure(PROFILE_UNSUPPORTED); }
    }
    private static BigDecimal price(String value) {
        // Precision is bounded by input length, never silently rounded or forced to two decimals.
        if (value==null || value.length()>64 || !value.matches("(0|[1-9][0-9]*)(\\.[0-9]+)?"))
            throw failure(PROFILE_UNSUPPORTED);
        return new BigDecimal(value);
    }
    private static boolean exactFields(JsonNode node,Set<String> names) {
        if (node==null || !node.isObject() || node.size()!=names.size()) return false;
        var iterator=node.fieldNames();
        while(iterator.hasNext()) if(!names.contains(iterator.next())) return false;
        return true;
    }
    private static boolean isUuid(String value) {
        return value!=null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static PrintQuoteException failure(PrintQuoteException.Reason reason) { return new PrintQuoteException(reason); }
}
