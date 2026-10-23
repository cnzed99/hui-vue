package cn.iocoder.yudao.server.print.order;

import cn.iocoder.yudao.server.print.quote.PrintQuoteSnapshot;
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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import static cn.iocoder.yudao.server.print.order.PrintOrderException.Reason.*;

@Service
public class PrintOrderService {
    private final PrintOrderProperties properties;
    private final PrintScopeService scopes;
    private final PrintOrderRepository repository;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    public PrintOrderService(PrintOrderProperties properties,PrintScopeService scopes,PrintOrderRepository repository,
            ObjectMapper mapper,PlatformTransactionManager manager){
        this.properties=properties;this.scopes=scopes;this.repository=repository;this.mapper=mapper;
        this.transaction=new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public PrintOrderView create(JsonNode body,String key){
        properties.requireEnabled();
        if(body==null || !body.isObject() || body.size()!=1 || !body.has("quoteId") || !body.get("quoteId").isTextual()
                || !isUuid(body.get("quoteId").textValue()) || key==null || !key.matches("[!-~]{1,128}"))throw failure(INVALID_INPUT);
        String quoteId=body.get("quoteId").textValue(),keyHash=hash(key),inputHash=hash("order-v1\n"+quoteId);
        return transaction.execute(status->{
            PrintScope scope=scopes.requireCurrent();
            repository.serializeOwner(scope);
            var existing=repository.idempotent(scope,keyHash);
            if(existing.isPresent()){
                if(!inputHash.equals(existing.get().inputHash()))throw failure(IDEMPOTENCY_CONFLICT);
                // Successful creation is replayed even if the originating quote/file has since expired or been deleted.
                return detail(scope,existing.get().summary());
            }
            var quote=repository.quoteForUpdate(scope,quoteId).orElseThrow(()->failure(QUOTE_NOT_FOUND));
            // Consumption takes precedence on a different key, including after the original quote expires.
            if(repository.consumed(scope,quoteId))throw failure(QUOTE_CONSUMED);
            if(quote.expiresAtMs()<=System.currentTimeMillis())throw failure(QUOTE_EXPIRED);
            var snapshot=readSnapshot(quote.json(),SNAPSHOT_INVALID);
            validateSnapshot(snapshot,quote);
            var file=repository.fileForUpdate(scope,quote.fileId()).orElseThrow(()->failure(FILE_NOT_FOUND));
            long now=System.currentTimeMillis();
            if(quote.expiresAtMs()<=now)throw failure(QUOTE_EXPIRED);
            if(!"READY".equals(file.status()) || file.expiresAtMs()<=now || file.pages()==null || file.pages()<=0)
                throw failure(FILE_NOT_READY);
            if(!file.sha256().equals(snapshot.fileSha256()) || file.pages()!=snapshot.pagesPerCopy()
                    || !file.id().equals(snapshot.fileId()))throw failure(QUOTE_FILE_MISMATCH);
            String id=UUID.randomUUID().toString(),number="PD"+id.replace("-","").toUpperCase(Locale.ROOT);
            repository.insert(scope,id,number,quoteId,quote,file,snapshot.totalAmount(),snapshot.currency(),keyHash,inputHash,now);
            return detail(scope,repository.owned(scope,id).orElseThrow(()->failure(STORAGE_UNAVAILABLE)).summary());
        });
    }
    public PrintOrderView get(String id){
        properties.requireEnabled();requireUuid(id);PrintScope s=scopes.requireCurrent();
        return detail(s,repository.owned(s,id).orElseThrow(()->failure(ORDER_NOT_FOUND)).summary());
    }
    public PrintOrderPage<PrintOrderSummary> list(int pageNo,int pageSize){
        properties.requireEnabled();validatePage(pageNo,pageSize);PrintScope s=scopes.requireCurrent();
        return new PrintOrderPage<>(repository.list(s,pageSize,offset(pageNo,pageSize)),repository.count(s),pageNo,pageSize);
    }
    public PrintOrderPage<PrintOrderEventView> events(String id,int pageNo,int pageSize){
        properties.requireEnabled();requireUuid(id);validatePage(pageNo,pageSize);PrintScope s=scopes.requireCurrent();
        repository.owned(s,id).orElseThrow(()->failure(ORDER_NOT_FOUND));
        return new PrintOrderPage<>(repository.events(s,id,pageSize,offset(pageNo,pageSize)),repository.eventCount(s,id),pageNo,pageSize);
    }
    private PrintOrderView detail(PrintScope s,PrintOrderSummary order){
        List<PrintOrderView.Item> items=repository.items(s,order.id()).stream().map(item->{
            var snapshot=readSnapshot(item.json(),STORAGE_UNAVAILABLE);
            if(!item.amount().equals(snapshot.totalAmount()) || !item.fileId().equals(snapshot.fileId())
                    || !item.fileSha256().equals(snapshot.fileSha256()))throw failure(STORAGE_UNAVAILABLE);
            return new PrintOrderView.Item(item.id(),item.quoteId(),item.fileId(),item.fileSha256(),item.amount(),snapshot);
        }).toList();
        if(items.size()!=1 || !order.totalAmount().equals(items.get(0).amount()))throw failure(STORAGE_UNAVAILABLE);
        return new PrintOrderView(order.id(),order.orderNo(),order.source(),order.simulation(),order.currency(),order.totalAmount(),
                order.orderState(),order.paymentState(),order.refundState(),order.deliveryMode(),order.deliveryState(),order.printState(),
                order.createdByActorType(),order.createdByActorId(),order.createdAtMs(),items);
    }
    private PrintQuoteSnapshot readSnapshot(String json,PrintOrderException.Reason reason){
        try{
            PrintQuoteSnapshot snapshot=mapper.readValue(json,PrintQuoteSnapshot.class);
            if(snapshot==null || snapshot.schemaVersion()!=1)throw failure(reason);
            return snapshot;
        }catch(JsonProcessingException ex){throw failure(reason);}
    }
    private static void validateSnapshot(PrintQuoteSnapshot s,PrintOrderRepository.Quote q){
        if(!"DEMO".equals(s.mode()) || !"CNY".equals(s.currency()) || !"PER_FILE_ALL_COPIES".equals(s.aggregationPolicy())
                || !q.fileId().equals(s.fileId()) || !q.profileId().equals(s.priceProfileId())
                || q.createdAtMs()!=s.createdAtMs() || q.expiresAtMs()!=s.expiresAtMs()
                || s.fileSha256()==null || !s.fileSha256().matches("[0-9a-f]{64}") || s.pagesPerCopy()<=0
                || (!"SIMPLEX".equals(s.side()) && !"DUPLEX".equals(s.side()))
                || !positiveLong(s.copies()) || !positiveLong(s.sheetsPerCopy()) || !positiveLong(s.totalSheets())
                || !decimal(s.totalAmount()) || !decimal(s.pricePerSheet()) || !decimal(s.simplexPricePerSheet()) || !decimal(s.duplexPricePerSheet()))
            throw failure(SNAPSHOT_INVALID);
    }
    private static boolean positiveLong(String value){
        if(value==null || !value.matches("[1-9][0-9]{0,18}"))return false;
        try{return Long.parseLong(value)>0;}catch(NumberFormatException ex){return false;}
    }
    private static boolean decimal(String value){
        return value!=null && value.length()<=128 && value.matches("(0|[1-9][0-9]*)(\\.[0-9]+)?");
    }
    private static void validatePage(int pageNo,int pageSize){
        if(pageNo<1 || pageNo>1_000_000 || pageSize<1 || pageSize>100)throw failure(INVALID_INPUT);
    }
    private static long offset(int pageNo,int pageSize){return ((long)pageNo-1)*pageSize;}
    private static void requireUuid(String id){if(!isUuid(id))throw failure(INVALID_INPUT);}
    private static boolean isUuid(String value){return value!=null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    private static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException("SHA-256 unavailable");}
    }
    private static PrintOrderException failure(PrintOrderException.Reason reason){return new PrintOrderException(reason);}
}
