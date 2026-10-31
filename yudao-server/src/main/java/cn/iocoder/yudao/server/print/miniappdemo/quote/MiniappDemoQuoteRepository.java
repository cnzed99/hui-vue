package cn.iocoder.yudao.server.print.miniappdemo.quote;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class MiniappDemoQuoteRepository {
    record Profile(String id, String key, String version, String mode, String currency,
                   String simplex, String duplex, String tierJson) {}
    record File(String id, String sha256, Integer pages, String status, long expiresAtMs) {}
    record Quote(String id, String fileId, String profileId, String inputHash,
                 String snapshotJson, long createdAtMs, long expiresAtMs) {}

    private static final RowMapper<Quote> QUOTE_MAPPER = (rs, rowNum) -> new Quote(
            rs.getString("id"), rs.getString("file_id"), rs.getString("price_profile_id"),
            rs.getString("input_sha256"), rs.getString("snapshot_json"),
            rs.getLong("created_at_ms"), rs.getLong("expires_at_ms"));
    private final JdbcTemplate jdbc;

    public MiniappDemoQuoteRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<Quote> idempotentForUpdate(MiniappDemoIdentity identity, String keyHash) {
        return jdbc.query("SELECT id,file_id,price_profile_id,input_sha256,snapshot_json,created_at_ms,expires_at_ms "
                        + "FROM print_miniapp_demo_quote WHERE tenant_id=? AND store_id=? AND principal_id=? "
                        + "AND idempotency_sha256=? FOR UPDATE", QUOTE_MAPPER,
                identity.tenantId(), identity.storeId(), identity.principalId(), keyHash).stream().findFirst();
    }

    Optional<Quote> owned(MiniappDemoIdentity identity, String id) {
        return jdbc.query("SELECT id,file_id,price_profile_id,input_sha256,snapshot_json,created_at_ms,expires_at_ms "
                        + "FROM print_miniapp_demo_quote WHERE tenant_id=? AND store_id=? AND principal_id=? AND id=?",
                QUOTE_MAPPER, identity.tenantId(), identity.storeId(), identity.principalId(), id).stream().findFirst();
    }

    Optional<File> fileForUpdate(MiniappDemoIdentity identity, String id) {
        return jdbc.query("SELECT id,content_sha256,page_count,status,expires_at_ms FROM print_miniapp_demo_file "
                        + "WHERE tenant_id=? AND store_id=? AND principal_id=? AND id=? FOR UPDATE",
                (rs, rowNum) -> new File(rs.getString("id"), rs.getString("content_sha256"),
                        rs.getObject("page_count", Integer.class), rs.getString("status"), rs.getLong("expires_at_ms")),
                identity.tenantId(), identity.storeId(), identity.principalId(), id).stream().findFirst();
    }

    Optional<Profile> profileForUpdate(MiniappDemoIdentity identity, String id) {
        return jdbc.query("SELECT id,profile_key,version,mode,currency,simplex_price_per_sheet,"
                        + "duplex_price_per_sheet,tier_json FROM print_price_profile "
                        + "WHERE tenant_id=? AND store_id=? AND id=? FOR UPDATE",
                (rs, rowNum) -> new Profile(rs.getString("id"), rs.getString("profile_key"), rs.getString("version"),
                        rs.getString("mode"), rs.getString("currency"), rs.getString("simplex_price_per_sheet"),
                        rs.getString("duplex_price_per_sheet"), rs.getString("tier_json")),
                identity.tenantId(), identity.storeId(), id).stream().findFirst();
    }

    int activeCount(MiniappDemoIdentity identity, long now) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM print_miniapp_demo_quote "
                        + "WHERE tenant_id=? AND store_id=? AND principal_id=? AND expires_at_ms>?",
                Integer.class, identity.tenantId(), identity.storeId(), identity.principalId(), now);
        return count == null ? 0 : count;
    }

    void insert(MiniappDemoIdentity identity, String id, String fileId, String profileId, String keyHash,
                String inputHash, String snapshotJson, long now, long expiresAt) {
        jdbc.update("INSERT INTO print_miniapp_demo_quote(id,tenant_id,store_id,principal_id,file_id,price_profile_id,"
                        + "idempotency_sha256,input_sha256,snapshot_json,created_at_ms,expires_at_ms) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?)", id, identity.tenantId(), identity.storeId(),
                identity.principalId(), fileId, profileId, keyHash, inputHash, snapshotJson, now, expiresAt);
    }
}
