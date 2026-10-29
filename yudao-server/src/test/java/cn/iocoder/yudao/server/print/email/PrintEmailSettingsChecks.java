package cn.iocoder.yudao.server.print.email;

import cn.iocoder.yudao.server.print.controller.admin.PrintEmailSettingsController;
import cn.iocoder.yudao.server.print.scope.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.util.MultiValueMap;
import java.util.*;

/** Production Java exercised against in-memory/recording ports; no DB, SMTP or HTTP. */
public final class PrintEmailSettingsChecks {
    static final ObjectMapper JSON = new ObjectMapper(); static int checks;
    static final PrintScope SCOPE = new PrintScope(1, 10, 7);
    static void check(boolean value, String name) { if (!value) throw new AssertionError(name); checks++; }
    static ObjectNode body(String version, String password) {
        return JSON.createObjectNode().put("expectedVersion", version).put("username", "printer@example.test")
                .put("password", password).put("smtpHost", "smtp.example.test").put("smtpPort", 465);
    }
    static void rejected(Runnable action, PrintEmailSettingsException.Reason reason) {
        try { action.run(); } catch (PrintEmailSettingsException e) { check(e.reason == reason, "expected_reason"); return; }
        throw new AssertionError("not_rejected");
    }
    static class Memory extends PrintEmailSettingsRepository {
        PrintEmailSettings value = PrintEmailSettings.unconfigured(); PrintScope observed; int writes;
        String cipher; String passwordMarker;
        Memory() { super(null); }
        public Optional<PrintEmailSettings> current(PrintScope s) { observed = s; return Optional.of(value); }
        public boolean lockCurrentStore(PrintScope s) { observed = s; return true; }
        public boolean replace(PrintScope s, PrintEmailSettingsCommand c, boolean configured,
                PrintEmailSettingsSecretCodec.EncryptedSecret secret) {
            observed = s; if (!value.version().equals(Long.toString(c.expectedVersion()))) return false;
            if (!configured && secret == null) return false;
            if (secret != null) { cipher = secret.ciphertext(); passwordMarker = c.password(); }
            value = PrintEmailSettings.of(c.expectedVersion() + 1, true, c.username(), c.smtpHost(), c.smtpPort(), true);
            writes++; return true;
        }
    }
    static class Transaction extends AbstractPlatformTransactionManager {
        final Memory repo; PrintEmailSettings before; int writes; String cipher;
        Transaction(Memory repo) { this.repo = repo; }
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object tx, TransactionDefinition def) { before = repo.value; writes = repo.writes; cipher = repo.cipher; }
        protected void doCommit(DefaultTransactionStatus status) { }
        protected void doRollback(DefaultTransactionStatus status) { repo.value = before; repo.writes = writes; repo.cipher = cipher; }
    }
    static class Recording extends JdbcTemplate {
        String sql; Object[] args;
        public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) { this.sql = sql; this.args = args; return List.of(); }
        public int update(String sql, Object... args) { this.sql = sql; this.args = args; return 1; }
    }
    public static void main(String[] args) throws Exception {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        PrintEmailSettingsSecretCodec codec = new PrintEmailSettingsSecretCodec(key, "v1");
        check(codec.available(), "codec_available");
        var encrypted = codec.encrypt(1, 10, "secret-value");
        check(!encrypted.ciphertext().contains("secret-value") && encrypted.nonce().length() == 16, "ciphertext_not_plaintext");
        check(PrintEmailSettingsCommand.parse(body("0", "secret-value")).smtpPort() == 465, "four_fields_parse");
        ObjectNode injected = body("0", "secret-value"); injected.put("storeId", 99);
        rejected(() -> PrintEmailSettingsCommand.parse(injected), PrintEmailSettingsException.Reason.INVALID_INPUT);
        ObjectNode badPort = body("0", "secret-value"); badPort.put("smtpPort", 70000);
        rejected(() -> PrintEmailSettingsCommand.parse(badPort), PrintEmailSettingsException.Reason.INVALID_INPUT);
        Memory repo = new Memory(); PrintScopeService scopes = new PrintScopeService(null) { public PrintScope requireCurrent() { return SCOPE; } };
        var disabled = new PrintEmailSettingsService(scopes, repo, codec, new Transaction(repo), false);
        rejected(() -> disabled.put(body("0", "secret-value")), PrintEmailSettingsException.Reason.WRITE_DISABLED);
        check(repo.writes == 0, "default_gate_zero_writes");
        var service = new PrintEmailSettingsService(scopes, repo, codec, new Transaction(repo), true);
        check(!service.get().configured() && repo.writes == 0, "get_unconfigured_no_seed");
        rejected(() -> service.put(body("0", "")), PrintEmailSettingsException.Reason.INVALID_INPUT);
        var saved = service.put(body("0", "secret-value"));
        check(saved.version().equals("1") && saved.configured() && saved.passwordConfigured()
                && saved.sendingStatus().equals("SIMULATED_NOT_SENT") && saved.tlsStatus().equals("UNCONFIRMED_NOT_CONFIGURED"), "save_readback_and_simulation");
        check(repo.cipher != null && !repo.cipher.contains("secret-value") && repo.observed.equals(SCOPE), "encrypted_scope_write");
        String firstCipher = repo.cipher;
        var updated = service.put(body("1", ""));
        check(updated.version().equals("2") && repo.cipher.equals(firstCipher), "blank_password_preserves_secret");
        rejected(() -> service.put(body("1", "")), PrintEmailSettingsException.Reason.VERSION_CONFLICT);
        Recording jdbc = new Recording(); var sql = new PrintEmailSettingsRepository(jdbc);
        sql.current(SCOPE); check(jdbc.sql.contains("e.tenant_id=s.tenant_id AND e.store_id=s.id")
                && Arrays.equals(jdbc.args, new Object[]{1L, 10L}), "read_scope_bindings");
        sql.replace(SCOPE, PrintEmailSettingsCommand.parse(body("2", "")), true, null);
        check(jdbc.sql.contains("WHERE tenant_id=? AND store_id=? AND version=?")
                && Arrays.equals(Arrays.copyOfRange(jdbc.args, 7, 10), new Object[]{1L, 10L, 2L}), "update_scope_cas_bindings");
        var get = PrintEmailSettingsController.class.getMethod("get", MultiValueMap.class).getAnnotation(PreAuthorize.class).value();
        var put = PrintEmailSettingsController.class.getMethod("put", com.fasterxml.jackson.databind.JsonNode.class, MultiValueMap.class).getAnnotation(PreAuthorize.class).value();
        check(get.equals("@ss.hasPermission('print:store:query')") && put.equals("@ss.hasPermission('print:store:update')"), "existing_permission_mapping");
        check(!PrintEmailSettings.unconfigured().passwordConfigured(), "password_never_returned");
        System.out.println("{\"kind\":\"P35_JAVA_RECORDING_PORTS\",\"checksPassed\":" + checks
                + ",\"database\":false,\"smtp\":false,\"http\":false,\"springSecurityIntegration\":false}");
    }
}
