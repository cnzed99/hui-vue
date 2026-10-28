package cn.iocoder.yudao.server.print.payment;

import cn.iocoder.yudao.server.print.order.PrintOrderProperties;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import static cn.iocoder.yudao.server.print.payment.PrintPaymentException.Reason.*;

@Service
public class PrintPaymentService {
    private final PrintPaymentProperties properties;
    private final PrintOrderProperties orders;
    private final PrintScopeService scopes;
    private final PrintPaymentRepository repository;
    private final ObjectMapper mapper;
    private final TransactionTemplate transaction;
    public PrintPaymentService(PrintPaymentProperties properties,PrintOrderProperties orders,PrintScopeService scopes,
            PrintPaymentRepository repository,ObjectMapper mapper,PlatformTransactionManager manager){
        this.properties=properties;this.orders=orders;this.scopes=scopes;this.repository=repository;this.mapper=mapper;
        transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public PrintPaymentView simulate(String id,JsonNode body,String key){
        orders.requireEnabled();properties.requireWriteEnabled();requireUuid(id);
        if(body==null || !body.isObject() || body.size()!=0 || key==null || !key.matches("[!-~]{1,128}"))throw failure(INVALID_INPUT);
        String keyHash=hash(key),inputHash=hash("simulated-payment-v1\n"+id);
        return transaction.execute(status->{
            PrintScope scope=scopes.requireCurrent();repository.serializeOwner(scope);
            var order=repository.ownedOrder(scope,id,false).orElseThrow(()->failure(ORDER_NOT_FOUND));
            requireSource(scope,order);
            var replay=repository.idempotent(scope,keyHash);
            if(replay.isPresent()){
                if(!id.equals(replay.get().orderId()) || !inputHash.equals(replay.get().inputHash()))throw failure(IDEMPOTENCY_CONFLICT);
                return verifiedView(scope,order,replay.get());
            }
            var prior=repository.receipt(scope,id);
            if(prior.isPresent()){
                verifiedView(scope,order,prior.get());
                throw failure(ALREADY_PAID);
            }
            if("SUCCEEDED".equals(order.paymentState()))throw failure(INTEGRITY_FAILURE);
            requireInitial(order);
            var items=repository.items(scope,id);
            if(items.size()!=1)throw failure(SNAPSHOT_MISMATCH);
            var item=items.get(0);
            var snapshot=snapshot(item.json());
            validateItem(order,item,snapshot);
            // Read location first; actual write locks remain mapping -> file -> order.
            var file=repository.fileForUpdate(scope,item.fileId()).orElseThrow(()->failure(FILE_NOT_FOUND));
            order=repository.ownedOrder(scope,id,true).orElseThrow(()->failure(ORDER_NOT_FOUND));
            requireSource(scope,order);requireInitial(order);validateItem(order,item,snapshot);
            long now=System.currentTimeMillis();
            requireFile(file,item,snapshot,now);
            String receiptId=UUID.randomUUID().toString(),jobId=UUID.randomUUID().toString();
            repository.insertReceipt(scope,receiptId,order,keyHash,inputHash,now);
            repository.insertJournal(scope,receiptId,order,"DEBIT","SIMULATED_EXTERNAL_FUNDS",now);
            repository.insertJournal(scope,receiptId,order,"CREDIT","SIMULATED_ORDER_RECEIPT",now);
            repository.insertJob(scope,jobId,receiptId,order,item,now);
            repository.insertEvent(scope,id,now);
            if(file.expiresAtMs()<=System.currentTimeMillis())throw failure(FILE_NOT_READY);
            if(!repository.markPaid(scope,id))throw failure(ORDER_STATE_INVALID);
            var paid=repository.ownedOrder(scope,id,false).orElseThrow(()->failure(INTEGRITY_FAILURE));
            var receipt=repository.receipt(scope,id).orElseThrow(()->failure(INTEGRITY_FAILURE));
            var result=verifiedView(scope,paid,receipt);
            if(file.expiresAtMs()<=System.currentTimeMillis())throw failure(FILE_NOT_READY);
            return result;
        });
    }
    public PrintPaymentView get(String id){
        // Disabling new simulated payments must not hide existing receipts.
        orders.requireEnabled();requireUuid(id);
        return transaction.execute(status->{
            PrintScope scope=scopes.requireCurrent();repository.serializeOwner(scope);
            var order=repository.ownedOrder(scope,id,false).orElseThrow(()->failure(ORDER_NOT_FOUND));requireSource(scope,order);
            var receipt=repository.receipt(scope,id).orElseThrow(()->failure(RECEIPT_NOT_FOUND));
            return verifiedView(scope,order,receipt);
        });
    }
    private PrintPaymentView verifiedView(PrintScope scope,PrintPaymentRepository.Order order,PrintPaymentRepository.Receipt receipt){
        if(!"SUCCEEDED".equals(order.paymentState()) || order.version()<2 || !receipt.orderId().equals(order.id())
                || !"SIMULATED".equals(receipt.provider()) || !"SUCCEEDED".equals(receipt.state()) || !receipt.simulation()
                || !receipt.amount().equals(order.amount()) || !receipt.currency().equals(order.currency())
                || !"STAFF".equals(receipt.actorType()) || receipt.actorId()!=scope.userId())throw failure(INTEGRITY_FAILURE);
        var entries=repository.journals(scope,receipt.id());
        if(entries.size()!=2)throw failure(INTEGRITY_FAILURE);
        BigDecimal balance=BigDecimal.ZERO;boolean debit=false,credit=false;
        for(var entry:entries){
            if(!entry.simulation() || !entry.orderId().equals(order.id()) || !entry.currency().equals(receipt.currency())
                    || !entry.amount().equals(receipt.amount()) || !decimal(entry.amount()))throw failure(INTEGRITY_FAILURE);
            BigDecimal amount=new BigDecimal(entry.amount());
            if("DEBIT".equals(entry.side()) && "SIMULATED_EXTERNAL_FUNDS".equals(entry.account()) && !debit){debit=true;balance=balance.add(amount);}
            else if("CREDIT".equals(entry.side()) && "SIMULATED_ORDER_RECEIPT".equals(entry.account()) && !credit){credit=true;balance=balance.subtract(amount);}
            else throw failure(INTEGRITY_FAILURE);
        }
        if(!debit || !credit || balance.signum()!=0)throw failure(INTEGRITY_FAILURE);
        var jobs=repository.jobs(scope,receipt.id());var items=repository.items(scope,order.id());
        if(jobs.size()!=1 || items.size()!=1 || repository.paidEventCount(scope,order.id())!=1)throw failure(INTEGRITY_FAILURE);
        var job=jobs.get(0);var item=items.get(0);
        if(!job.simulation() || !"ADMIN_DEMO".equals(job.source()) || !job.orderId().equals(order.id()) || !job.itemId().equals(item.id())
                || !job.fileId().equals(item.fileId()) || !job.sha256().equals(item.sha256()))throw failure(INTEGRITY_FAILURE);
        return new PrintPaymentView(receipt.id(),receipt.orderId(),receipt.provider(),receipt.state(),receipt.simulation(),receipt.currency(),
                receipt.amount(),receipt.actorType(),receipt.actorId(),receipt.createdAtMs(),jobs.stream().map(PrintPaymentRepository.Job::id).toList());
    }
    private PrintQuoteSnapshot snapshot(String json){
        try{var result=mapper.readValue(json,PrintQuoteSnapshot.class);if(result==null)throw failure(SNAPSHOT_MISMATCH);return result;}
        catch(JsonProcessingException ex){throw failure(SNAPSHOT_MISMATCH);}
    }
    private static void validateItem(PrintPaymentRepository.Order order,PrintPaymentRepository.Item item,PrintQuoteSnapshot snapshot){
        if(!decimal(order.amount()) || !order.amount().equals(item.amount()) || !item.amount().equals(snapshot.totalAmount())
                || snapshot.schemaVersion()!=1 || !"DEMO".equals(snapshot.mode()) || !order.currency().equals(snapshot.currency())
                || !"PER_FILE_ALL_COPIES".equals(snapshot.aggregationPolicy()) || !item.fileId().equals(snapshot.fileId())
                || !item.sha256().equals(snapshot.fileSha256()) || snapshot.pagesPerCopy()<=0)throw failure(SNAPSHOT_MISMATCH);
    }
    private static void requireFile(PrintPaymentRepository.File file,PrintPaymentRepository.Item item,PrintQuoteSnapshot snapshot,long now){
        if(!"READY".equals(file.status()) || file.pages()==null || file.pages()<=0 || file.expiresAtMs()<=now)throw failure(FILE_NOT_READY);
        if(!file.id().equals(item.fileId()) || !file.sha256().equals(item.sha256()) || file.pages()!=snapshot.pagesPerCopy())throw failure(SNAPSHOT_MISMATCH);
    }
    private static void requireSource(PrintScope scope,PrintPaymentRepository.Order order){
        if(!"ADMIN_DEMO".equals(order.source()) || !order.simulation() || !"CNY".equals(order.currency())
                || !"STAFF".equals(order.actorType()) || order.actorId()!=scope.userId())throw failure(SOURCE_FORBIDDEN);
    }
    private static void requireInitial(PrintPaymentRepository.Order order){
        if(order.version()!=1 || !"OPEN".equals(order.orderState()) || !"UNPAID".equals(order.paymentState())
                || !"NONE".equals(order.refundState()) || !"NONE".equals(order.deliveryMode())
                || !"NOT_APPLICABLE".equals(order.deliveryState()) || !"NOT_CREATED".equals(order.printState()))throw failure(ORDER_STATE_INVALID);
    }
    private static boolean decimal(String value){return value!=null && value.length()<=128 && value.matches("(0|[1-9][0-9]*)(\\.[0-9]+)?");}
    private static void requireUuid(String value){
        if(value==null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw failure(INVALID_INPUT);
    }
    private static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException ex){throw new IllegalStateException("SHA-256 unavailable");}
    }
    private static PrintPaymentException failure(PrintPaymentException.Reason reason){return new PrintPaymentException(reason);}
}
