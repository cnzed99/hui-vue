package cn.iocoder.yudao.server.print.library.category;

import cn.iocoder.yudao.server.print.scope.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import static cn.iocoder.yudao.server.print.library.category.PrintLibraryCategoryException.Reason.*;

@Service
public class PrintLibraryCategoryService {
    public record Page(List<PrintLibraryCategory> list,long total,int page,int pageSize){}
    private final PrintScopeService scopes;private final PrintLibraryCategoryRepository repository;private final TransactionTemplate transaction;
    public PrintLibraryCategoryService(PrintScopeService scopes,PrintLibraryCategoryRepository repository,PlatformTransactionManager manager){
        this.scopes=scopes;this.repository=repository;this.transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public Page list(int page,int size){if(page<1||page>1000000||size<1||size>100)throw new PrintLibraryCategoryException(INVALID_INPUT);var s=scopes.requireCurrent();return new Page(repository.list(s,page,size),repository.count(s),page,size);}
    public PrintLibraryCategory get(String id){PrintLibraryCategoryCommand.id(id);return current(scopes.requireCurrent(),id);}
    public PrintLibraryCategory create(JsonNode body){var c=PrintLibraryCategoryCommand.parse(body,true);return transaction.execute(status->{
        var s=scopes.requireCurrent();if(!repository.lockCurrentStore(s))throw new PrintLibraryCategoryException(NOT_FOUND);
        var existing=repository.get(s,c.requestId());
        if(existing.isPresent()){
            if(!existing.get().version().equals("1")||!existing.get().name().equals(c.name()))throw new PrintLibraryCategoryException(VERSION_CONFLICT);
            return existing.get();
        }
        repository.create(s,c);return current(s,c.requestId());
    });}
    public PrintLibraryCategory update(String id,JsonNode body){PrintLibraryCategoryCommand.id(id);var c=PrintLibraryCategoryCommand.parse(body,false);return transaction.execute(status->{
        var s=scopes.requireCurrent();if(!repository.lockCurrentStore(s))throw new PrintLibraryCategoryException(NOT_FOUND);
        current(s,id);if(!repository.update(s,id,c))throw new PrintLibraryCategoryException(VERSION_CONFLICT);return current(s,id);
    });}
    /** Fail closed: no deletion, reference cascade or file-count mutation has been authorized. */
    public void delete(String id){get(id);throw new PrintLibraryCategoryException(RULE_PENDING);}
    private PrintLibraryCategory current(PrintScope s,String id){return repository.get(s,id).orElseThrow(()->new PrintLibraryCategoryException(NOT_FOUND));}
}
