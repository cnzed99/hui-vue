package cn.iocoder.yudao.server.print.email;

import cn.iocoder.yudao.server.print.scope.PrintScope;
import cn.iocoder.yudao.server.print.scope.PrintScopeService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.iocoder.yudao.server.print.email.PrintEmailSettingsException.Reason.*;

@Service
public class PrintEmailSettingsService {
    private final PrintScopeService scopes;
    private final PrintEmailSettingsRepository repository;
    private final PrintEmailSettingsSecretCodec secrets;
    private final TransactionTemplate transaction;
    private final boolean writeEnabled;

    public PrintEmailSettingsService(PrintScopeService scopes, PrintEmailSettingsRepository repository,
            PrintEmailSettingsSecretCodec secrets, PlatformTransactionManager manager,
            @Value("${print.email-settings-write-enabled:false}") boolean writeEnabled) {
        this.scopes = scopes; this.repository = repository; this.secrets = secrets; this.writeEnabled = writeEnabled;
        this.transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public PrintEmailSettings get() { return current(scopes.requireCurrent()); }

    public PrintEmailSettings put(JsonNode body) {
        if (!writeEnabled) throw new PrintEmailSettingsException(WRITE_DISABLED);
        PrintEmailSettingsCommand command = PrintEmailSettingsCommand.parse(body);
        return transaction.execute(status -> {
            PrintScope scope = scopes.requireCurrent();
            if (!repository.lockCurrentStore(scope)) throw new PrintEmailSettingsException(NOT_AVAILABLE);
            PrintEmailSettings before = current(scope);
            if (!before.version().equals(Long.toString(command.expectedVersion()))) throw new PrintEmailSettingsException(VERSION_CONFLICT);
            if ((!before.configured() || !before.passwordConfigured()) && command.password().isEmpty()) {
                throw new PrintEmailSettingsException(INVALID_INPUT);
            }
            if (!secrets.available()) throw new PrintEmailSettingsException(SECRET_UNAVAILABLE);
            PrintEmailSettingsSecretCodec.EncryptedSecret encrypted = command.password().isEmpty() ? null
                    : secrets.encrypt(scope.tenantId(), scope.storeId(), command.password());
            if (!repository.replace(scope, command, before.configured(), encrypted)) {
                throw new PrintEmailSettingsException(VERSION_CONFLICT);
            }
            return current(scope);
        });
    }

    private PrintEmailSettings current(PrintScope scope) {
        return repository.current(scope).orElseThrow(() -> new PrintEmailSettingsException(NOT_AVAILABLE));
    }
}
