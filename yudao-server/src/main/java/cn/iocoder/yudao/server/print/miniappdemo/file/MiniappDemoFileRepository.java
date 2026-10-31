package cn.iocoder.yudao.server.print.miniappdemo.file;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class MiniappDemoFileRepository {
    private static final String FIELDS = "id,tenant_id,store_id,principal_id,idempotency_sha256,content_sha256,display_name,"
            + "size_bytes,page_count,status,failure_code,storage_key,created_at_ms,updated_at_ms,expires_at_ms,processing_attempts";
    private static final String OWNER = "id=? AND tenant_id=? AND store_id=? AND principal_id=?";
    private static final RowMapper<MiniappDemoFileRow> MAPPER = (rs, n) -> new MiniappDemoFileRow(
            rs.getString("id"), rs.getLong("tenant_id"), rs.getLong("store_id"), rs.getString("principal_id"),
            rs.getString("idempotency_sha256"), rs.getString("content_sha256"), rs.getString("display_name"),
            rs.getLong("size_bytes"), rs.getObject("page_count", Integer.class), rs.getString("status"),
            rs.getString("failure_code"), rs.getString("storage_key"), rs.getLong("created_at_ms"),
            rs.getLong("updated_at_ms"), rs.getLong("expires_at_ms"), rs.getInt("processing_attempts"));
    private final JdbcTemplate jdbc;
    public MiniappDemoFileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<MiniappDemoFileRow> findOwned(String id, MiniappDemoIdentity principal) {
        return jdbc.query("SELECT " + FIELDS + " FROM print_miniapp_demo_file WHERE " + OWNER, MAPPER,
                id, principal.tenantId(), principal.storeId(), principal.principalId()).stream().findFirst();
    }
    Optional<MiniappDemoFileRow> findIdempotent(MiniappDemoIdentity principal, String digest) {
        return jdbc.query("SELECT " + FIELDS + " FROM print_miniapp_demo_file WHERE tenant_id=? AND store_id=? AND principal_id=? AND idempotency_sha256=?",
                MAPPER, principal.tenantId(), principal.storeId(), principal.principalId(), digest).stream().findFirst();
    }
    long activeCount(MiniappDemoIdentity principal, long now) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM print_miniapp_demo_file WHERE tenant_id=? AND store_id=? AND principal_id=? "
                        + "AND status IN ('UPLOADED','PROCESSING','READY') AND expires_at_ms>?",
                Long.class, principal.tenantId(), principal.storeId(), principal.principalId(), now);
        return count == null ? 0 : count;
    }
    long pendingCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM print_miniapp_demo_file WHERE status IN ('UPLOADED','PROCESSING')", Long.class);
        return count == null ? 0 : count;
    }
    boolean storageReferenced(String key) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM print_miniapp_demo_file WHERE storage_key=?", Long.class, key);
        return count != null && count > 0;
    }
    void insert(MiniappDemoFileRow row) {
        jdbc.update("INSERT INTO print_miniapp_demo_file(id,tenant_id,store_id,principal_id,idempotency_sha256,content_sha256,display_name,"
                        + "size_bytes,status,storage_key,created_at_ms,updated_at_ms,expires_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.id(), row.tenantId(), row.storeId(), row.principalId(), row.idempotencySha256(), row.contentSha256(),
                row.displayName(), row.sizeBytes(), row.status(), row.storageKey(), row.createdAtMs(), row.updatedAtMs(), row.expiresAtMs());
    }
    void recoverInterrupted(long now) {
        jdbc.update("UPDATE print_miniapp_demo_file SET status='UPLOADED',processing_started_at_ms=NULL,updated_at_ms=? WHERE status='PROCESSING'", now);
    }
    Optional<MiniappDemoFileRow> claimNext(long now) {
        List<MiniappDemoFileRow> rows = jdbc.query("SELECT " + FIELDS + " FROM print_miniapp_demo_file "
                + "WHERE status='UPLOADED' AND expires_at_ms>? ORDER BY created_at_ms,id LIMIT 1", MAPPER, now);
        if (rows.isEmpty()) return Optional.empty();
        MiniappDemoFileRow row = rows.get(0);
        int changed = jdbc.update("UPDATE print_miniapp_demo_file SET status='PROCESSING',processing_started_at_ms=?,"
                        + "processing_attempts=processing_attempts+1,updated_at_ms=? WHERE " + OWNER + " AND status='UPLOADED'",
                now, now, row.id(), row.tenantId(), row.storeId(), row.principalId());
        return changed == 1 ? findExact(row) : Optional.empty();
    }
    Optional<MiniappDemoFileRow> findExact(MiniappDemoFileRow row) {
        return jdbc.query("SELECT " + FIELDS + " FROM print_miniapp_demo_file WHERE " + OWNER, MAPPER,
                row.id(), row.tenantId(), row.storeId(), row.principalId()).stream().findFirst();
    }
    void ready(MiniappDemoFileRow row, int pages, long now) {
        jdbc.update("UPDATE print_miniapp_demo_file SET status='READY',page_count=?,failure_code=NULL,updated_at_ms=? WHERE " + OWNER
                        + " AND status='PROCESSING' AND expires_at_ms>?",
                pages, now, row.id(), row.tenantId(), row.storeId(), row.principalId(), now);
    }
    void failed(MiniappDemoFileRow row, String failure, long now) {
        jdbc.update("UPDATE print_miniapp_demo_file SET status='FAILED',failure_code=?,updated_at_ms=? WHERE " + OWNER
                        + " AND status IN ('PROCESSING','UPLOADED')",
                failure, now, row.id(), row.tenantId(), row.storeId(), row.principalId());
    }
    void deleted(MiniappDemoFileRow row, String reason, long now) {
        jdbc.update("UPDATE print_miniapp_demo_file SET status='DELETED',failure_code=?,updated_at_ms=? WHERE " + OWNER + " AND status<>'DELETED'",
                reason, now, row.id(), row.tenantId(), row.storeId(), row.principalId());
    }
    List<MiniappDemoFileRow> cleanupCandidates(long now, int limit) {
        return jdbc.query("SELECT " + FIELDS + " FROM print_miniapp_demo_file WHERE "
                        + "(expires_at_ms<=? AND status<>'DELETED') OR (status IN ('FAILED','DELETED') AND blob_deleted_at_ms IS NULL) "
                        + "ORDER BY expires_at_ms,id LIMIT ?", MAPPER, now, limit);
    }
    void purged(MiniappDemoFileRow row, long now) {
        jdbc.update("UPDATE print_miniapp_demo_file SET blob_deleted_at_ms=? WHERE " + OWNER + " AND status IN ('FAILED','DELETED')",
                now, row.id(), row.tenantId(), row.storeId(), row.principalId());
    }
}
