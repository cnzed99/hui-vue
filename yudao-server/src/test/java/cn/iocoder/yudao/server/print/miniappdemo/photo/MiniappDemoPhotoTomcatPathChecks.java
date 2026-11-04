package cn.iocoder.yudao.server.print.miniappdemo.photo;

import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.server.print.controller.app.PrintMiniappDemoPhotoUploadController;
import cn.iocoder.yudao.server.print.miniappdemo.*;
import cn.iocoder.yudao.server.print.miniappdemo.file.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.*;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.web.multipart.*;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Actual loopback Tomcat mapping + candidate admission filter + candidate upload controller.
 * No Mock request. The default resolver and Servlet getParts are independently instrumented.
 */
public final class MiniappDemoPhotoTomcatPathChecks {
    static Path root,containerTemp;
    static int checks;
    static final AtomicInteger reads=new AtomicInteger(),parts=new AtomicInteger(),defaults=new AtomicInteger(),uploads=new AtomicInteger();
    static volatile String raw,servlet,info;
    static MiniappDemoPhotoLifecycleChecks.FilesFixture files;
    static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    @Configuration(proxyBeanMethods=false) @EnableWebMvc
    static class Candidate {
        @Bean static org.springframework.context.support.PropertySourcesPlaceholderConfigurer placeholders(){return new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();}
        @Bean TomcatServletWebServerFactory tomcat(){var server=new TomcatServletWebServerFactory(0);server.setAddress(java.net.InetAddress.getLoopbackAddress());server.setBaseDirectory(root.resolve("tomcat").toFile());return server;}
        @Bean DispatcherServlet dispatcherServlet(){return new DispatcherServlet();}
        @Bean ServletRegistrationBean<DispatcherServlet> registration(DispatcherServlet dispatcher){
            var registration=new ServletRegistrationBean<>(dispatcher,"/app-api/*");
            registration.setMultipartConfig(new MultipartConfigElement(containerTemp.toString(),2L*1024*1024,3L*1024*1024,0));return registration;
        }
        @Bean(name="multipartResolver") StandardServletMultipartResolver defaultResolver(){return new StandardServletMultipartResolver(){@Override public MultipartHttpServletRequest resolveMultipart(HttpServletRequest request){defaults.incrementAndGet();return super.resolveMultipart(request);}};}
        @Bean WebProperties web(){var web=new WebProperties();web.getAppApi().setPrefix("/app-api");return web;}
        @Bean MiniappDemoFileProperties fileConfig(){return MiniappDemoPhotoLifecycleChecks.config(root);}
        @Bean MiniappDemoFileService files(){return files;}
        @Bean MiniappDemoSessionService sessions(){return new MiniappDemoSessionService(null,null,null){@Override public MiniappDemoIdentity requireForWrite(String a,String o,String r,String h){if(!"fixture-valid".equals(a))throw new MiniappDemoSessionException(MiniappDemoSessionException.Reason.SESSION_INVALID);return MiniappDemoPhotoLifecycleChecks.ID;}};}
        @Bean MiniappDemoPhotoAdmissionFilter admission(WebProperties web,MiniappDemoSessionService sessions,MiniappDemoFileProperties config,MiniappDemoFileService files){return new MiniappDemoPhotoAdmissionFilter(web,sessions,config,files);}
        @Bean FilterRegistrationBean<MiniappDemoPhotoAdmissionFilter> admissionRegistration(MiniappDemoPhotoAdmissionFilter filter){var registration=new FilterRegistrationBean<>(filter);registration.setOrder(Ordered.HIGHEST_PRECEDENCE+18);return registration;}
        @Bean MiniappDemoPhotoUploadService uploads(MiniappDemoFileService files,MiniappDemoFileProperties config){
            return new MiniappDemoPhotoUploadService(MiniappDemoPhotoLifecycleChecks.absentObjects(),null,null,files,config,null){
                @Override public View upload(MiniappDemoIdentity actor,String id,int index,MultipartFile file){
                    try{
                        check(file.getOriginalFilename().equals("photo.jpg")&&Arrays.equals(file.getBytes(),"picture-bytes".getBytes(java.nio.charset.StandardCharsets.US_ASCII)),"candidate controller got custom parsed file");
                        check(MiniappDemoPhotoLifecycleChecks.entries(MiniappDemoPhotoTomcatPathChecks.files.multipart)==2,"one private task/image during controller");
                        MiniappDemoPhotoTomcatPathChecks.uploads.incrementAndGet();
                        return new View(id,"OPEN","PHOTO_6IN",1,List.of(index),System.currentTimeMillis()+600000,null);
                    }catch(IOException e){throw new IllegalStateException(e);}
                }
            };
        }
        @Bean PrintMiniappDemoPhotoUploadController controller(MiniappDemoSessionService sessions,MiniappDemoPhotoUploadService uploads){return new PrintMiniappDemoPhotoUploadController(sessions,uploads);}
        @Bean FilterRegistrationBean<OncePerRequestFilter> observer(){
            var registration=new FilterRegistrationBean<OncePerRequestFilter>();registration.setOrder(Ordered.HIGHEST_PRECEDENCE+17);
            registration.setFilter(new OncePerRequestFilter(){
                @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
                    raw=request.getRequestURI();servlet=request.getServletPath();info=request.getPathInfo();
                    chain.doFilter(new HttpServletRequestWrapper(request){
                        @Override public ServletInputStream getInputStream()throws IOException{reads.incrementAndGet();return super.getInputStream();}
                        @Override public Collection<Part> getParts()throws IOException,ServletException{parts.incrementAndGet();return super.getParts();}
                    },response);
                }
            });return registration;
        }
    }
    public static void main(String[] args)throws Exception{
        root=Path.of(args[0]).toAbsolutePath();Files.createDirectories(root);containerTemp=Files.createDirectory(root.resolve("container-multipart"));files=new MiniappDemoPhotoLifecycleChecks.FilesFixture(root){@Override public void start(){}};
        System.setProperty("org.springframework.boot.logging.LoggingSystem","none");
        try(var context=new SpringApplicationBuilder(Candidate.class).web(WebApplicationType.SERVLET).properties("PRINT_MINIAPP_DEMO_PHOTO_ENABLED=true","spring.main.banner-mode=off","spring.config.location=optional:classpath:/photo-path-fixture-nonexistent.properties").run()){
            int port=((ServletWebServerApplicationContext)context).getWebServer().getPort();var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            String canonical="/app-api/print/miniapp/demo/photo-upload-sessions/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/parts/0";
            reset();var standard=send(client,port,canonical,true);check(standard.statusCode()==200,"canonical actual HTTP200");
            check(uploads.get()==1&&reads.get()>0,"canonical candidate controller entered via own parser");check(defaults.get()==0&&parts.get()==0,"canonical no default container multipart parse");
            check("/app-api".equals(servlet)&&info.startsWith("/print/miniapp/demo/photo-upload-sessions/"),"actual Tomcat servletPath/pathInfo mapping observed");empty();
            System.out.println("TOMCAT_HTTP_EVIDENCE scenario=canonical status="+standard.statusCode()+" bodyReads="+reads.get()+" defaultResolver="+defaults.get()+" getParts="+parts.get()+" controller="+uploads.get()+" privateFiles=0 containerFiles=0 originals=0");
            var admission=context.getBean(MiniappDemoPhotoAdmissionFilter.class);
            for(String scenario:new String[]{"encoded","disabled","anonymous"}){
                MiniappDemoPhotoLifecycleChecks.set(admission,"enabled",!scenario.equals("disabled"));reset();
                String path=scenario.equals("encoded")?canonical.replace("/photo-upload-sessions/","/%70hoto-upload-sessions/"):canonical;
                var response=send(client,port,path,!scenario.equals("anonymous"));check(response.statusCode()==(scenario.equals("encoded")?400:scenario.equals("disabled")?404:401),scenario+" rejected by candidate filter");
                check(reads.get()==0&&parts.get()==0&&defaults.get()==0&&uploads.get()==0,scenario+" preparse/body/controller not entered");empty();
                System.out.println("TOMCAT_HTTP_EVIDENCE scenario="+scenario+" status="+response.statusCode()+" bodyReads="+reads.get()+" defaultResolver="+defaults.get()+" getParts="+parts.get()+" controller="+uploads.get()+" privateFiles=0 containerFiles=0 originals=0");
                if(scenario.equals("encoded")){check(raw.contains("/%70hoto-upload-sessions/")&&info.contains("/photo-upload-sessions/"),"raw encoded URI and container-decoded mapping both observed");System.out.println("TOMCAT_MAPPING_EVIDENCE raw="+raw+" servletPath="+servlet+" pathInfo="+info);}
            }
        }
        System.out.println("PHOTO_ACTUAL_TOMCAT_PATH_PASS checks="+checks+" actual_http candidate_filter candidate_upload_controller no_Mock_no_default_multipart");
    }
    static void reset(){reads.set(0);parts.set(0);defaults.set(0);uploads.set(0);raw=servlet=info=null;}
    static void empty()throws IOException{check(MiniappDemoPhotoLifecycleChecks.entries(files.multipart)==0,"no private multipart leftovers");check(MiniappDemoPhotoLifecycleChecks.entries(containerTemp)==0,"no default multipart files");check(MiniappDemoPhotoLifecycleChecks.entries(root.resolve("photo-upload-objects"))==0,"no originals persisted");}
    static HttpResponse<String> send(HttpClient client,int port,String path,boolean authenticated)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(5)).header("Content-Type","multipart/form-data; boundary=photo-boundary");if(authenticated)request.header("Authorization","fixture-valid");return client.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(MiniappDemoPhotoLifecycleChecks.multipart())).build(),HttpResponse.BodyHandlers.ofString());
    }
}
