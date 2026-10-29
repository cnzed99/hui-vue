package cn.iocoder.yudao.server.print.email;

import cn.iocoder.yudao.server.print.scope.PrintScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class PrintEmailSettingsRepository {
    private final JdbcTemplate jdbc;
    public PrintEmailSettingsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<PrintEmailSettings> current(PrintScope s) {
        return jdbc.query("SELECT e.version,e.username,e.smtp_host,e.smtp_port,(CHAR_LENGTH(e.password_ciphertext)>0) password_configured "
                        + "FROM print_store s LEFT JOIN print_email_settings e ON e.tenant_id=s.tenant_id AND e.store_id=s.id "
                        + "WHERE s.tenant_id=? AND s.id=? AND s.status=0 AND s.deleted=b'0'",
                (rs, n) -> rs.getObject("version") == null ? PrintEmailSettings.unconfigured()
                        : PrintEmailSettings.of(rs.getLong("version"), true, rs.getString("username"),
                                rs.getString("smtp_host"), rs.getInt("smtp_port"), rs.getBoolean("password_configured")),
                s.tenantId(), s.storeId()).stream().findFirst();
    }

    public boolean lockCurrentStore(PrintScope s) {
        return jdbc.query("SELECT id FROM print_store WHERE tenant_id=? AND id=? AND status=0 AND deleted=b'0' FOR UPDATE",
                (rs, n) -> rs.getLong("id"), s.tenantId(), s.storeId()).size() == 1;
    }

    public boolean replace(PrintScope s, PrintEmailSettingsCommand c, boolean configured,
            PrintEmailSettingsSecretCodec.EncryptedSecret secret) {
        if (!configured) {
            if (c.expectedVersion() != 0 || secret == null) return false;
            return jdbc.update("INSERT INTO print_email_settings(tenant_id,store_id,version,username,smtp_host,smtp_port,"
                            + "password_ciphertext,password_nonce,password_key_version,creator,updater) VALUES(?,?,1,?,?,?,?,?,?,?,?)",
                    s.tenantId(), s.storeId(), c.username(), c.smtpHost(), c.smtpPort(), secret.ciphertext(),
                    secret.nonce(), secret.keyVersion(), Long.toString(s.userId()), Long.toString(s.userId())) == 1;
        }
        return jdbc.update("UPDATE print_email_settings SET version=version+1,username=?,smtp_host=?,smtp_port=?,"
                        + "password_ciphertext=COALESCE(?,password_ciphertext),password_nonce=COALESCE(?,password_nonce),"
                        + "password_key_version=COALESCE(?,password_key_version),updater=?,update_time=CURRENT_TIMESTAMP "
                        + "WHERE tenant_id=? AND store_id=? AND version=?",
                c.username(), c.smtpHost(), c.smtpPort(), secret == null ? null : secret.ciphertext(),
                secret == null ? null : secret.nonce(), secret == null ? null : secret.keyVersion(),
                Long.toString(s.userId()), s.tenantId(), s.storeId(), c.expectedVersion()) == 1;
    }
}
