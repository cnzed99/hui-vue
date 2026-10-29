package cn.iocoder.yudao.server.print.reservation;

import cn.iocoder.yudao.server.print.scope.*;
import cn.iocoder.yudao.server.print.controller.admin.PrintReservationSettingsController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.util.MultiValueMap;
import java.util.*;

/** Actual production Java against recording JDBC and in-memory transactions; no DB or HTTP. */
public final class PrintReservationSettingsChecks {
    static final ObjectMapper JSON=new ObjectMapper(); static int checks;
    static final PrintScope SCOPE=new PrintScope(1,10,7);
    static void check(boolean value,String name) { if(!value)throw new AssertionError(name); checks++; }
    static ObjectNode body(String version) { return JSON.createObjectNode().put("expectedVersion",version)
        .put("pickupEnabled",true).put("timedDeliveryEnabled",false).put("vipDeliveryEnabled",true)
        .put("pickupCodePrefix","A1").put("automaticPrintEnabled",true); }
    static void rejected(Runnable action,PrintReservationSettingsException.Reason reason) {
        try { action.run(); } catch(PrintReservationSettingsException e) { check(e.reason==reason,"expected_reason"); return; }
        throw new AssertionError("not_rejected");
    }
    static class Memory extends PrintReservationSettingsRepository {
        PrintReservationSettings value=PrintReservationSettings.unconfigured(); PrintScope observed; int writes;
        Memory() { super(null); }
        public Optional<PrintReservationSettings> current(PrintScope s) { observed=s; return Optional.of(value); }
        public boolean lockCurrentStore(PrintScope s) { observed=s; return true; }
        public boolean replace(PrintScope s,PrintReservationSettingsCommand c,boolean configured) {
            observed=s; if(!value.version().equals(Long.toString(c.expectedVersion())))return false;
            value=PrintReservationSettings.of(c.expectedVersion()+1,true,c.pickupEnabled(),c.timedDeliveryEnabled(),c.vipDeliveryEnabled(),c.pickupCodePrefix(),c.automaticPrintEnabled()); writes++; return true;
        }
    }
    static class Transaction extends AbstractPlatformTransactionManager {
        final Memory repo; PrintReservationSettings before; int writes;
        Transaction(Memory repo) { this.repo=repo; }
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object tx,TransactionDefinition def) { before=repo.value; writes=repo.writes; }
        protected void doCommit(DefaultTransactionStatus status) { }
        protected void doRollback(DefaultTransactionStatus status) { repo.value=before; repo.writes=writes; }
    }
    static class Recording extends JdbcTemplate {
        String sql; Object[] args;
        public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args) { this.sql=sql;this.args=args;return List.of(); }
        public int update(String sql,Object...args) { this.sql=sql;this.args=args;return 1; }
    }
    public static void main(String[] args) throws Exception {
        // One valid command plus principal malformed/scope-injection cases.
        check(PrintReservationSettingsCommand.parse(body("0")).pickupCodePrefix().equals("A1"),"five_fields_parse");
        ObjectNode invalid=body("0"); invalid.put("pickupCodePrefix","中A");
        rejected(()->PrintReservationSettingsCommand.parse(invalid),PrintReservationSettingsException.Reason.INVALID_INPUT);
        ObjectNode typed=body("0"); typed.put("pickupEnabled",1);
        rejected(()->PrintReservationSettingsCommand.parse(typed),PrintReservationSettingsException.Reason.INVALID_INPUT);
        ObjectNode injected=body("0"); injected.put("storeId",99);
        rejected(()->PrintReservationSettingsCommand.parse(injected),PrintReservationSettingsException.Reason.INVALID_INPUT);
        Memory repo=new Memory(); PrintScopeService scopes=new PrintScopeService(null) { public PrintScope requireCurrent(){return SCOPE;} };
        var disabled=new PrintReservationSettingsService(scopes,repo,new Transaction(repo),false);
        rejected(()->disabled.put(body("0")),PrintReservationSettingsException.Reason.WRITE_DISABLED);
        check(repo.writes==0,"default_gate_zero_writes");
        var service=new PrintReservationSettingsService(scopes,repo,new Transaction(repo),true);
        check(!service.get().configured()&&repo.writes==0,"get_unconfigured_no_seed");
        var saved=service.put(body("0"));
        check(saved.version().equals("1")&&saved.configured()&&saved.pickupEnabled()&&saved.vipDeliveryEnabled()
                &&!saved.timedDeliveryEnabled()&&saved.automaticPrintEnabled()&&saved.pickupCodePrefix().equals("A1"),"save_readback");
        check(saved.automaticPrintExecutionStatus().equals("SIMULATED_CONFIG_ONLY")&&saved.storeSettingsLinkStatus().equals("PENDING_CONFIRMATION"),"explicit_simulation_and_linkage");
        rejected(()->service.put(body("0")),PrintReservationSettingsException.Reason.VERSION_CONFLICT);
        check(repo.writes==1&&service.get().equals(saved)&&repo.observed.equals(SCOPE),"stale_request_no_second_write");
        Recording jdbc=new Recording(); var sql=new PrintReservationSettingsRepository(jdbc);
        sql.current(SCOPE); check(jdbc.sql.contains("p.tenant_id=s.tenant_id AND p.store_id=s.id")&&Arrays.equals(jdbc.args,new Object[]{1L,10L}),"read_scope_bindings");
        sql.lockCurrentStore(SCOPE); check(jdbc.sql.endsWith("FOR UPDATE")&&Arrays.equals(jdbc.args,new Object[]{1L,10L}),"store_lock_bindings");
        sql.replace(SCOPE,PrintReservationSettingsCommand.parse(body("1")),true);
        check(jdbc.sql.contains("WHERE tenant_id=? AND store_id=? AND version=?")&&Arrays.equals(Arrays.copyOfRange(jdbc.args,6,9),new Object[]{1L,10L,1L}),"update_scope_cas_bindings");
        var get=PrintReservationSettingsController.class.getMethod("get",MultiValueMap.class).getAnnotation(PreAuthorize.class).value();
        var put=PrintReservationSettingsController.class.getMethod("put",com.fasterxml.jackson.databind.JsonNode.class,MultiValueMap.class).getAnnotation(PreAuthorize.class).value();
        check(get.equals("@ss.hasPermission('print:store:query')")&&put.equals("@ss.hasPermission('print:store:update')"),"existing_read_write_permission_mapping");
        System.out.println("{\"kind\":\"P06_JAVA_RECORDING_PORTS\",\"checksPassed\":"+checks+",\"database\":false,\"http\":false,\"springSecurityIntegration\":false}");
    }
}
