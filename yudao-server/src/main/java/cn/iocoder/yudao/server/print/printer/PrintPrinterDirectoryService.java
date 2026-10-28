package cn.iocoder.yudao.server.print.printer;
import cn.iocoder.yudao.server.print.scope.PrintScopeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
@Service
public class PrintPrinterDirectoryService {
    private final PrintScopeService scopes;private final PrintPrinterDirectoryRepository repository;
    public PrintPrinterDirectoryService(PrintScopeService scopes,PrintPrinterDirectoryRepository repository){this.scopes=scopes;this.repository=repository;}
    @Transactional(readOnly=true)
    public PrintPrinterDirectoryModel.Page list(Map<String,List<String>> params){var query=PrintPrinterDirectoryQuery.parse(params);return repository.list(scopes.requireCurrent(),query);}
}
