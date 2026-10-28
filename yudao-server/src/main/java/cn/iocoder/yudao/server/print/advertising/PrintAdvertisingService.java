package cn.iocoder.yudao.server.print.advertising;
import cn.iocoder.yudao.server.print.scope.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.iocoder.yudao.server.print.advertising.PrintAdvertisingException.Reason.*;
@Service
public class PrintAdvertisingService {
    private final PrintScopeService scopes; private final PrintAdvertisingRepository repository; private final TransactionTemplate transaction;
    public PrintAdvertisingService(PrintScopeService scopes,PrintAdvertisingRepository repository,PlatformTransactionManager manager){this.scopes=scopes;this.repository=repository;transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    public PrintAdvertisingDraft get(PrintAdPosition p){return current(scopes.requireCurrent(),p);}
    public PrintAdvertisingDraft put(PrintAdPosition p,JsonNode body){
        var command=PrintAdvertisingCommand.parse(p,body);
        return transaction.execute(status->{
            var scope=scopes.requireCurrent();if(!repository.lockStore(scope))throw new PrintAdvertisingException(UNAVAILABLE);
            var before=current(scope,p);if(!before.version().equals(Long.toString(command.expectedVersion())))throw new PrintAdvertisingException(CONFLICT);
            // Until deletion semantics are confirmed, existing slots cannot be removed by a replacement save.
            if(before.assets().stream().anyMatch(old->command.assets().stream().noneMatch(a->a.slot().equals(old.slot()))))throw new PrintAdvertisingException(PENDING);
            if(command.assets().stream().anyMatch(a->!repository.existingImage(scope,p,a.url())))throw new PrintAdvertisingException(ASSET);
            if(!repository.replace(scope,p,command))throw new PrintAdvertisingException(CONFLICT);
            return current(scope,p);
        });
    }
    private PrintAdvertisingDraft current(PrintScope s,PrintAdPosition p){return repository.current(s,p).orElseThrow(()->new PrintAdvertisingException(UNAVAILABLE));}
}
