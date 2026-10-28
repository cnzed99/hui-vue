package cn.iocoder.yudao.server.print.order;

import cn.iocoder.yudao.server.print.scope.PrintScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;

@Repository
public class PrintOrderRepository {
    record Order(PrintOrderSummary summary,String inputHash) { }
    record Quote(String id,String fileId,String profileId,String json,long createdAtMs,long expiresAtMs) { }
    record File(String id,String sha256,Integer pages,String status,long expiresAtMs) { }
    record Item(String id,String quoteId,String fileId,String fileSha256,String amount,String json) { }
    private static final String FIELDS="id,order_no,source,simulation,currency,total_amount,order_state,payment_state,"
            +"refund_state,delivery_mode,delivery_state,print_state,created_by_actor_type,created_by_actor_id,created_at_ms,input_sha256";
    private static final String OWNER="tenant_id=? AND store_id=? AND owner_user_id=?";
    private static final RowMapper<Order> ORDER_MAPPER=(rs,n)->new Order(new PrintOrderSummary(
            rs.getString("id"),rs.getString("order_no"),rs.getString("source"),rs.getBoolean("simulation"),
            rs.getString("currency"),rs.getString("total_amount"),rs.getString("order_state"),rs.getString("payment_state"),
            rs.getString("refund_state"),rs.getString("delivery_mode"),rs.getString("delivery_state"),rs.getString("print_state"),
            rs.getString("created_by_actor_type"),rs.getLong("created_by_actor_id"),rs.getLong("created_at_ms")),rs.getString("input_sha256"));
    private final JdbcTemplate jdbc;
    public PrintOrderRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    void serializeOwner(PrintScope scope){
        var ids=jdbc.query("SELECT id FROM print_staff_store WHERE tenant_id=? AND store_id=? AND user_id=? "
                        +"AND status=0 AND deleted=0 FOR UPDATE",(rs,n)->rs.getLong(1),scope.tenantId(),scope.storeId(),scope.userId());
        if(ids.size()!=1)throw new PrintOrderException(PrintOrderException.Reason.SCOPE_CHANGED);
    }
    Optional<Order> idempotent(PrintScope s,String hash){
        // Non-locking lookup after the mapping lock; new-create lock order remains mapping -> quote -> file -> order.
        return jdbc.query("SELECT "+FIELDS+" FROM print_order WHERE "+OWNER+" AND idempotency_sha256=?",ORDER_MAPPER,
                s.tenantId(),s.storeId(),s.userId(),hash).stream().findFirst();
    }
    Optional<Order> owned(PrintScope s,String id){
        return jdbc.query("SELECT "+FIELDS+" FROM print_order WHERE "+OWNER+" AND id=?",ORDER_MAPPER,
                s.tenantId(),s.storeId(),s.userId(),id).stream().findFirst();
    }
    Optional<Quote> quoteForUpdate(PrintScope s,String id){
        return jdbc.query("SELECT id,file_id,price_profile_id,snapshot_json,created_at_ms,expires_at_ms FROM print_quote WHERE "+OWNER+" AND id=? FOR UPDATE",
                (rs,n)->new Quote(rs.getString("id"),rs.getString("file_id"),rs.getString("price_profile_id"),rs.getString("snapshot_json"),
                        rs.getLong("created_at_ms"),rs.getLong("expires_at_ms")),s.tenantId(),s.storeId(),s.userId(),id).stream().findFirst();
    }
    Optional<File> fileForUpdate(PrintScope s,String id){
        return jdbc.query("SELECT id,content_sha256,page_count,status,expires_at_ms FROM print_file WHERE "+OWNER+" AND id=? FOR UPDATE",
                (rs,n)->new File(rs.getString("id"),rs.getString("content_sha256"),rs.getObject("page_count",Integer.class),
                        rs.getString("status"),rs.getLong("expires_at_ms")),s.tenantId(),s.storeId(),s.userId(),id).stream().findFirst();
    }
    boolean consumed(PrintScope s,String quoteId){
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM print_order_item WHERE "+OWNER+" AND quote_id=?",Long.class,
                s.tenantId(),s.storeId(),s.userId(),quoteId);
        return count!=null && count>0;
    }
    void insert(PrintScope s,String id,String number,String quoteId,Quote quote,File file,String amount,String currency,
                String keyHash,String inputHash,long now){
        jdbc.update("INSERT INTO print_order(id,order_no,tenant_id,store_id,owner_user_id,created_by_actor_type,created_by_actor_id,"
                +"source,simulation,currency,total_amount,order_state,payment_state,refund_state,delivery_mode,delivery_state,print_state,"
                +"version,idempotency_sha256,input_sha256,created_at_ms) VALUES(?,?,?,?,?,'STAFF',?,'ADMIN_DEMO',1,?,?,'OPEN','UNPAID','NONE','NONE','NOT_APPLICABLE','NOT_CREATED',1,?,?,?)",
                id,number,s.tenantId(),s.storeId(),s.userId(),s.userId(),currency,amount,keyHash,inputHash,now);
        jdbc.update("INSERT INTO print_order_item(id,order_id,tenant_id,store_id,owner_user_id,quote_id,file_id,file_content_sha256,"
                        +"quote_snapshot_json,amount,sequence_no,created_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,1,?)",
                java.util.UUID.randomUUID().toString(),id,s.tenantId(),s.storeId(),s.userId(),quoteId,file.id(),file.sha256(),quote.json(),amount,now);
        jdbc.update("INSERT INTO print_order_event(id,order_id,tenant_id,store_id,owner_user_id,event_type,actor_type,actor_id,simulation,aggregate_version,created_at_ms) "
                        +"VALUES(?,?,?,?,?,'ORDER_CREATED','STAFF',?,1,1,?)",
                java.util.UUID.randomUUID().toString(),id,s.tenantId(),s.storeId(),s.userId(),s.userId(),now);
    }
    List<Item> items(PrintScope s,String orderId){
        return jdbc.query("SELECT id,quote_id,file_id,file_content_sha256,amount,quote_snapshot_json FROM print_order_item WHERE "+OWNER
                        +" AND order_id=? ORDER BY sequence_no,id",(rs,n)->new Item(rs.getString("id"),rs.getString("quote_id"),rs.getString("file_id"),
                        rs.getString("file_content_sha256"),rs.getString("amount"),rs.getString("quote_snapshot_json")),s.tenantId(),s.storeId(),s.userId(),orderId);
    }
    private record Filter(String sql,List<Object> args) { }
    private static Filter filter(PrintScope s,PrintOrderQuery query){
        StringBuilder sql=new StringBuilder(OWNER);List<Object> args=new ArrayList<>(List.of(s.tenantId(),s.storeId(),s.userId()));
        add(sql,args,"order_no=?",query.orderNo());
        add(sql,args,"created_at_ms>=?",query.createdFromMs());add(sql,args,"created_at_ms<=?",query.createdToMs());
        add(sql,args,"order_state=?",query.orderState());add(sql,args,"payment_state=?",query.paymentState());
        add(sql,args,"refund_state=?",query.refundState());add(sql,args,"print_state=?",query.printState());
        return new Filter(sql.toString(),args);
    }
    private static void add(StringBuilder sql,List<Object> args,String clause,Object value){
        if(value!=null){sql.append(" AND ").append(clause);args.add(value);}
    }
    long count(PrintScope s,PrintOrderQuery query){
        Filter f=filter(s,query);
        Long n=jdbc.queryForObject("SELECT COUNT(*) FROM print_order WHERE "+f.sql(),Long.class,f.args().toArray());
        return n==null?0:n;
    }
    List<PrintOrderSummary> list(PrintScope s,PrintOrderQuery query){
        Filter f=filter(s,query);List<Object> args=new ArrayList<>(f.args());
        args.add(query.pageSize());args.add((long)(query.pageNo()-1)*query.pageSize());
        return jdbc.query("SELECT "+FIELDS+" FROM print_order WHERE "+f.sql()+" ORDER BY created_at_ms DESC,id DESC LIMIT ? OFFSET ?",
                ORDER_MAPPER,args.toArray()).stream().map(Order::summary).toList();
    }
    long eventCount(PrintScope s,String orderId){
        Long n=jdbc.queryForObject("SELECT COUNT(*) FROM print_order_event WHERE "+OWNER+" AND order_id=?",Long.class,
                s.tenantId(),s.storeId(),s.userId(),orderId);return n==null?0:n;
    }
    List<PrintOrderEventView> events(PrintScope s,String orderId,int limit,long offset){
        return jdbc.query("SELECT id,order_id,event_type,actor_type,actor_id,simulation,aggregate_version,created_at_ms "
                        +"FROM print_order_event WHERE "+OWNER+" AND order_id=? ORDER BY aggregate_version,id LIMIT ? OFFSET ?",
                (rs,n)->new PrintOrderEventView(rs.getString("id"),rs.getString("order_id"),rs.getString("event_type"),rs.getString("actor_type"),
                        rs.getLong("actor_id"),rs.getBoolean("simulation"),rs.getLong("aggregate_version"),rs.getLong("created_at_ms")),
                s.tenantId(),s.storeId(),s.userId(),orderId,limit,offset);
    }
}
