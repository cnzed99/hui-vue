package cn.iocoder.yudao.server.print.printer;
import cn.iocoder.yudao.server.print.scope.PrintScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import static cn.iocoder.yudao.server.print.printer.PrintPrinterDirectoryModel.*;
@Repository
public class PrintPrinterDirectoryRepository {
    private final JdbcTemplate jdbc;
    public PrintPrinterDirectoryRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String ACTIVE=" FROM print_printer_directory p JOIN print_terminal_directory t ON t.tenant_id=p.tenant_id AND t.store_id=p.store_id AND t.owner_user_id=p.owner_user_id AND t.id=p.terminal_id JOIN print_store s ON s.tenant_id=p.tenant_id AND s.id=p.store_id WHERE p.tenant_id=? AND p.store_id=? AND p.owner_user_id=? AND s.status=0 AND s.deleted=b'0'";
    public Page list(PrintScope scope,PrintPrinterDirectoryQuery query){
        String from=ACTIVE;List<Object> params=new ArrayList<>(List.of(scope.tenantId(),scope.storeId(),scope.userId()));
        if(!query.terminalId().isEmpty()){from+=" AND p.terminal_id=?";params.add(query.terminalId());}
        Long total=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,params.toArray());
        List<Object> paging=new ArrayList<>(params);paging.add(query.pageSize());paging.add((query.page()-1L)*query.pageSize());
        List<Printer> rows=jdbc.query("SELECT p.id,p.name,p.terminal_id,t.name AS terminal_name,p.address,p.default_printer,p.mobile_self_service,p.updated_at"+from+" ORDER BY t.name,t.id,p.name,p.id LIMIT ? OFFSET ?",(rs,n)->new Printer(rs.getString("id"),rs.getString("name"),rs.getString("terminal_id"),rs.getString("terminal_name"),rs.getString("address"),nullableBoolean(rs,"default_printer"),nullableBoolean(rs,"mobile_self_service"),rs.getString("updated_at"),"NOT_REPORTED"),paging.toArray());
        List<TerminalOption> terminals=jdbc.query("SELECT t.id,t.name,t.host_name,t.mobile_self_service FROM print_terminal_directory t JOIN print_store s ON s.tenant_id=t.tenant_id AND s.id=t.store_id WHERE t.tenant_id=? AND t.store_id=? AND t.owner_user_id=? AND s.status=0 AND s.deleted=b'0' ORDER BY t.name,t.id LIMIT 1001",(rs,n)->new TerminalOption(rs.getString("id"),rs.getString("name"),rs.getString("host_name"),nullableBoolean(rs,"mobile_self_service")),scope.tenantId(),scope.storeId(),scope.userId());
        if(terminals.size()>1000)throw new PrintPrinterDirectoryException(503,1510100002,"终端目录过大，当前侧栏暂不可用");
        return new Page(rows,total==null?0:total,query.page(),query.pageSize(),terminals,"DEVICE_REPORT_NOT_CONNECTED");
    }
    private static Boolean nullableBoolean(java.sql.ResultSet rs,String key)throws java.sql.SQLException{boolean value=rs.getBoolean(key);return rs.wasNull()?null:value;}
}
