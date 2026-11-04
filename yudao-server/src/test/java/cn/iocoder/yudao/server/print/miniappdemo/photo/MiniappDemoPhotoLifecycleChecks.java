package cn.iocoder.yudao.server.print.miniappdemo.photo;

import cn.iocoder.yudao.framework.web.config.WebProperties;
import cn.iocoder.yudao.server.print.miniappdemo.*;
import cn.iocoder.yudao.server.print.miniappdemo.file.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.servlet.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real filesystem, request stream and child JVM boundaries; no production database or services. */
public final class MiniappDemoPhotoLifecycleChecks {
    static int checks;
    static void check(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    static void set(Object instance,String field,Object value)throws Exception{Class<?> type=instance.getClass();while(type!=null){try{var f=type.getDeclaredField(field);f.setAccessible(true);f.set(instance,value);return;}catch(NoSuchFieldException e){type=type.getSuperclass();}}throw new NoSuchFieldException(field);}
    static final MiniappDemoIdentity ID=new MiniappDemoIdentity("session","principal",1,1,System.currentTimeMillis()+600_000);
    static class FilesFixture extends MiniappDemoFileService {
        final Path root,multipart,scratch;final AtomicBoolean retained=new AtomicBoolean();
        volatile CountDownLatch cleanupEntered,cleanupRelease;
        FilesFixture(Path root)throws Exception{super(null,null,null,null,null,null);this.root=root;multipart=Files.createDirectory(root.resolve("photo-multipart"));scratch=Files.createDirectory(root.resolve("scratch"));}
        @Override public void requireStorageLockHeld(){}
        @Override public Path photoMultipartRoot(){return multipart;}
        @Override public Path idPhotoScratchRoot(){return scratch;}
        @Override public void retainStorageLockUntilProcessExit(){retained.set(true);}
        @Override public boolean cleanupPhotoMultipartTask(Path path){return remove(path);}
        @Override public boolean cleanupIdPhotoScratchTask(Path path){
            if(cleanupEntered!=null){cleanupEntered.countDown();boolean interrupted=false;while(cleanupRelease.getCount()>0)try{cleanupRelease.await();}catch(InterruptedException e){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
            return remove(path);
        }
        boolean remove(Path path){try(var walk=Files.walk(path)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);return true;}catch(IOException e){return false;}}
    }
    static MiniappDemoFileProperties config(Path root){var p=new MiniappDemoFileProperties();p.setStorageRoot(root);p.setEnabled(true);p.setMinFreeBytes(10L*1024*1024*1024);return p;}
    static class Request extends MockHttpServletRequest {
        int reads;
        Request(byte[] body){setMethod("POST");setRequestURI("/app-api/print/miniapp/demo/photo-upload-sessions/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa/parts/0");setServletPath(getRequestURI());setContentType("multipart/form-data; boundary=photo-boundary");setContent(body);addHeader("Content-Length",Integer.toString(body.length));}
        @Override public ServletInputStream getInputStream(){reads++;return super.getInputStream();}
        @Override public Collection<jakarta.servlet.http.Part> getParts(){throw new AssertionError("Container multipart parser was invoked");}
    }
    static byte[] multipart(){return ("--photo-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\npicture-bytes\r\n--photo-boundary--\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);}
    static long entries(Path root)throws IOException{try(var all=Files.walk(root)){return all.count()-1;}}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);Files.createDirectories(root);
        preparse(Files.createDirectory(root.resolve("filter")));
        cleanup(Files.createDirectory(root.resolve("cleanup")));
        drain(Files.createDirectory(root.resolve("drain")),false);
        drain(Files.createDirectory(root.resolve("drain-timeout")),true);
        process(Files.createDirectory(root.resolve("process-timeout")),false);
        process(Files.createDirectory(root.resolve("process-interrupt")),true);
        System.out.println("PHOTO_LIFECYCLE_PASS checks="+checks+" preparse_no_body disabled_cleanup drain retry real_child_permit");
    }
    static void preparse(Path root)throws Exception{
        var files=new FilesFixture(root);var config=config(root);AtomicBoolean authenticated=new AtomicBoolean(true);
        var sessions=new MiniappDemoSessionService(null,null,null){@Override public MiniappDemoIdentity requireForWrite(String a,String o,String r,String h){if(!authenticated.get())throw new MiniappDemoSessionException(MiniappDemoSessionException.Reason.SESSION_INVALID);return ID;}};
        var web=new WebProperties();web.getAppApi().setPrefix("/app-api");AtomicBoolean low=new AtomicBoolean();
        var filter=new MiniappDemoPhotoAdmissionFilter(web,sessions,config,files){@Override protected long usableSpace(Path p){return low.get()?0:Long.MAX_VALUE;}};
        filter.initialize();set(filter,"enabled",true);
        for(String scenario:new String[]{"disabled","identity","low-disk","oversize","unknown-size","encoding","wrong-route"}){
            set(filter,"enabled",!scenario.equals("disabled"));authenticated.set(!scenario.equals("identity"));low.set(scenario.equals("low-disk"));
            Request req=new Request(multipart());
            if(scenario.equals("oversize")){req.removeHeader("Content-Length");req.addHeader("Content-Length","9999999");req=new Request(multipart()){@Override public long getContentLengthLong(){return 9999999;}};}
            if(scenario.equals("unknown-size"))req.removeHeader("Content-Length");if(scenario.equals("encoding"))req.addHeader("Content-Encoding","gzip");if(scenario.equals("wrong-route"))req.setRequestURI(req.getRequestURI()+"/extra");
            MockHttpServletResponse response=new MockHttpServletResponse();filter.doFilter(req,response,(r,s)->{throw new AssertionError("Rejected request reached dispatcher");});
            check(response.getStatus()>=400,scenario+" rejected");check(req.reads==0,scenario+" body unopened");check(entries(files.multipart)==0,scenario+" no private file");
        }
        set(filter,"enabled",true);authenticated.set(true);low.set(false);var pool=Executors.newSingleThreadExecutor();CountDownLatch chainEntered=new CountDownLatch(1),release=new CountDownLatch(1);
        try{
            var first=pool.submit(()->{try{filter.doFilter(new Request(multipart()),new MockHttpServletResponse(),(r,s)->{check(r.getAttribute(MiniappDemoPhotoAdmissionFilter.ADMITTED_IMAGE) instanceof MultipartFile,"admitted image");chainEntered.countDown();try{release.await();}catch(InterruptedException e){throw new ServletException(e);}});}catch(Exception e){throw new RuntimeException(e);}});
            check(chainEntered.await(3,TimeUnit.SECONDS),"first admitted");long existing=entries(files.multipart);check(existing==2,"one private task/image");
            Request second=new Request(multipart());MockHttpServletResponse response=new MockHttpServletResponse();filter.doFilter(second,response,(r,s)->{throw new AssertionError("Concurrent request entered");});check(response.getStatus()==429&&second.reads==0,"busy before parse");check(entries(files.multipart)==existing,"busy creates no files");
            release.countDown();first.get(5,TimeUnit.SECONDS);check(entries(files.multipart)==0,"multipart cleaned after chain");
        }finally{release.countDown();pool.shutdownNow();filter.drain();}
    }
    static JdbcTemplate absentObjects(){return new JdbcTemplate(){@Override public <T>T queryForObject(String sql,Class<T> type,Object... values){return type.cast(0L);}};}
    static void cleanup(Path root)throws Exception{
        var files=new FilesFixture(root);var config=config(root);Path objects=Files.createDirectory(root.resolve("photo-upload-objects"));Path orphan=objects.resolve("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.bin");Files.writeString(orphan,"original");Files.setLastModifiedTime(orphan,FileTime.fromMillis(System.currentTimeMillis()-900_000));Files.setPosixFilePermissions(objects,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        AtomicBoolean failing=new AtomicBoolean(true);
        var service=new MiniappDemoPhotoUploadService(absentObjects(),null,null,files,config,null){@Override protected void deletePrivateObject(Path p)throws IOException{if(failing.get())throw new IOException("fixture delete blocked");super.deletePrivateObject(p);}};
        service.initialize();check(Files.exists(orphan),"disabled restart deletion failure preserved");check(service.pendingCleanupCount()==1,"deletion failure observable");failing.set(false);service.cleanup();check(!Files.exists(orphan)&&service.pendingCleanupCount()==0,"disabled cleanup retry recovered");
        Path temp=objects.resolve("incoming-old.part");Files.writeString(temp,"temporary");Files.setLastModifiedTime(temp,FileTime.fromMillis(System.currentTimeMillis()-900_000));service.cleanup();check(!Files.exists(temp),"disabled temporary cleanup");service.stop();check(!files.retained.get(),"clean disabled shutdown drained");
    }
    static void drain(Path root,boolean forceTimeout)throws Exception{
        var files=new FilesFixture(root);var config=config(root);
        var service=new MiniappDemoPhotoUploadService(absentObjects(),null,null,files,config,null){@Override protected int drainSeconds(){return forceTimeout?1:5;}};set(service,"enabled",true);service.initialize();
        CountDownLatch reading=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger opens=new AtomicInteger();
        MultipartFile slow=new MultipartFile(){public String getName(){return "file";}public String getOriginalFilename(){return "a.jpg";}public String getContentType(){return "image/jpeg";}public boolean isEmpty(){return false;}public long getSize(){return 1;}public byte[] getBytes(){throw new UnsupportedOperationException();}public void transferTo(File f){throw new UnsupportedOperationException();}public InputStream getInputStream(){opens.incrementAndGet();return new InputStream(){public int read(){reading.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return -1;}};}};
        var pool=Executors.newFixedThreadPool(2);
        try{
            var uploader=pool.submit(()->{try{service.upload(ID,"upload",0,slow);throw new AssertionError("empty upload passed");}catch(MiniappDemoPhotoException good){}});check(reading.await(3,TimeUnit.SECONDS),"active upload started");var stopper=pool.submit(service::stop);Thread.sleep(100);check(!stopper.isDone(),"stop waits active upload");
            try{service.upload(ID,"upload",0,slow);throw new AssertionError("new upload during stop");}catch(MiniappDemoPhotoException good){checks++;}check(opens.get()==1,"stop blocks before body copy");
            if(forceTimeout){stopper.get(3,TimeUnit.SECONDS);check(files.retained.get(),"undrained upload retains OS lock");}
            release.countDown();uploader.get(3,TimeUnit.SECONDS);stopper.get(5,TimeUnit.SECONDS);check(forceTimeout||!files.retained.get(),"normal drain releases safely");check(entries(root.resolve("photo-upload-objects"))==0,"upload scratch drained");
        }finally{release.countDown();pool.shutdownNow();service.stop();}
    }
    public static final class Child{public static void main(String[] args)throws Exception{Files.writeString(Path.of(args[0]),"ready");Thread.sleep(60_000);}}
    static void process(Path root,boolean interrupt)throws Exception{
        var files=new FilesFixture(root);var config=config(root);Path jar=Files.writeString(root.resolve("fixture.jar"),"fixture");config.setRuntimeJar(jar);var gate=new MiniappDemoProcessAdmission();AtomicInteger starts=new AtomicInteger();AtomicReference<Process> child=new AtomicReference<>();Path ready=root.resolve("ready");
        files.cleanupEntered=new CountDownLatch(1);files.cleanupRelease=new CountDownLatch(1);
        var renderer=new MiniappDemoPhotoProcessRenderer(files,config,gate){@Override protected int timeoutSeconds(){return 1;}@Override protected Process startProcess(List<String> ignored)throws IOException{starts.incrementAndGet();Process p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx32m","-cp",System.getProperty("java.class.path"),Child.class.getName(),ready.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();child.set(p);return p;}};
        AtomicReference<Throwable> unexpected=new AtomicReference<>();Thread owner=new Thread(()->{try{renderer.render(List.of(),"PHOTO_6IN","a".repeat(64));unexpected.set(new AssertionError("sleep child succeeded"));}catch(MiniappDemoPhotoException expected){}catch(Throwable e){unexpected.set(e);}},"real-photo-owner");owner.start();
        try{
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(!Files.exists(ready)&&System.nanoTime()<end)Thread.sleep(10);check(Files.exists(ready),"real child started");if(interrupt)owner.interrupt();
            check(files.cleanupEntered.await(5,TimeUnit.SECONDS),"cleanup reached after child termination");check(!child.get().isAlive(),"child confirmed exited before scratch handling");check(gate.tryAcquire()==null,"process lease retained through cleanup");
            try{renderer.render(List.of(),"PHOTO_6IN","a".repeat(64));throw new AssertionError("second process admitted during cleanup");}catch(MiniappDemoPhotoException expected){check(expected.status()==429,"second child rejected");}
            check(starts.get()==1,"one child only");files.cleanupRelease.countDown();owner.join(5000);check(!owner.isAlive()&&unexpected.get()==null,"owner completed safely");
            try(var lease=gate.tryAcquire()){check(lease!=null,"lease available only after cleanup");}check(entries(files.scratch)==0,"scratch removed before release");
        }finally{files.cleanupRelease.countDown();owner.interrupt();owner.join(5000);if(child.get()!=null&&child.get().isAlive()){child.get().destroyForcibly();child.get().waitFor();}renderer.stop();}
    }
}
