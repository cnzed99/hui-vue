package cn.iocoder.yudao.server.print.store.profile;

import cn.iocoder.yudao.server.print.controller.admin.PrintStoreProfileController;
import cn.iocoder.yudao.server.print.scope.*;
import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkServiceImpl;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.system.dal.redis.RedisKeyConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import org.springframework.util.LinkedMultiValueMap;
import java.sql.ResultSet;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import static org.mockito.Mockito.*;

/** SOURCE + IN-MEMORY MODEL checks. Does not connect to SQL, Redis or running application. */
public final class PrintStoreProfileChecks {
    static final ObjectMapper JSON=new ObjectMapper();
    static final List<String> checks=new ArrayList<>();
    static final PrintScope SCOPE=new PrintScope(1,10,7);
    static void check(boolean ok,String name) { if(!ok) throw new AssertionError(name); checks.add(name); }
    static void rejects(Runnable action,Class<? extends Throwable> type,String name) {
        try { action.run(); } catch(Throwable e) { check(type.isInstance(e),name); return; }
        throw new AssertionError("did_not_reject:"+name);
    }
    static ObjectNode body(String version) {
        ObjectNode b=JSON.createObjectNode();
        for(String f:new String[]{"notice","provinceCode","provinceName","cityCode","cityName","districtCode","districtName", "streetCode","streetName","address","contact","telephone","qq","openTime","closeTime","businessOpen"}) b.putNull(f);
        b.put("storeName","原门店"); b.put("expectedVersion",version); return b;
    }
    static void dto() {
        check(PrintStoreProfileCommand.parse(body("0")).profile().businessOpen()==null,"nullable_business_unconfigured");
        check(PrintStoreProfileCommand.parse(body("9007199254740993")).expectedVersion()==9007199254740993L,"version_beyond_js_safe_integer");
        for(String field:new String[]{"tenantId","storeId","userId","status","creator","updater","officialAccountQrUrl","miniProgramQrUrl","actorType"}) {
            ObjectNode b=body("0");b.put(field,"forged");rejects(()->PrintStoreProfileCommand.parse(b),PrintStoreProfileException.class,"reject_immutable_"+field);
        }
        ObjectNode incomplete=body("0");incomplete.remove("address");rejects(()->PrintStoreProfileCommand.parse(incomplete),PrintStoreProfileException.class,"reject_missing_full_replacement_field");
        for(String value:new String[]{"-1","01","9223372036854775807","9223372036854775808","1.0",""}) rejects(()->PrintStoreProfileCommand.parse(body(value)),PrintStoreProfileException.class,"reject_version_"+value);
        ObjectNode number=body("0");number.put("expectedVersion",0);rejects(()->PrintStoreProfileCommand.parse(number),PrintStoreProfileException.class,"reject_numeric_version");
        ObjectNode region=body("0");region.put("provinceCode","forged");rejects(()->PrintStoreProfileCommand.parse(region),PrintStoreProfileException.class,"dictionary_unconfigured_rejects_client_region");
        ObjectNode longName=body("0");longName.put("storeName","店".repeat(128));check(PrintStoreProfileCommand.parse(longName).profile().storeName().length()==128,"preserve_name_51_to_128");
        longName.put("storeName","店".repeat(129));rejects(()->PrintStoreProfileCommand.parse(longName),PrintStoreProfileException.class,"reject_name_over_capacity");
        ObjectNode leading=body("0");leading.put("telephone","00123");leading.put("qq","00012");check(PrintStoreProfileCommand.parse(leading).profile().telephone().equals("00123"),"phone_preserves_leading_zero");
        ObjectNode t=body("0");t.put("openTime","23:59");t.put("closeTime","00:00");check(PrintStoreProfileCommand.parse(t).profile().closeTime().equals("00:00"),"cross_day_stored_without_inferred_schedule");
        t.put("closeTime","23:59");check(PrintStoreProfileCommand.parse(t).profile().openTime().equals(t.get("closeTime").textValue()),"equal_times_stored_without_inferred_schedule");
        for(String bad:new String[]{"24:00","12:60","8:00","12:00x"}) { ObjectNode b=body("0");b.put("openTime",bad);b.put("closeTime","12:00");rejects(()->PrintStoreProfileCommand.parse(b),PrintStoreProfileException.class,"reject_time_"+bad); }
        ObjectNode half=body("0");half.put("openTime","08:00");rejects(()->PrintStoreProfileCommand.parse(half),PrintStoreProfileException.class,"reject_half_time_pair");
        ObjectNode b=body("0");b.put("businessOpen",false);check(Boolean.FALSE.equals(PrintStoreProfileCommand.parse(b).profile().businessOpen()),"business_false_separate_from_admission_status");
    }
    static class MemoryRepository extends PrintStoreProfileRepository {
        PrintStoreProfileView state=PrintStoreProfileView.of(0,false,PrintStoreProfileCommand.parse(body("0")).profile());
        int writes=0; boolean fail=false; PrintScope observed;
        MemoryRepository(){super(null);}
        public Optional<PrintStoreProfileView> current(PrintScope s){observed=s;return Optional.of(state);}
        public boolean compareAndSet(PrintScope s,PrintStoreProfileCommand c){observed=s;if(!state.profileVersion().equals(Long.toString(c.expectedVersion())))return false;
            state=PrintStoreProfileView.of(c.expectedVersion()+1,state.configured(),c.profile());writes++;return true;}
        public void replace(PrintScope s,PrintStoreProfileValue p){observed=s;if(fail)throw new IllegalStateException("injected_profile_failure");state=PrintStoreProfileView.of(Long.parseLong(state.profileVersion()),true,p);writes++;}
    }
    static class ModelTransaction extends AbstractPlatformTransactionManager {
        final MemoryRepository repo;final ReentrantLock lock=new ReentrantLock();PrintStoreProfileView before;int writes;
        ModelTransaction(MemoryRepository repo){this.repo=repo;}
        protected Object doGetTransaction(){return new Object();}
        protected void doBegin(Object tx,TransactionDefinition def){lock.lock();before=repo.state;writes=repo.writes;}
        protected void doCommit(DefaultTransactionStatus s){lock.unlock();}
        protected void doRollback(DefaultTransactionStatus s){repo.state=before;repo.writes=writes;lock.unlock();}
    }
    static void service() throws Exception {
        MemoryRepository repo=new MemoryRepository();PrintScopeService scopes=mock(PrintScopeService.class);when(scopes.requireCurrent()).thenReturn(SCOPE);
        PrintStoreProfileService service=new PrintStoreProfileService(scopes,repo,new ModelTransaction(repo));
        check(!service.get().configured()&&repo.writes==0,"get_missing_profile_never_creates");
        ObjectNode b=body("0");b.put("businessOpen",false);b.put("storeName","新名称");var saved=service.put(b);
        check(saved.profileVersion().equals("1")&&saved.configured()&&saved.profile().storeName().equals("新名称"),"transaction_name_profile_version_commit");
        check(repo.observed.equals(SCOPE),"repository_uses_server_resolved_tenant_store");
        check(Boolean.FALSE.equals(service.get().profile().businessOpen()),"closed_business_can_still_read_and_edit");
        rejects(()->service.put(b),PrintStoreProfileException.class,"stale_version_conflict_no_retry");
        check(repo.writes==2,"conflict_no_profile_write");
        var before=repo.state;repo.fail=true;rejects(()->service.put(body("1")),IllegalStateException.class,"profile_failure_propagates");
        check(repo.state.equals(before)&&repo.writes==2,"model_transaction_rolls_back_name_profile_version");repo.fail=false;
        MemoryRepository first=new MemoryRepository();PrintStoreProfileService concurrent=new PrintStoreProfileService(scopes,first,new ModelTransaction(first));
        var pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        Callable<Boolean> save=()->{start.await();try{concurrent.put(body("0"));return true;}catch(PrintStoreProfileException e){return false;}};
        var a=pool.submit(save);var c=pool.submit(save);start.countDown();int successes=(a.get()?1:0)+(c.get()?1:0);pool.shutdown();
        check(successes==1&&first.state.profileVersion().equals("1")&&first.writes==2,"model_first_creation_concurrent_at_most_one_success");
        check(first.state.officialAccountQrUrl()==null&&first.state.officialAccountQrStatus().equals("NOT_CONFIGURED"),"qr_remains_unconfigured");
        verify(scopes,atLeast(1)).requireCurrent();
    }
    static final class RecordingJdbc extends JdbcTemplate {
        String sql;Object[] args;
        @Override public int update(String sql,Object... args){this.sql=sql;this.args=args;return 1;}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args){this.sql=sql;this.args=args;return List.of();}
    }
    static void repository() {
        RecordingJdbc jdbc=new RecordingJdbc();var repo=new PrintStoreProfileRepository(jdbc);
        repo.current(SCOPE);check(jdbc.sql.contains("LEFT JOIN print_store_profile")&&jdbc.sql.contains("p.tenant_id=s.tenant_id")&&Arrays.equals(jdbc.args,new Object[]{1L,10L}),"get_single_join_bound_to_tenant_store");
        repo.compareAndSet(SCOPE,PrintStoreProfileCommand.parse(body("0")));
        check(jdbc.sql.contains("tenant_id=? AND id=? AND status=0")&&jdbc.sql.contains("profile_version=?")&&Arrays.equals(jdbc.args,new Object[]{"原门店","7",1L,10L,0L}),"sql_cas_active_scope_and_expected_version_bindings");
        check(!jdbc.sql.contains("SET status")&&!jdbc.sql.contains("business_open"),"cas_does_not_overwrite_admission_status");
        repo.replace(SCOPE,PrintStoreProfileCommand.parse(body("0")).profile());
        check(jdbc.sql.startsWith("INSERT INTO print_store_profile(tenant_id,store_id,")&&jdbc.args[0].equals(1L)&&jdbc.args[1].equals(10L),"profile_upsert_bound_to_tenant_store");
        check(!jdbc.sql.contains("store_name")&&!jdbc.sql.contains("profile_version")&&!jdbc.sql.contains("qr_url"),"no_duplicate_name_or_qr_in_profile");
    }
    static void auth(long id,long tenant) {
        LoginUser actor=new LoginUser();actor.setId(id);actor.setTenantId(tenant);actor.setUserType(UserTypeEnum.ADMIN.getValue());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,List.of()));
        TenantContextHolder.setTenantId(tenant);TenantContextHolder.setIgnore(false);
    }
    @Configuration @EnableMethodSecurity(proxyTargetClass=true)
    static class SecurityConfiguration {
        @Bean PrintStoreProfileService profiles(){return mock(PrintStoreProfileService.class);}
        @Bean PrintStoreProfileController controller(PrintStoreProfileService service){return new PrintStoreProfileController(service);}
        @Bean PermissionCommonApi permissionApi(){
            PermissionCommonApi api=mock(PermissionCommonApi.class);
            when(api.hasAnyPermissions(anyLong(),any(String[].class))).thenAnswer(call->{
                Long id=call.getArgument(0);String permission=call.getArgument(1,String.class);
                return id==1L || id==2L&&permission.equals("print:store:query");
            });return api;
        }
        @Bean(name="ss") SecurityFrameworkServiceImpl security(PermissionCommonApi api){return new SecurityFrameworkServiceImpl(api);}
    }
    static void permissions() {
        try(var context=new AnnotationConfigApplicationContext(SecurityConfiguration.class)) {
            var controller=context.getBean(PrintStoreProfileController.class);var service=context.getBean(PrintStoreProfileService.class);
            var query=new LinkedMultiValueMap<String,String>();
            auth(1,1);controller.get(query);controller.put(body("0"),query);verify(service).get();verify(service).put(any());check(true,"method_security_admin_query_and_update");
            clearInvocations(service);auth(2,1);controller.get(query);rejects(()->controller.put(body("0"),query),AccessDeniedException.class,"method_security_staff_put_denied");verify(service,never()).put(any());check(true,"method_security_staff_get_all_profile");
            auth(3,1);rejects(()->controller.get(query),AccessDeniedException.class,"method_security_no_permission_get_denied");rejects(()->controller.put(body("0"),query),AccessDeniedException.class,"method_security_no_permission_put_denied");
            auth(1,1);query.add("storeId","another");rejects(()->controller.get(query),PrintStoreProfileException.class,"controller_rejects_scope_query_override");
        } finally {SecurityContextHolder.clearContext();TenantContextHolder.clear();}
    }
    static final class ScopeJdbc extends JdbcTemplate {
        int userStatus=0;List<Long> stores=List.of(10L);List<String> names=List.of("当前门店");
        @Override @SuppressWarnings("unchecked") public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args) {
            if(sql.contains("system_users"))return (List<T>)List.of(userStatus);
            if(sql.contains("print_staff_store"))return (List<T>)stores;
            if(sql.contains("print_store"))return (List<T>)names;
            throw new AssertionError("unexpected_scope_query");
        }
        @Override public <T> T queryForObject(String sql,Class<T> type,Object...args){return type.cast(Integer.valueOf(0));}
    }
    static void scope() {
        var jdbc=new ScopeJdbc();var scopes=new PrintScopeService(jdbc);auth(7,1);
        check(scopes.requireCurrent().equals(SCOPE),"real_scope_resolver_active_mapping");
        TenantContextHolder.setTenantId(2L);rejects(scopes::requireCurrent,RuntimeException.class,"scope_cross_tenant_rejected");TenantContextHolder.setTenantId(1L);
        TenantContextHolder.setIgnore(true);rejects(scopes::requireCurrent,RuntimeException.class,"scope_ignore_rejected");TenantContextHolder.setIgnore(false);
        jdbc.userStatus=1;rejects(scopes::requireCurrent,RuntimeException.class,"scope_disabled_user_rejected");jdbc.userStatus=0;
        jdbc.stores=List.of();rejects(scopes::requireCurrent,RuntimeException.class,"scope_zero_mapping_rejected");jdbc.stores=List.of(10L,11L);rejects(scopes::requireCurrent,RuntimeException.class,"scope_multiple_mapping_rejected");jdbc.stores=List.of(10L);
        jdbc.names=List.of();rejects(scopes::requireCurrent,RuntimeException.class,"scope_disabled_store_rejected");
        SecurityContextHolder.clearContext();TenantContextHolder.clear();rejects(scopes::requireCurrent,RuntimeException.class,"scope_unauthenticated_rejected");
    }
    static void cache() {
        var manager=new ConcurrentMapCacheManager(RedisKeyConstants.PERMISSION_MENU_ID_LIST,RedisKeyConstants.MENU_ROLE_ID_LIST);
        var p=manager.getCache(RedisKeyConstants.PERMISSION_MENU_ID_LIST);var r=manager.getCache(RedisKeyConstants.MENU_ROLE_ID_LIST);
        p.put("print:store:query","old");p.put("print:store:update","old");p.put("print:order:query","keep");r.put(2010L,"old");r.put(2011L,"old");r.put(2007L,"keep");
        var bridge=new PrintStoreProfilePermissionCache(manager);
        rejects(()->bridge.evictAfterCommittedPermissionMigration(1),IllegalStateException.class,"cache_missing_tenant_rejected");
        TenantContextHolder.setTenantId(1L);TenantContextHolder.setIgnore(true);rejects(()->bridge.evictAfterCommittedPermissionMigration(1),IllegalStateException.class,"cache_ignore_rejected");TenantContextHolder.setIgnore(false);
        TransactionSynchronizationManager.setActualTransactionActive(true);rejects(()->bridge.evictAfterCommittedPermissionMigration(1),IllegalStateException.class,"cache_uncommitted_migration_rejected");TransactionSynchronizationManager.setActualTransactionActive(false);
        bridge.evictAfterCommittedPermissionMigration(1);
        check(p.get("print:store:query")==null&&p.get("print:store:update")==null&&r.get(2010L)==null&&r.get(2011L)==null,"cache_manager_evicts_both_permissions_and_menu_roles");
        check(p.get("print:order:query")!=null&&r.get(2007L)!=null,"cache_retains_unrelated_entries");TenantContextHolder.clear();
    }
    public static void main(String[] args) throws Exception {
        dto();service();repository();permissions();scope();cache();
        System.out.println(JSON.writeValueAsString(Map.of("kind","P02_SOURCE_AND_IN_MEMORY_MODEL","checks",checks,"count",checks.size(),"sqlExecuted",false,"redisExecuted",false,"httpAcceptance","NOT_RUN")));
    }
}
