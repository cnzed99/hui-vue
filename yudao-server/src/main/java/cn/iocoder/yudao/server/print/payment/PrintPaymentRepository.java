package cn.iocoder.yudao.server.print.payment;
import cn.iocoder.yudao.server.print.scope.PrintScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PrintPaymentRepository {
    record Order(String id,String source,boolean simulation,String currency,String amount,String actorType,long actorId,
                 String orderState,String paymentState,String refundState,String deliveryMode,String deliveryState,String printState,long version) { }
    record Item(String id,String fileId,String sha256,String amount,String json) { }
    record File(String id,String sha256,Integer pages,String status,long expiresAtMs) { }
    record Receipt(String id,String orderId,String provider,String state,boolean simulation,String currency,String amount,
                   String actorType,long actorId,long createdAtMs,String inputHash) { }
    record Journal(String side,String account,String amount,String currency,boolean simulation,String orderId) { }
    record Job(String id,String orderId,String itemId,String fileId,String sha256,boolean simulation,String source) { }
    private static final String OWNER="tenant_id=? AND store_id=? AND owner_user_id=?";
    private static final String ORDER_FIELDS="id,source,simulation,currency,total_amount,created_by_actor_type,created_by_actor_id,"
            +"order_state,payment_state,refund_state,delivery_mode,delivery_state,print_state,version";
    private static final String RECEIPT_FIELDS="id,order_id,provider,state,simulation,currency,amount,actor_type,actor_id,created_at_ms,input_sha256";
    private static final RowMapper<Order> ORDER_MAPPER=(r,n)->new Order(r.getString("id"),r.getString("source"),r.getBoolean("simulation"),
            r.getString("currency"),r.getString("total_amount"),r.getString("created_by_actor_type"),r.getLong("created_by_actor_id"),
            r.getString("order_state"),r.getString("payment_state"),r.getString("refund_state"),r.getString("delivery_mode"),
            r.getString("delivery_state"),r.getString("print_state"),r.getLong("version"));
    private static final RowMapper<Receipt> RECEIPT_MAPPER=(r,n)->new Receipt(r.getString("id"),r.getString("order_id"),r.getString("provider"),
            r.getString("state"),r.getBoolean("simulation"),r.getString("currency"),r.getString("amount"),r.getString("actor_type"),
            r.getLong("actor_id"),r.getLong("created_at_ms"),r.getString("input_sha256"));
    private final JdbcTemplate jdbc;
    public PrintPaymentRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    void serializeOwner(PrintScope s){
        var rows=jdbc.query("SELECT id FROM print_staff_store WHERE tenant_id=? AND store_id=? AND user_id=? AND status=0 AND deleted=0 FOR UPDATE",
                (r,n)->r.getLong(1),s.tenantId(),s.storeId(),s.userId());
        if(rows.size()!=1)throw new PrintPaymentException(PrintPaymentException.Reason.SCOPE_CHANGED);
    }
    Optional<Order> ownedOrder(PrintScope s,String id,boolean lock){
        return jdbc.query("SELECT "+ORDER_FIELDS+" FROM print_order WHERE "+OWNER+" AND id=?"+(lock?" FOR UPDATE":""),ORDER_MAPPER,
                s.tenantId(),s.storeId(),s.userId(),id).stream().findFirst();
    }
    Optional<Receipt> idempotent(PrintScope s,String key){
        return jdbc.query("SELECT "+RECEIPT_FIELDS+" FROM print_payment_receipt WHERE "+OWNER+" AND idempotency_sha256=?",
                RECEIPT_MAPPER,s.tenantId(),s.storeId(),s.userId(),key).stream().findFirst();
    }
    Optional<Receipt> receipt(PrintScope s,String orderId){
        return jdbc.query("SELECT "+RECEIPT_FIELDS+" FROM print_payment_receipt WHERE "+OWNER+" AND order_id=?",
                RECEIPT_MAPPER,s.tenantId(),s.storeId(),s.userId(),orderId).stream().findFirst();
    }
    List<Item> items(PrintScope s,String orderId){
        return jdbc.query("SELECT id,file_id,file_content_sha256,amount,quote_snapshot_json FROM print_order_item WHERE "+OWNER+" AND order_id=? ORDER BY sequence_no,id",
                (r,n)->new Item(r.getString("id"),r.getString("file_id"),r.getString("file_content_sha256"),r.getString("amount"),r.getString("quote_snapshot_json")),
                s.tenantId(),s.storeId(),s.userId(),orderId);
    }
    Optional<File> fileForUpdate(PrintScope s,String id){
        return jdbc.query("SELECT id,content_sha256,page_count,status,expires_at_ms FROM print_file WHERE "+OWNER+" AND id=? FOR UPDATE",
                (r,n)->new File(r.getString("id"),r.getString("content_sha256"),r.getObject("page_count",Integer.class),r.getString("status"),r.getLong("expires_at_ms")),
                s.tenantId(),s.storeId(),s.userId(),id).stream().findFirst();
    }
    void insertReceipt(PrintScope s,String id,Order order,String keyHash,String inputHash,long now){
        jdbc.update("INSERT INTO print_payment_receipt(id,order_id,tenant_id,store_id,owner_user_id,provider,state,simulation,currency,amount,actor_type,actor_id,"
                        +"idempotency_sha256,input_sha256,created_at_ms) VALUES(?,?,?,?,?,'SIMULATED','SUCCEEDED',1,?,?,'STAFF',?,?,?,?)",
                id,order.id(),s.tenantId(),s.storeId(),s.userId(),order.currency(),order.amount(),s.userId(),keyHash,inputHash,now);
    }
    void insertJournal(PrintScope s,String receiptId,Order order,String side,String account,long now){
        jdbc.update("INSERT INTO print_simulated_journal(id,receipt_id,order_id,tenant_id,store_id,owner_user_id,entry_side,account_kind,amount,currency,simulation,created_at_ms) "
                        +"VALUES(?,?,?,?,?,?,?,?,?,?,1,?)",UUID.randomUUID().toString(),receiptId,order.id(),s.tenantId(),s.storeId(),s.userId(),
                side,account,order.amount(),order.currency(),now);
    }
    void insertJob(PrintScope s,String id,String receiptId,Order order,Item item,long now){
        jdbc.update("INSERT INTO print_job(id,order_id,order_item_id,payment_receipt_id,file_id,tenant_id,store_id,owner_user_id,file_content_sha256,source,simulation,"
                        +"generation,dispatch_state,print_result,version,attempt_count,fencing_version,created_at_ms,updated_at_ms) "
                        +"VALUES(?,?,?,?,?,?,?,?,?,'ADMIN_DEMO',1,0,'QUEUED','NOT_REPORTED',1,0,0,?,?)",
                id,order.id(),item.id(),receiptId,item.fileId(),s.tenantId(),s.storeId(),s.userId(),item.sha256(),now,now);
    }
    void insertEvent(PrintScope s,String orderId,long now){
        jdbc.update("INSERT INTO print_order_event(id,order_id,tenant_id,store_id,owner_user_id,event_type,actor_type,actor_id,simulation,aggregate_version,created_at_ms) "
                        +"VALUES(?,?,?,?,?,'PAYMENT_SIMULATED','STAFF',?,1,2,?)",UUID.randomUUID().toString(),orderId,
                s.tenantId(),s.storeId(),s.userId(),s.userId(),now);
    }
    boolean markPaid(PrintScope s,String id){
        return jdbc.update("UPDATE print_order SET payment_state='SUCCEEDED',print_state='QUEUED',version=2 WHERE "+OWNER
                        +" AND id=? AND version=1 AND order_state='OPEN' AND payment_state='UNPAID' AND print_state='NOT_CREATED' "
                        +"AND refund_state='NONE' AND delivery_mode='NONE' AND delivery_state='NOT_APPLICABLE'",
                s.tenantId(),s.storeId(),s.userId(),id)==1;
    }
    List<Journal> journals(PrintScope s,String receiptId){
        return jdbc.query("SELECT entry_side,account_kind,amount,currency,simulation,order_id FROM print_simulated_journal WHERE "+OWNER+" AND receipt_id=? ORDER BY entry_side",
                (r,n)->new Journal(r.getString("entry_side"),r.getString("account_kind"),r.getString("amount"),r.getString("currency"),r.getBoolean("simulation"),r.getString("order_id")),
                s.tenantId(),s.storeId(),s.userId(),receiptId);
    }
    List<Job> jobs(PrintScope s,String receiptId){
        return jdbc.query("SELECT id,order_id,order_item_id,file_id,file_content_sha256,simulation,source FROM print_job WHERE "+OWNER+" AND payment_receipt_id=? ORDER BY id",
                (r,n)->new Job(r.getString("id"),r.getString("order_id"),r.getString("order_item_id"),r.getString("file_id"),r.getString("file_content_sha256"),
                        r.getBoolean("simulation"),r.getString("source")),s.tenantId(),s.storeId(),s.userId(),receiptId);
    }
    long paidEventCount(PrintScope s,String orderId){
        Long n=jdbc.queryForObject("SELECT COUNT(*) FROM print_order_event WHERE "+OWNER+" AND order_id=? AND event_type='PAYMENT_SIMULATED' "
                +"AND aggregate_version=2 AND actor_type='STAFF' AND actor_id=? AND simulation=1",Long.class,
                s.tenantId(),s.storeId(),s.userId(),orderId,s.userId());return n==null?0:n;
    }
}
