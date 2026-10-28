package cn.iocoder.yudao.server.print.store.profile;

import cn.iocoder.yudao.server.print.scope.PrintScope;
import cn.iocoder.yudao.server.print.scope.PrintScopeService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.iocoder.yudao.server.print.store.profile.PrintStoreProfileException.Reason.*;

@Service
public class PrintStoreProfileService {
    private final PrintScopeService scopes;
    private final PrintStoreProfileRepository repository;
    private final TransactionTemplate transaction;
    public PrintStoreProfileService(PrintScopeService scopes,PrintStoreProfileRepository repository,PlatformTransactionManager manager) {
        this.scopes=scopes; this.repository=repository; this.transaction=new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public PrintStoreProfileView get() { return current(scopes.requireCurrent()); }
    public PrintStoreProfileView put(JsonNode body) {
        var command=PrintStoreProfileCommand.parse(body);
        return transaction.execute(status->{
            PrintScope scope=scopes.requireCurrent();
            if (!repository.compareAndSet(scope,command)) throw new PrintStoreProfileException(VERSION_CONFLICT);
            repository.replace(scope,command.profile());
            return current(scope);
        }); // TransactionTemplate commits before returning; failures roll back name, profile and version.
    }
    private PrintStoreProfileView current(PrintScope scope) {
        return repository.current(scope).orElseThrow(()->new PrintStoreProfileException(NOT_AVAILABLE));
    }
}
