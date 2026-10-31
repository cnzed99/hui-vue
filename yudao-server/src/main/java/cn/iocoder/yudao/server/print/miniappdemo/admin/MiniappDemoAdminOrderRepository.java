package cn.iocoder.yudao.server.print.miniappdemo.admin;

import cn.iocoder.yudao.server.print.scope.PrintScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MiniappDemoAdminOrderRepository {
    record Order(String id, String orderNo, String source, boolean simulation, String currency,
                 String totalAmount, String orderState, String paymentState, String refundState,
                 String printState, long version, String principalId, long createdAtMs, long updatedAtMs) {}
    record Item(String id, String quoteId, String fileId, String fileSha256, String amount, String snapshotJson) {}
    record Payment(String id, String provider, String mode, String state, boolean simulation,
                   String currency, String amount, long createdAtMs) {}
    record Event(String id, String type, String actorType, String actorId,
                 boolean simulation, long aggregateVersion, long createdAtMs) {}

    private static final String ORDER_FIELDS = "id,order_no,source,simulation,currency,total_amount,order_state,"
            + "payment_state,refund_state,print_state,version,principal_id,created_at_ms,updated_at_ms";
    private static final RowMapper<Order> ORDER_MAPPER = (rs, n) -> new Order(rs.getString("id"),
            rs.getString("order_no"), rs.getString("source"), rs.getBoolean("simulation"),
            rs.getString("currency"), rs.getString("total_amount"), rs.getString("order_state"),
            rs.getString("payment_state"), rs.getString("refund_state"), rs.getString("print_state"),
            rs.getLong("version"), rs.getString("principal_id"), rs.getLong("created_at_ms"),
            rs.getLong("updated_at_ms"));

    private final JdbcTemplate jdbc;
    public MiniappDemoAdminOrderRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    long count(PrintScope scope) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM print_miniapp_demo_order WHERE tenant_id=? AND store_id=?",
                Long.class, scope.tenantId(), scope.storeId());
        return value == null ? 0 : value;
    }

    List<Order> list(PrintScope scope, int limit, long offset) {
        return jdbc.query("SELECT " + ORDER_FIELDS + " FROM print_miniapp_demo_order WHERE tenant_id=? AND store_id=? "
                        + "ORDER BY created_at_ms DESC,id DESC LIMIT ? OFFSET ?", ORDER_MAPPER,
                scope.tenantId(), scope.storeId(), limit, offset);
    }

    Optional<Order> order(PrintScope scope, String id) {
        return jdbc.query("SELECT " + ORDER_FIELDS + " FROM print_miniapp_demo_order WHERE tenant_id=? AND store_id=? AND id=?",
                ORDER_MAPPER, scope.tenantId(), scope.storeId(), id).stream().findFirst();
    }

    Optional<Item> item(PrintScope scope, Order order) {
        return jdbc.query("SELECT id,quote_id,file_id,file_content_sha256,amount,quote_snapshot_json "
                        + "FROM print_miniapp_demo_order_item WHERE tenant_id=? AND store_id=? AND principal_id=? "
                        + "AND order_id=? ORDER BY sequence_no,id",
                (rs, n) -> new Item(rs.getString("id"), rs.getString("quote_id"), rs.getString("file_id"),
                        rs.getString("file_content_sha256"), rs.getString("amount"),
                        rs.getString("quote_snapshot_json")), scope.tenantId(), scope.storeId(),
                order.principalId(), order.id()).stream().findFirst();
    }

    Optional<Payment> payment(PrintScope scope, Order order) {
        return jdbc.query("SELECT id,provider,mode,state,simulation,currency,amount,created_at_ms "
                        + "FROM print_miniapp_demo_payment_receipt WHERE tenant_id=? AND store_id=? "
                        + "AND principal_id=? AND order_id=?",
                (rs, n) -> new Payment(rs.getString("id"), rs.getString("provider"), rs.getString("mode"),
                        rs.getString("state"), rs.getBoolean("simulation"), rs.getString("currency"),
                        rs.getString("amount"), rs.getLong("created_at_ms")), scope.tenantId(), scope.storeId(),
                order.principalId(), order.id()).stream().findFirst();
    }

    List<Event> events(PrintScope scope, Order order) {
        return jdbc.query("SELECT id,event_type,actor_type,actor_id,simulation,aggregate_version,created_at_ms "
                        + "FROM print_miniapp_demo_order_event WHERE tenant_id=? AND store_id=? AND principal_id=? "
                        + "AND order_id=? ORDER BY aggregate_version,id",
                (rs, n) -> new Event(rs.getString("id"), rs.getString("event_type"),
                        rs.getString("actor_type"), rs.getString("actor_id"), rs.getBoolean("simulation"),
                        rs.getLong("aggregate_version"), rs.getLong("created_at_ms")), scope.tenantId(),
                scope.storeId(), order.principalId(), order.id());
    }
}
