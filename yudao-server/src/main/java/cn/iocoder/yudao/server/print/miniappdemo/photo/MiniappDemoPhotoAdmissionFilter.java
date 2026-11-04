package cn.iocoder.yudao.server.print.miniappdemo.photo;

import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.server.print.miniappdemo.*;
import cn.iocoder.yudao.server.print.miniappdemo.file.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.file.*;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Runs before DispatcherServlet multipart resolution. No rejected request body is opened. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 18)
public class MiniappDemoPhotoAdmissionFilter extends OncePerRequestFilter {
    public static final String ADMITTED_IDENTITY=MiniappDemoPhotoAdmissionFilter.class.getName()+".identity";
    public static final String ADMITTED_IMAGE=MiniappDemoPhotoAdmissionFilter.class.getName()+".image";
    static final long MAX_MULTIPART=3L*1024*1024;
    static final long MAX_IMAGE=2L*1024*1024;
    private final MiniappDemoSessionService sessions;
    private final MiniappDemoFileProperties config;
    private final MiniappDemoFileService files;
    private final String prefix;
    private final Semaphore requests=new Semaphore(1,true);
    @Value("${PRINT_MINIAPP_DEMO_PHOTO_ENABLED:false}") private boolean enabled;
    private boolean closing;
    private Path multipartRoot;

    public MiniappDemoPhotoAdmissionFilter(WebProperties web,MiniappDemoSessionService sessions,
            MiniappDemoFileProperties config,MiniappDemoFileService files){
        this.sessions=sessions;this.config=config;this.files=files;
        String api=web.getAppApi().getPrefix();
        this.prefix=(api==null||api.equals("/")?"":api.replaceAll("/$",""))+"/print/miniapp/demo/photo-upload-sessions";
    }
    @PostConstruct public void initialize(){
        if(!config.isEnabled())return;
        files.requireStorageLockHeld();multipartRoot=files.photoMultipartRoot();
    }
    private String containerPath(HttpServletRequest request){
        // Servlet containers already decode this mapping path. Never URL-decode it again.
        String servlet=request.getServletPath();
        String info=request.getPathInfo();
        return (servlet==null?"":servlet)+(info==null?"":info);
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        String path=containerPath(request);
        return !(path.equals(prefix)||path.startsWith(prefix+"/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws IOException,ServletException{
        String path=containerPath(request);
        if(!request.getRequestURI().equals(request.getContextPath()+path)){
            reject(response,400,43200);return;
        }
        boolean multipartBody=request.getContentType()!=null&&request.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
        if(!multipartBody&&!path.contains("/parts")){chain.doFilter(request,response);return;}
        String suffix=path.substring(prefix.length());
        if(!enabled||multipartRoot==null){reject(response,404,43202);return;}
        if(!"POST".equals(request.getMethod())||!suffix.matches("/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/parts/(?:[0-9]|[12][0-9])")){reject(response,404,43201);return;}
        long length=request.getContentLengthLong();var lengths=Collections.list(request.getHeaders("Content-Length"));
        if(request.getHeader("Transfer-Encoding")!=null||request.getHeader("Content-Encoding")!=null||request.getQueryString()!=null
                ||lengths.size()!=1||!lengths.get(0).matches("[0-9]+")||length<1){reject(response,400,43200);return;}
        if(length>MAX_MULTIPART){reject(response,413,43200);return;}
        try{MiniappDemoPhotoMultipartRequest.boundary(request.getContentType());}catch(RuntimeException e){reject(response,400,43200);return;}
        MiniappDemoIdentity identity;
        try{identity=sessions.requireForWrite(request.getHeader("Authorization"),request.getHeader("Origin"),request.getHeader("Referer"),request.getHeader("Host"));}
        catch(MiniappDemoSessionException e){reject(response,e.status().value(),e.code());return;}
        if(!admit()){reject(response,429,43204);return;}
        MiniappDemoPhotoMultipartRequest multipart=null;
        try{
            try{
                files.requireStorageLockHeld();
                long reserve=Math.addExact(Math.max(config.getMinFreeBytes(),10L*1024*1024*1024),MAX_MULTIPART+MAX_IMAGE);
                if(usableSpace(multipartRoot)<reserve){reject(response,507,43205);return;}
            }catch(IOException|RuntimeException e){reject(response,503,43205);return;}
            try{multipart=MiniappDemoPhotoMultipartRequest.parse(request,multipartRoot,MAX_IMAGE,files);}
            catch(MiniappDemoPhotoMultipartRequest.PartTooLarge e){reject(response,413,43200);return;}
            catch(MiniappDemoPhotoMultipartRequest.UnsupportedPart e){reject(response,415,43200);return;}
            catch(IOException|ServletException|IllegalArgumentException e){reject(response,400,43200);return;}
            multipart.setAttribute(ADMITTED_IDENTITY,identity);multipart.setAttribute(ADMITTED_IMAGE,multipart.image());
            chain.doFilter(multipart,response);
        }finally{
            try{if(multipart!=null&&!multipart.cleanup(files))files.retainStorageLockUntilProcessExit();}
            finally{requests.release();}
        }
    }
    protected long usableSpace(Path path)throws IOException{return Files.getFileStore(path).getUsableSpace();}
    private synchronized boolean admit(){return !closing&&requests.tryAcquire();}
    @PreDestroy public void drain(){
        synchronized(this){closing=true;}
        boolean drained=false;
        try{drained=requests.tryAcquire(1,35,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        if(!drained)files.retainStorageLockUntilProcessExit();
    }
    private static void reject(HttpServletResponse response,int status,int code)throws IOException{
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.setHeader("Cache-Control","no-store");
        response.getWriter().write("{\"code\":"+code+",\"data\":null,\"msg\":\"Photo upload rejected\"}");
    }
}
