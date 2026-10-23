package cn.iocoder.yudao.server.print.quote;

import cn.iocoder.yudao.server.print.scope.PrintScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class PrintQuoteRepository {
    record Profile(String id, String key, String version, String mode, String currency,
            String simplex, String duplex, String tierJson) { }
    record File(String id, String sha256, Integer pages, String status, long expiresAtMs) { }
    record Quote(String id, String fileId, String inputHash, String json, long expiresAtMs) { }
    private static final RowMapper<Quote> QUOTE_MAPPER = (rs,n) -> new Quote(rs.getString("id"),
            rs.getString("file_id"),rs.getString("input_sha256"),rs.getString("snapshot_json"),rs.getLong("expires_at_ms"));
    private final JdbcTemplate jdbc;
    public PrintQuoteRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    void serializeOwner(PrintScope scope) {
        // A transaction-scoped database lock serializes same-key requests even across app processes.
        List<Long> rows = jdbc.query("SELECT id FROM print_staff_store WHERE tenant_id=? AND store_id=? "
                + "AND user_id=? AND status=0 AND deleted=0 FOR UPDATE", (rs,n) -> rs.getLong(1),
                scope.tenantId(),scope.storeId(),scope.userId());
        if (rows.size()!=1) throw new PrintQuoteException(PrintQuoteException.Reason.SCOPE_CHANGED);
    }
    Optional<Quote> idempotent(PrintScope scope, String keyHash) {
        return jdbc.query("SELECT id,file_id,input_sha256,snapshot_json,expires_at_ms FROM print_quote "
                + "WHERE tenant_id=? AND store_id=? AND owner_user_id=? AND idempotency_sha256=? FOR UPDATE",
                QUOTE_MAPPER,scope.tenantId(),scope.storeId(),scope.userId(),keyHash).stream().findFirst();
    }
    Optional<Quote> owned(PrintScope scope, String id) {
        return jdbc.query("SELECT id,file_id,input_sha256,snapshot_json,expires_at_ms FROM print_quote "
                + "WHERE tenant_id=? AND store_id=? AND owner_user_id=? AND id=?", QUOTE_MAPPER,
                scope.tenantId(),scope.storeId(),scope.userId(),id).stream().findFirst();
    }
    Optional<File> fileForUpdate(PrintScope scope, String id) {
        return jdbc.query("SELECT id,content_sha256,page_count,status,expires_at_ms FROM print_file "
                + "WHERE tenant_id=? AND store_id=? AND owner_user_id=? AND id=? FOR UPDATE",
                (rs,n) -> new File(rs.getString("id"),rs.getString("content_sha256"),
                        rs.getObject("page_count",Integer.class),rs.getString("status"),rs.getLong("expires_at_ms")),
                scope.tenantId(),scope.storeId(),scope.userId(),id).stream().findFirst();
    }
    Optional<Profile> profile(PrintScope scope, String id) {
        return jdbc.query("SELECT id,profile_key,version,mode,currency,simplex_price_per_sheet,"
                + "duplex_price_per_sheet,tier_json FROM print_price_profile WHERE tenant_id=? AND store_id=? AND id=?",
                (rs,n) -> new Profile(rs.getString("id"),rs.getString("profile_key"),rs.getString("version"),
                        rs.getString("mode"),rs.getString("currency"),rs.getString("simplex_price_per_sheet"),
                        rs.getString("duplex_price_per_sheet"),rs.getString("tier_json")),
                scope.tenantId(),scope.storeId(),id).stream().findFirst();
    }
    void insert(PrintScope scope, String id, String fileId, String profileId, String keyHash,
            String inputHash, String json, long now, long expires) {
        jdbc.update("INSERT INTO print_quote(id,tenant_id,store_id,owner_user_id,file_id,price_profile_id,"
                + "idempotency_sha256,input_sha256,snapshot_json,created_at_ms,expires_at_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                id,scope.tenantId(),scope.storeId(),scope.userId(),fileId,profileId,keyHash,inputHash,json,now,expires);
    }
}
