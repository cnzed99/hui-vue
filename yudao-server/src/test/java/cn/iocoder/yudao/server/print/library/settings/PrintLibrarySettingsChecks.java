package cn.iocoder.yudao.server.print.library.settings;

import cn.iocoder.yudao.server.print.scope.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import java.util.*;

/** Actual Java source exercised against recording JDBC and an in-memory transaction model; no DB/HTTP. */
public final class PrintLibrarySettingsChecks {
    static final ObjectMapper JSON=new ObjectMapper();static int checks;
    static final PrintScope SCOPE=new PrintScope(1,10,7);
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
    static ObjectNode body(String version){return JSON.createObjectNode().put("expectedVersion",version).put("name","演示文库店").put("description","欢迎选购文库文件");}
    static void rejected(Runnable action){try{action.run();}catch(PrintLibrarySettingsException e){checks++;return;}throw new AssertionError("not_rejected");}
    static class Memory extends PrintLibrarySettingsRepository {
        PrintLibrarySettings value=PrintLibrarySettings.of(0,false,"","");PrintScope observed;int writes;boolean fail;
        Memory(){super(null);}
        public Optional<PrintLibrarySettings> current(PrintScope scope){observed=scope;return Optional.of(value);}
        public boolean lockCurrentStore(PrintScope scope){observed=scope;return true;}
        public boolean replace(PrintScope scope,PrintLibrarySettingsCommand c,boolean configured){observed=scope;if(fail)throw new IllegalStateException();if(!value.version().equals(Long.toString(c.expectedVersion())))return false;value=PrintLibrarySettings.of(c.expectedVersion()+1,true,c.name(),c.description());writes++;return true;}
    }
    static class Transaction extends AbstractPlatformTransactionManager {
        final Memory repo;PrintLibrarySettings before;int writes;
        Transaction(Memory repo){this.repo=repo;}
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object tx,TransactionDefinition def){before=repo.value;writes=repo.writes;}
        protected void doCommit(DefaultTransactionStatus status){}
        protected void doRollback(DefaultTransactionStatus status){repo.value=before;repo.writes=writes;}
    }
    static class Recording extends JdbcTemplate {
        String sql;Object[] args;
        public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args){this.sql=sql;this.args=args;return List.of();}
        public int update(String sql,Object...args){this.sql=sql;this.args=args;return 1;}
    }
    public static void main(String[] args){
        check(PrintLibrarySettingsCommand.parse(body("0")).name().equals("演示文库店"),"parse_success");
        ObjectNode b=body("0");b.put("name","短");rejected(()->PrintLibrarySettingsCommand.parse(b));
        ObjectNode d=body("0");d.put("description","字".repeat(31));rejected(()->PrintLibrarySettingsCommand.parse(d));
        ObjectNode scope=body("0");scope.put("storeId",99);rejected(()->PrintLibrarySettingsCommand.parse(scope));
        ObjectNode path=body("0");path.put("miniProgramPath","forged");rejected(()->PrintLibrarySettingsCommand.parse(path));
        ObjectNode version=body("9223372036854775807");rejected(()->PrintLibrarySettingsCommand.parse(version));
        Memory repo=new Memory();PrintScopeService scopes=new PrintScopeService(null){public PrintScope requireCurrent(){return SCOPE;}};
        var service=new PrintLibrarySettingsService(scopes,repo,new Transaction(repo));
        check(!service.get().configured()&&repo.writes==0,"get_missing_zero_writes");
        var saved=service.put(body("0"));check(saved.version().equals("1")&&saved.configured()&&repo.observed.equals(SCOPE),"save_server_scope");
        rejected(()->service.put(body("0")));check(repo.writes==1,"stale_conflict_no_retry");
        check(service.get().equals(saved)&&saved.miniProgramPath()==null,"readback_and_unconfigured_path");
        repo.fail=true;try{service.put(body("1"));throw new AssertionError("injection");}catch(IllegalStateException expected){}
        check(repo.value.equals(saved)&&repo.writes==1,"transaction_failure_retains_state");
        Recording jdbc=new Recording();var sqlRepo=new PrintLibrarySettingsRepository(jdbc);
        sqlRepo.current(SCOPE);check(jdbc.sql.contains("p.tenant_id=s.tenant_id")&&Arrays.equals(jdbc.args,new Object[]{1L,10L}),"read_scope_bindings");
        sqlRepo.lockCurrentStore(SCOPE);check(jdbc.sql.endsWith("FOR UPDATE")&&Arrays.equals(jdbc.args,new Object[]{1L,10L}),"parent_lock_scope");
        sqlRepo.replace(SCOPE,PrintLibrarySettingsCommand.parse(body("1")),true);
        check(jdbc.sql.contains("WHERE tenant_id=? AND store_id=? AND version=?")&&jdbc.args[3].equals(1L)&&jdbc.args[4].equals(10L)&&jdbc.args[5].equals(1L),"cas_bindings");
        System.out.println("{\"kind\":\"P14_JAVA_SOURCE_MEMORY_MODEL\",\"checksPassed\":"+checks+",\"sqlExecuted\":false,\"httpExecuted\":false}");
    }
}
