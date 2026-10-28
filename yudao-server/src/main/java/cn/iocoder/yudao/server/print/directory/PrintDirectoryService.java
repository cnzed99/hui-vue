package cn.iocoder.yudao.server.print.directory;
import cn.iocoder.yudao.server.print.scope.PrintScopeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.iocoder.yudao.server.print.directory.PrintDirectoryModel.*;
@Service
public class PrintDirectoryService {
    private final PrintScopeService scopes;private final PrintDirectoryRepository repository;
    public PrintDirectoryService(PrintScopeService scopes,PrintDirectoryRepository repository){this.scopes=scopes;this.repository=repository;}
    @Transactional(readOnly=true)
    public Page list(Kind kind,Map<String,List<String>> params){
        var q=PrintDirectoryQuery.parse(kind,params);var scope=scopes.requireCurrent();
        if(kind==Kind.TERMINAL&&Set.of("ONLINE","OFFLINE").contains(q.online()))throw new PrintDirectoryException(422,1510090003,"心跳未接入，无法判断在线或离线");
        return repository.list(scope,kind,q);
    }
}
