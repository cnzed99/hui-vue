package cn.iocoder.yudao.server.print.reservation;

import cn.iocoder.yudao.server.print.scope.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.iocoder.yudao.server.print.reservation.PrintReservationSettingsException.Reason.*;

@Service
public class PrintReservationSettingsService {
    private final PrintScopeService scopes;
    private final PrintReservationSettingsRepository repository;
    private final TransactionTemplate transaction;
    private final boolean writeEnabled;
    public PrintReservationSettingsService(PrintScopeService scopes, PrintReservationSettingsRepository repository,
            PlatformTransactionManager manager, @Value("${print.reservation-settings-write-enabled:false}") boolean writeEnabled) {
        this.scopes=scopes; this.repository=repository; this.writeEnabled=writeEnabled;
        this.transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public PrintReservationSettings get() { return current(scopes.requireCurrent()); }
    public PrintReservationSettings put(JsonNode body) {
        if (!writeEnabled) throw new PrintReservationSettingsException(WRITE_DISABLED);
        var command=PrintReservationSettingsCommand.parse(body);
        return transaction.execute(status->{
            PrintScope scope=scopes.requireCurrent();
            if (!repository.lockCurrentStore(scope)) throw new PrintReservationSettingsException(NOT_AVAILABLE);
            var before=current(scope);
            if (!before.version().equals(Long.toString(command.expectedVersion())) || !repository.replace(scope,command,before.configured()))
                throw new PrintReservationSettingsException(VERSION_CONFLICT);
            return current(scope);
        });
    }
    private PrintReservationSettings current(PrintScope scope) { return repository.current(scope).orElseThrow(()->new PrintReservationSettingsException(NOT_AVAILABLE)); }
}
