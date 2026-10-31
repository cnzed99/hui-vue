package cn.iocoder.yudao.server.print.miniappdemo.order;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MiniappDemoPaymentRepository {
    record Receipt(String id, String orderId, String provider, String mode, String state,
                   boolean simulation, String currency, String amount, String idempotencyHash,
                   String inputHash, long createdAtMs) {}
    record Journal(String orderId, String side, String account, String currency, String amount) {}

    private static final String OWNER = "tenant_id=? AND store_id=? AND principal_id=?";
    private static final String RECEIPT_FIELDS = "id,order_id,provider,mode,state,simulation,currency,amount,"
            + "idempotency_sha256,input_sha256,created_at_ms";
    private static final RowMapper<Receipt> RECEIPT_MAPPER = (rs, n) -> new Receipt(rs.getString("id"),
            rs.getString("order_id"), rs.getString("provider"), rs.getString("mode"),
            rs.getString("state"), rs.getBoolean("simulation"), rs.getString("currency"),
            rs.getString("amount"), rs.getString("idempotency_sha256"), rs.getString("input_sha256"),
            rs.getLong("created_at_ms"));

    private final JdbcTemplate jdbc;
    public MiniappDemoPaymentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<Receipt> idempotentForUpdate(MiniappDemoIdentity identity, String keyHash) {
        return jdbc.query("SELECT " + RECEIPT_FIELDS + " FROM print_miniapp_demo_payment_receipt WHERE "
                        + OWNER + " AND idempotency_sha256=? FOR UPDATE", RECEIPT_MAPPER,
                identity.tenantId(), identity.storeId(), identity.principalId(), keyHash).stream().findFirst();
    }

    Optional<Receipt> byOrder(MiniappDemoIdentity identity, String orderId) {
        return jdbc.query("SELECT " + RECEIPT_FIELDS + " FROM print_miniapp_demo_payment_receipt WHERE "
                        + OWNER + " AND order_id=?", RECEIPT_MAPPER, identity.tenantId(), identity.storeId(),
                identity.principalId(), orderId).stream().findFirst();
    }

    Optional<MiniappDemoOrderRepository.Item> itemForUpdate(MiniappDemoIdentity identity, String orderId) {
        return jdbc.query("SELECT id,quote_id,file_id,file_content_sha256,amount,quote_snapshot_json "
                        + "FROM print_miniapp_demo_order_item WHERE " + OWNER
                        + " AND order_id=? ORDER BY sequence_no,id FOR UPDATE",
                (rs, n) -> new MiniappDemoOrderRepository.Item(rs.getString("id"), rs.getString("quote_id"),
                        rs.getString("file_id"), rs.getString("file_content_sha256"), rs.getString("amount"),
                        rs.getString("quote_snapshot_json")), identity.tenantId(), identity.storeId(),
                identity.principalId(), orderId).stream().findFirst();
    }

    List<Journal> journals(MiniappDemoIdentity identity, String receiptId) {
        return jdbc.query("SELECT order_id,side,account,currency,amount FROM print_miniapp_demo_payment_journal WHERE "
                        + OWNER + " AND receipt_id=? ORDER BY side", (rs, n) -> new Journal(rs.getString("order_id"),
                        rs.getString("side"), rs.getString("account"), rs.getString("currency"), rs.getString("amount")),
                identity.tenantId(), identity.storeId(), identity.principalId(), receiptId);
    }

    String insertEvidence(MiniappDemoIdentity identity, MiniappDemoOrderRepository.Order order,
                          String keyHash, String inputHash, long now) {
        String receiptId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO print_miniapp_demo_payment_receipt(id,order_id,tenant_id,store_id,principal_id,"
                        + "provider,mode,state,simulation,currency,amount,idempotency_sha256,input_sha256,created_at_ms) "
                        + "VALUES(?,?,?,?,?,'SIMULATED','DEMO','SUCCEEDED',1,?,?,?,?,?)", receiptId, order.id(),
                identity.tenantId(), identity.storeId(), identity.principalId(), order.currency(), order.totalAmount(),
                keyHash, inputHash, now);
        insertJournal(identity, receiptId, order, "DEBIT", "DEMO_CUSTOMER", now);
        insertJournal(identity, receiptId, order, "CREDIT", "DEMO_STORE", now);
        jdbc.update("INSERT INTO print_miniapp_demo_order_event(id,order_id,tenant_id,store_id,principal_id,event_type,"
                        + "actor_type,actor_id,simulation,aggregate_version,created_at_ms) "
                        + "VALUES(?,?,?,?,?,'PAYMENT_SIMULATED','MINIAPP_DEMO',?,1,2,?)", UUID.randomUUID().toString(),
                order.id(), identity.tenantId(), identity.storeId(), identity.principalId(),
                identity.principalId(), now);
        return receiptId;
    }

    private void insertJournal(MiniappDemoIdentity identity, String receiptId,
                               MiniappDemoOrderRepository.Order order, String side, String account, long now) {
        jdbc.update("INSERT INTO print_miniapp_demo_payment_journal(id,receipt_id,order_id,tenant_id,store_id,"
                        + "principal_id,side,account,currency,amount,created_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), receiptId, order.id(), identity.tenantId(), identity.storeId(),
                identity.principalId(), side, account, order.currency(), order.totalAmount(), now);
    }

    boolean markPaid(MiniappDemoIdentity identity, MiniappDemoOrderRepository.Order order, long now) {
        return jdbc.update("UPDATE print_miniapp_demo_order SET payment_state='SUCCEEDED',version=2,updated_at_ms=? "
                        + "WHERE " + OWNER + " AND id=? AND source='MINIAPP_DEMO' AND simulation=1 "
                        + "AND order_state='OPEN' AND payment_state='UNPAID' AND refund_state='NONE' "
                        + "AND print_state='NOT_CREATED' AND version=1", now, identity.tenantId(), identity.storeId(),
                identity.principalId(), order.id()) == 1;
    }
}
