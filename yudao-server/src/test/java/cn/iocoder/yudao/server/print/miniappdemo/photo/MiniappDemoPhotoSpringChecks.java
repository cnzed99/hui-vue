package cn.iocoder.yudao.server.print.miniappdemo.photo;

import cn.iocoder.yudao.framework.common.biz.system.permission.PermissionCommonApi;
import cn.iocoder.yudao.framework.common.biz.system.permission.dto.DeptDataPermissionRespDTO;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.*;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.server.print.controller.admin.PrintMiniappDemoPhotoPriceController;
import cn.iocoder.yudao.server.print.scope.PrintScopeService;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Starts actual embedded Spring Boot/Tomcat and the candidate controller/service with method security.
 * Authentication is a fixture-only loopback header; permissions are resolved from isolated SQL grants.
 */
public final class MiniappDemoPhotoSpringChecks {
    private static DriverManagerDataSource datasource;
    private static int checks;
    static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    @Configuration(proxyBeanMethods=false) @EnableWebMvc @EnableMethodSecurity
    static class Candidate {
        @Bean TomcatServletWebServerFactory tomcat(){var f=new TomcatServletWebServerFactory(0);f.setAddress(java.net.InetAddress.getLoopbackAddress());return f;}
        @Bean DispatcherServlet dispatcherServlet(){return new DispatcherServlet();}
        @Bean ServletRegistrationBean<DispatcherServlet> dispatcherRegistration(DispatcherServlet dispatcher){return new ServletRegistrationBean<>(dispatcher,"/");}
        @Bean JdbcTemplate jdbc(){return new JdbcTemplate(datasource);}
        @Bean DataSourceTransactionManager manager(){return new DataSourceTransactionManager(datasource);}
        @Bean PrintScopeService scope(JdbcTemplate jdbc){return new PrintScopeService(jdbc);}
        @Bean MiniappDemoPhotoPriceService prices(PrintScopeService scope,JdbcTemplate jdbc,DataSourceTransactionManager manager){return new MiniappDemoPhotoPriceService(scope,jdbc,manager);}
        @Bean PrintMiniappDemoPhotoPriceController controller(MiniappDemoPhotoPriceService prices){return new PrintMiniappDemoPhotoPriceController(prices);}
        @Bean(name="ss") SecurityFrameworkService permissionService(JdbcTemplate jdbc){
            PermissionCommonApi permissions=new PermissionCommonApi(){
                public boolean hasAnyPermissions(Long id,String... names){
                    long role=id==2?2:1;
                    for(String name:names){Integer count=jdbc.queryForObject("SELECT COUNT(*) FROM system_role_menu rm JOIN system_role r ON r.id=rm.role_id AND r.tenant_id=rm.tenant_id JOIN system_menu m ON m.id=rm.menu_id WHERE rm.role_id=? AND rm.tenant_id=1 AND rm.deleted=0 AND r.deleted=0 AND r.status=0 AND m.deleted=0 AND m.status=0 AND m.permission=?",Integer.class,role,name);if(count!=null&&count==1)return true;}return false;
                }
                public boolean hasAnyRoles(Long id,String... roles){return false;}
                public DeptDataPermissionRespDTO getDeptDataPermission(Long id){return null;}
            };
            return new SecurityFrameworkServiceImpl(permissions);
        }
        @Bean FilterRegistrationBean<OncePerRequestFilter> fixtureAuthentication(){
            var registration=new FilterRegistrationBean<OncePerRequestFilter>();registration.setFilter(new OncePerRequestFilter(){
                @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
                    try{
                        String header=request.getHeader("X-Fixture-User");
                        if(header!=null){LoginUser actor=new LoginUser();actor.setId(Long.parseLong(header));actor.setUserType(2);actor.setTenantId(1L);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,"fixture",List.of()));TenantContextHolder.setTenantId(1L);}
                        chain.doFilter(request,response);
                    }finally{SecurityContextHolder.clearContext();TenantContextHolder.clear();}
                }
            });registration.setOrder(-100);return registration;
        }
    }
    public static void main(String[] args)throws Exception{
        int port=Integer.parseInt(args[0]);if(port<40000||port>59999)throw new IllegalArgumentException("Isolated DB port required");
        datasource=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:"+port+"/photo_candidate?connectTimeout=3000&socketTimeout=10000&sslMode=DISABLED","root","");
        var jdbc=new JdbcTemplate(datasource);
        jdbc.update("INSERT INTO system_users(id,username,nickname,status,tenant_id) VALUES(2,'fixture_reader','reader',0,1),(3,'fixture_no_scope','no_scope',0,1)");
        jdbc.update("INSERT INTO print_staff_store(tenant_id,store_id,user_id) VALUES(1,1,2)");
        System.setProperty("org.springframework.boot.logging.LoggingSystem","none");
        try(var context=new SpringApplicationBuilder(Candidate.class).web(WebApplicationType.SERVLET).properties("spring.main.banner-mode=off","spring.config.location=optional:classpath:/photo-spring-fixture-nonexistent.properties").run()){
            check(AopUtils.isCglibProxy(context.getBean(PrintMiniappDemoPhotoPriceController.class)),"actual controller CGLIB method-security proxy");
            int http=((ServletWebServerApplicationContext)context).getWebServer().getPort();
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();var mapper=new ObjectMapper();
            HttpResponse<String> get=send(client,http,"GET",1,null);check(get.statusCode()==200,"admin GET 200");JsonNode snapshot=mapper.readTree(get.body()).get("data");String revision=snapshot.get("revision").asText();
            HttpResponse<String> put=send(client,http,"PUT",1,"{\"expectedRevision\":\""+revision+"\",\"pricePerSheet\":\"5.00\"}");check(put.statusCode()==200&&mapper.readTree(put.body()).get("data").get("pricePerSheet").asText().equals("5.00"),"admin PUT actual scoped service");
            check(send(client,http,"GET",2,null).statusCode()==200,"reader GET 200");
            long before=jdbc.queryForObject("SELECT COUNT(*) FROM print_price_profile",Long.class);
            check(send(client,http,"PUT",2,"{\"expectedRevision\":\"999\",\"pricePerSheet\":\"6.00\"}").statusCode()==403,"reader PUT HTTP403");
            check(jdbc.queryForObject("SELECT COUNT(*) FROM print_price_profile",Long.class)==before,"reader no profile side effect");
            check(send(client,http,"GET",3,null).statusCode()==403,"no scope GET rejected");check(send(client,http,"PUT",3,"{\"expectedRevision\":\"999\",\"pricePerSheet\":\"6.00\"}").statusCode()==403,"no scope PUT rejected");
            check(send(client,http,"PUT",null,"{\"expectedRevision\":\"999\",\"pricePerSheet\":\"6.00\"}").statusCode()==403,"anonymous method permission rejected");
            check(send(client,http,"PUT",1,"{\"expectedRevision\":\""+revision+"\",\"pricePerSheet\":\"5.00\"}").statusCode()==409,"HTTP CAS conflict");
        }
        System.out.println("PHOTO_ACTUAL_SPRING_BOOT_HTTP_PASS checks="+checks+" candidate_CGLIB admin_GET_PUT reader_PUT403 no_scope no_production_auth");
    }
    static HttpResponse<String> send(HttpClient client,int port,String method,Integer actor,String body)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/print/miniapp-demo/photo-price")).timeout(Duration.ofSeconds(5));
        if(actor!=null)request.header("X-Fixture-User",actor.toString());
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));if(body!=null)request.header("Content-Type","application/json");return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
}
