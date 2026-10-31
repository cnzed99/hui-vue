package cn.iocoder.yudao.server.print.miniappdemo.order;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class MiniappDemoOrderRepository {
    record Order(String id, String orderNo, String source, boolean simulation, String currency,
                 String totalAmount, String orderState, String paymentState, String refundState,
                 String printState, long version, String inputHash, long createdAtMs, long updatedAtMs) {}
    record Quote(String id, String fileId, String profileId, String snapshotJson,
                 long createdAtMs, long expiresAtMs) {}
    record File(String id, String sha256, Integer pages, String status, long expiresAtMs) {}
    record Item(String id, String quoteId, String fileId, String fileSha256,
                String amount, String snapshotJson) {}

    private static final String ORDER_FIELDS = "id,order_no,source,simulation,currency,total_amount,order_state,"
            + "payment_state,refund_state,print_state,version,input_sha256,created_at_ms,updated_at_ms";
    private static final String OWNER = "tenant_id=? AND store_id=? AND principal_id=?";
    private static final RowMapper<Order> ORDER_MAPPER = (rs, n) -> new Order(rs.getString("id"),
            rs.getString("order_no"), rs.getString("source"), rs.getBoolean("simulation"),
            rs.getString("currency"), rs.getString("total_amount"), rs.getString("order_state"),
            rs.getString("payment_state"), rs.getString("refund_state"), rs.getString("print_state"),
            rs.getLong("version"), rs.getString("input_sha256"), rs.getLong("created_at_ms"),
            rs.getLong("updated_at_ms"));

    private final JdbcTemplate jdbc;
    public MiniappDemoOrderRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<Order> idempotentForUpdate(MiniappDemoIdentity identity, String keyHash) {
        return jdbc.query("SELECT " + ORDER_FIELDS + " FROM print_miniapp_demo_order WHERE " + OWNER
                        + " AND idempotency_sha256=? FOR UPDATE", ORDER_MAPPER, identity.tenantId(),
                identity.storeId(), identity.principalId(), keyHash).stream().findFirst();
    }

    Optional<Order> owned(MiniappDemoIdentity identity, String id) {
        return jdbc.query("SELECT " + ORDER_FIELDS + " FROM print_miniapp_demo_order WHERE " + OWNER + " AND id=?",
                ORDER_MAPPER, identity.tenantId(), identity.storeId(), identity.principalId(), id).stream().findFirst();
    }

    Optional<Order> ownedForUpdate(MiniappDemoIdentity identity, String id) {
        return jdbc.query("SELECT " + ORDER_FIELDS + " FROM print_miniapp_demo_order WHERE " + OWNER
                        + " AND id=? FOR UPDATE", ORDER_MAPPER, identity.tenantId(), identity.storeId(),
                identity.principalId(), id).stream().findFirst();
    }

    Optional<Quote> quoteForUpdate(MiniappDemoIdentity identity, String id) {
        return jdbc.query("SELECT id,file_id,price_profile_id,snapshot_json,created_at_ms,expires_at_ms "
                        + "FROM print_miniapp_demo_quote WHERE " + OWNER + " AND id=? FOR UPDATE",
                (rs, n) -> new Quote(rs.getString("id"), rs.getString("file_id"),
                        rs.getString("price_profile_id"), rs.getString("snapshot_json"),
                        rs.getLong("created_at_ms"), rs.getLong("expires_at_ms")),
                identity.tenantId(), identity.storeId(), identity.principalId(), id).stream().findFirst();
    }

    Optional<File> fileForUpdate(MiniappDemoIdentity identity, String id) {
        return jdbc.query("SELECT id,content_sha256,page_count,status,expires_at_ms "
                        + "FROM print_miniapp_demo_file WHERE " + OWNER + " AND id=? FOR UPDATE",
                (rs, n) -> new File(rs.getString("id"), rs.getString("content_sha256"),
                        rs.getObject("page_count", Integer.class), rs.getString("status"), rs.getLong("expires_at_ms")),
                identity.tenantId(), identity.storeId(), identity.principalId(), id).stream().findFirst();
    }

    boolean consumed(MiniappDemoIdentity identity, String quoteId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM print_miniapp_demo_order_item WHERE " + OWNER
                        + " AND quote_id=?", Long.class, identity.tenantId(), identity.storeId(),
                identity.principalId(), quoteId);
        return count != null && count > 0;
    }

    int count(MiniappDemoIdentity identity) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM print_miniapp_demo_order WHERE " + OWNER,
                Integer.class, identity.tenantId(), identity.storeId(), identity.principalId());
        return count == null ? 0 : count;
    }

    void insert(MiniappDemoIdentity identity, String orderId, String orderNo, Quote quote, File file,
                String snapshotJson, String amount, String currency, String keyHash, String inputHash, long now) {
        jdbc.update("INSERT INTO print_miniapp_demo_order(id,order_no,tenant_id,store_id,principal_id,source,simulation,"
                        + "currency,total_amount,order_state,payment_state,refund_state,print_state,version,"
                        + "idempotency_sha256,input_sha256,created_at_ms,updated_at_ms) "
                        + "VALUES(?,?,?,?,?,'MINIAPP_DEMO',1,?,?,'OPEN','UNPAID','NONE','NOT_CREATED',1,?,?,?,?)",
                orderId, orderNo, identity.tenantId(), identity.storeId(), identity.principalId(), currency, amount,
                keyHash, inputHash, now, now);
        jdbc.update("INSERT INTO print_miniapp_demo_order_item(id,order_id,tenant_id,store_id,principal_id,quote_id,"
                        + "file_id,file_content_sha256,quote_snapshot_json,amount,sequence_no,created_at_ms) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,1,?)", UUID.randomUUID().toString(), orderId,
                identity.tenantId(), identity.storeId(), identity.principalId(), quote.id(), file.id(),
                file.sha256(), snapshotJson, amount, now);
        jdbc.update("INSERT INTO print_miniapp_demo_order_event(id,order_id,tenant_id,store_id,principal_id,event_type,"
                        + "actor_type,actor_id,simulation,aggregate_version,created_at_ms) "
                        + "VALUES(?,?,?,?,?,'ORDER_CREATED','MINIAPP_DEMO',?,1,1,?)", UUID.randomUUID().toString(),
                orderId, identity.tenantId(), identity.storeId(), identity.principalId(), identity.principalId(), now);
    }

    Optional<Item> item(MiniappDemoIdentity identity, String orderId) {
        return jdbc.query("SELECT id,quote_id,file_id,file_content_sha256,amount,quote_snapshot_json "
                        + "FROM print_miniapp_demo_order_item WHERE " + OWNER + " AND order_id=? ORDER BY sequence_no,id",
                (rs, n) -> new Item(rs.getString("id"), rs.getString("quote_id"), rs.getString("file_id"),
                        rs.getString("file_content_sha256"), rs.getString("amount"), rs.getString("quote_snapshot_json")),
                identity.tenantId(), identity.storeId(), identity.principalId(), orderId).stream().findFirst();
    }
}
