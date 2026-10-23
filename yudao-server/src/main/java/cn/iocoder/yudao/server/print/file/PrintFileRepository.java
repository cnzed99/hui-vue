package cn.iocoder.yudao.server.print.file;

import cn.iocoder.yudao.server.print.scope.PrintScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class PrintFileRepository {
    private static final String FIELDS = "id,tenant_id,store_id,owner_user_id,idempotency_sha256,content_sha256,"
            + "display_name,size_bytes,page_count,status,failure_code,storage_key,created_at_ms,updated_at_ms,"
            + "expires_at_ms,processing_attempts";
    private static final String OWNER = "id=? AND tenant_id=? AND store_id=? AND owner_user_id=?";
    private static final RowMapper<PrintFileRow> MAPPER = (rs, n) -> new PrintFileRow(
            rs.getString("id"), rs.getLong("tenant_id"), rs.getLong("store_id"), rs.getLong("owner_user_id"),
            rs.getString("idempotency_sha256"), rs.getString("content_sha256"), rs.getString("display_name"),
            rs.getLong("size_bytes"), rs.getObject("page_count", Integer.class), rs.getString("status"),
            rs.getString("failure_code"), rs.getString("storage_key"), rs.getLong("created_at_ms"),
            rs.getLong("updated_at_ms"), rs.getLong("expires_at_ms"), rs.getInt("processing_attempts"));
    private final JdbcTemplate jdbc;
    public PrintFileRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<PrintFileRow> findOwned(String id, PrintScope scope) {
        return jdbc.query("SELECT " + FIELDS + " FROM print_file WHERE " + OWNER, MAPPER,
                id, scope.tenantId(), scope.storeId(), scope.userId()).stream().findFirst();
    }
    Optional<PrintFileRow> findIdempotent(PrintScope scope, String digest) {
        return jdbc.query("SELECT " + FIELDS + " FROM print_file WHERE tenant_id=? AND store_id=? "
                        + "AND owner_user_id=? AND idempotency_sha256=?", MAPPER,
                scope.tenantId(), scope.storeId(), scope.userId(), digest).stream().findFirst();
    }
    boolean storageReferenced(String key) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM print_file WHERE storage_key=?", Long.class, key);
        return count != null && count > 0;
    }
    long pendingCount() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM print_file WHERE status IN ('UPLOADED','PROCESSING')", Long.class);
        return n == null ? 0 : n;
    }
    void insert(PrintFileRow row) {
        jdbc.update("INSERT INTO print_file(id,tenant_id,store_id,owner_user_id,idempotency_sha256,"
                        + "content_sha256,display_name,size_bytes,status,storage_key,created_at_ms,updated_at_ms,expires_at_ms)"
                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                row.id(),row.tenantId(),row.storeId(),row.ownerUserId(),row.idempotencySha256(),row.contentSha256(),
                row.displayName(),row.sizeBytes(),row.status(),row.storageKey(),row.createdAtMs(),row.updatedAtMs(),row.expiresAtMs());
    }
    void recoverInterrupted(long now) {
        // This first deployment supports exactly one application instance and one parser worker.
        jdbc.update("UPDATE print_file SET status='UPLOADED',processing_started_at_ms=NULL,updated_at_ms=? "
                + "WHERE status='PROCESSING'", now);
    }
    Optional<PrintFileRow> claimNext(long now) {
        List<PrintFileRow> rows = jdbc.query("SELECT " + FIELDS
                + " FROM print_file WHERE status='UPLOADED' AND expires_at_ms>? ORDER BY created_at_ms,id LIMIT 1", MAPPER, now);
        if (rows.isEmpty()) return Optional.empty();
        PrintFileRow row = rows.get(0);
        int changed = jdbc.update("UPDATE print_file SET status='PROCESSING',processing_started_at_ms=?,"
                        + "processing_attempts=processing_attempts+1,updated_at_ms=? WHERE " + OWNER + " AND status='UPLOADED'",
                now, now, row.id(), row.tenantId(), row.storeId(), row.ownerUserId());
        return changed == 1 ? findOwned(row.id(), scope(row)) : Optional.empty();
    }
    void ready(PrintFileRow row, int pages, long now) {
        jdbc.update("UPDATE print_file SET status='READY',page_count=?,failure_code=NULL,updated_at_ms=? "
                        + "WHERE " + OWNER + " AND status='PROCESSING' AND expires_at_ms>?",
                pages, now, row.id(), row.tenantId(), row.storeId(), row.ownerUserId(), now);
    }
    void failed(PrintFileRow row, String failure, long now) {
        jdbc.update("UPDATE print_file SET status='FAILED',failure_code=?,updated_at_ms=? WHERE " + OWNER
                        + " AND status IN ('PROCESSING','UPLOADED')",
                failure, now, row.id(), row.tenantId(), row.storeId(), row.ownerUserId());
    }
    void deleted(PrintFileRow row, String reason, long now) {
        jdbc.update("UPDATE print_file SET status='DELETED',failure_code=?,updated_at_ms=? WHERE " + OWNER
                        + " AND status<>'DELETED'",
                reason, now, row.id(), row.tenantId(), row.storeId(), row.ownerUserId());
    }
    List<PrintFileRow> cleanupCandidates(long now, int limit) {
        return jdbc.query("SELECT " + FIELDS + " FROM print_file WHERE "
                        + "(expires_at_ms<=? AND status<>'DELETED') OR "
                        + "(status IN ('FAILED','DELETED') AND blob_deleted_at_ms IS NULL) "
                        + "ORDER BY expires_at_ms,id LIMIT ?", MAPPER, now, limit);
    }
    void purged(PrintFileRow row, long now) {
        jdbc.update("UPDATE print_file SET blob_deleted_at_ms=? WHERE " + OWNER + " AND status IN ('FAILED','DELETED')",
                now, row.id(), row.tenantId(), row.storeId(), row.ownerUserId());
    }
    static PrintScope scope(PrintFileRow row) {
        return new PrintScope(row.tenantId(), row.storeId(), row.ownerUserId());
    }
}
