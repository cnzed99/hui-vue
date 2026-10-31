package cn.iocoder.yudao.server.print.miniappdemo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MiniappDemoSessionRepository {
    private final JdbcTemplate jdbc;
    public MiniappDemoSessionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean lockActiveTenantAndStore(long tenantId, long storeId) {
        List<Long> tenants = jdbc.query("SELECT id FROM system_tenant WHERE id=? AND status=0 AND deleted=b'0' AND expire_time>CURRENT_TIMESTAMP FOR UPDATE",
                (rs, rowNum) -> rs.getLong("id"), tenantId);
        if (tenants.size() != 1) return false;
        List<Long> rows = jdbc.query("SELECT id FROM print_store WHERE tenant_id=? AND id=? AND status=0 AND deleted=b'0' FOR UPDATE",
                (rs, rowNum) -> rs.getLong("id"), tenantId, storeId);
        return rows.size() == 1;
    }

    public void expire(long tenantId, long storeId, long now) {
        jdbc.update("UPDATE print_miniapp_demo_session SET status='EXPIRED' WHERE tenant_id=? AND store_id=? AND status='ACTIVE' AND expires_at_ms<=?",
                tenantId, storeId, now);
        jdbc.update("UPDATE print_miniapp_demo_principal SET status='EXPIRED' WHERE tenant_id=? AND store_id=? AND status='ACTIVE' AND expires_at_ms<=?",
                tenantId, storeId, now);
    }

    public int countActive(long tenantId, long storeId, long now) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM print_miniapp_demo_session WHERE tenant_id=? AND store_id=? AND status='ACTIVE' AND expires_at_ms>?",
                Integer.class, tenantId, storeId, now);
        return count == null ? 0 : count;
    }

    public int countIssuedSince(long tenantId, long storeId, long since) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM print_miniapp_demo_session WHERE tenant_id=? AND store_id=? AND created_at_ms>=?",
                Integer.class, tenantId, storeId, since);
        return count == null ? 0 : count;
    }

    public void insert(String principalId, String sessionId, long tenantId, long storeId,
            String tokenHash, long createdAtMs, long expiresAtMs) {
        jdbc.update("INSERT INTO print_miniapp_demo_principal(id,tenant_id,store_id,actor_type,status,created_at_ms,expires_at_ms) VALUES(?,?,?,'MINIAPP_DEMO','ACTIVE',?,?)",
                principalId, tenantId, storeId, createdAtMs, expiresAtMs);
        jdbc.update("INSERT INTO print_miniapp_demo_session(id,tenant_id,store_id,principal_id,token_sha256,status,created_at_ms,expires_at_ms) VALUES(?,?,?,?,?,'ACTIVE',?,?)",
                sessionId, tenantId, storeId, principalId, tokenHash, createdAtMs, expiresAtMs);
    }

    public Optional<MiniappDemoPrincipal> findActive(String tokenHash, long tenantId, long storeId, long now) {
        List<MiniappDemoPrincipal> rows = jdbc.query("SELECT p.id,p.tenant_id,p.store_id,p.expires_at_ms FROM print_miniapp_demo_session s "
                        + "JOIN print_miniapp_demo_principal p ON p.tenant_id=s.tenant_id AND p.store_id=s.store_id AND p.id=s.principal_id "
                        + "JOIN print_store st ON st.tenant_id=p.tenant_id AND st.id=p.store_id "
                        + "JOIN system_tenant t ON t.id=p.tenant_id "
                        + "WHERE s.token_sha256=? AND s.tenant_id=? AND s.store_id=? AND s.status='ACTIVE' AND s.expires_at_ms>? "
                        + "AND p.actor_type='MINIAPP_DEMO' AND p.status='ACTIVE' AND p.expires_at_ms>? "
                        + "AND st.status=0 AND st.deleted=b'0' AND t.status=0 AND t.deleted=b'0' AND t.expire_time>CURRENT_TIMESTAMP",
                (rs, rowNum) -> new MiniappDemoPrincipal(rs.getString("id"), rs.getLong("tenant_id"),
                        rs.getLong("store_id"), rs.getLong("expires_at_ms")),
                tokenHash, tenantId, storeId, now, now);
        return rows.size() == 1 ? Optional.of(rows.get(0)) : Optional.empty();
    }
}
