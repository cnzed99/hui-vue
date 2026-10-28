package cn.iocoder.yudao.server.print.library.settings;

import cn.iocoder.yudao.server.print.scope.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.iocoder.yudao.server.print.library.settings.PrintLibrarySettingsException.Reason.*;

@Service
public class PrintLibrarySettingsService {
    private final PrintScopeService scopes;
    private final PrintLibrarySettingsRepository repository;
    private final TransactionTemplate transaction;
    public PrintLibrarySettingsService(PrintScopeService scopes,PrintLibrarySettingsRepository repository,PlatformTransactionManager manager){
        this.scopes=scopes;this.repository=repository;this.transaction=new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public PrintLibrarySettings get(){return current(scopes.requireCurrent());}
    public PrintLibrarySettings put(JsonNode body){
        var command=PrintLibrarySettingsCommand.parse(body);
        return transaction.execute(status->{
            PrintScope scope=scopes.requireCurrent();
            if(!repository.lockCurrentStore(scope))throw new PrintLibrarySettingsException(NOT_AVAILABLE);
            var before=current(scope);
            if(!before.version().equals(Long.toString(command.expectedVersion()))||!repository.replace(scope,command,before.configured()))
                throw new PrintLibrarySettingsException(VERSION_CONFLICT);
            return current(scope);
        });
    }
    private PrintLibrarySettings current(PrintScope scope){return repository.current(scope).orElseThrow(()->new PrintLibrarySettingsException(NOT_AVAILABLE));}
}
