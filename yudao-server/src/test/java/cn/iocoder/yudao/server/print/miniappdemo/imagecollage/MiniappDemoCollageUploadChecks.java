package cn.iocoder.yudao.server.print.miniappdemo.imagecollage;

import cn.iocoder.yudao.server.print.miniappdemo.*;
import cn.iocoder.yudao.server.print.miniappdemo.file.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class MiniappDemoCollageUploadChecks {
    static int passed;
    static final ObjectMapper JSON=new ObjectMapper();
    static void check(String name,boolean value){if(!value)throw new AssertionError(name);System.out.println("PASS "+name);passed++;}
    static void rejected(String name,Runnable action,int code){try{action.run();throw new AssertionError(name);}catch(MiniappDemoCollageUploadException e){check(name,e.code()==code);}}
    static MiniappDemoIdentity identity(String id){return new MiniappDemoIdentity(id,id,1,1,System.currentTimeMillis()+3600000);}
    static class FakeFiles extends MiniappDemoFileService {
        volatile boolean retained;
        FakeFiles(){super(null,null,null,null,null,null);}
        public void requireStorageLockHeld(){}
        public void retainStorageLockUntilProcessExit(){retained=true;}
        public MiniappDemoFileMetadata get(MiniappDemoIdentity i,String id){return metadata(id);}
    }
    static MiniappDemoFileMetadata metadata(String id){return new MiniappDemoFileMetadata(id,"collage.pdf",100,1,"READY",null,1,System.currentTimeMillis()+3600000,true);}
    static JsonNode manifest(byte[]... parts)throws Exception{
        var root=JSON.createObjectNode();root.put("renderVersion",MiniappDemoImageCollageProperties.RENDER_VERSION);
        var list=root.putArray("files");for(int i=0;i<parts.length;i++)list.addObject().put("index",i).put("sizeBytes",parts[i].length)
            .put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(parts[i])));
        return root;
    }
    static class Part implements MultipartFile {
        final byte[] bytes; Part(byte[] bytes){this.bytes=bytes;}
        public String getName(){return "file";}public String getOriginalFilename(){return "test.png";}
        public String getContentType(){return "image/png";}public boolean isEmpty(){return bytes.length==0;}
        public long getSize(){return bytes.length;}public byte[] getBytes(){return bytes.clone();}
        public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
        public void transferTo(File f){throw new UnsupportedOperationException();}
    }
    public static void main(String[] args)throws Exception{
        nativeChannel();
        var filter=new MiniappDemoImageCollageUploadAdmissionFilter(new MiniappDemoImageCollageProperties(),new cn.iocoder.yudao.framework.web.config.WebProperties());
        filter.initialize();
        String route="/app-api/print/miniapp/demo/image-collage-upload-sessions/12345678-1234-1234-1234-123456789abc/parts/";
        check("staged_part_route",filter.matchesStagedPart("",route+"8"));
        check("staged_context_route",filter.matchesStagedPart("/ctx","/ctx"+route+"0"));
        check("staged_bad_index",!filter.matchesStagedPart("",route+"9"));
        check("staged_adjacent_path",!filter.matchesStagedPart("",route+"0/extra"));
        Path storage=Files.createTempDirectory("staged-upload-check-");
        Files.setPosixFilePermissions(storage,MiniappDemoCollageUploadService.DIR);
        var props=new MiniappDemoCollageUploadProperties();props.setEnabled(true);props.setRoot(storage.resolve("image-collage-upload-scratch"));
        var fp=new MiniappDemoFileProperties();fp.setStorageRoot(storage);
        var cp=new MiniappDemoImageCollageProperties();cp.setEnabled(true);
        FakeFiles files=new FakeFiles();AtomicInteger renders=new AtomicInteger();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        var service=new MiniappDemoCollageUploadService(props,files,(i,p,k)->{
            renders.incrementAndGet();entered.countDown();
            try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException();}
            return metadata("result");
        },fp,cp);
        service.initialize();
        try {
            var a=identity("a");var b=identity("b");byte[] one={1,2,3},two={4,5};
            JsonNode manifest=manifest(one,two);
            var session=service.create(a,manifest,"key");
            check("same_manifest_restores_same_id",service.create(a,manifest,"key").uploadId().equals(session.uploadId()));
            rejected("same_count_changed_content",()->{try{service.create(a,manifest(two,one),"key");}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}},1935004);
            rejected("cross_owner_hidden",()->service.upload(b,session.uploadId(),0,new Part(one)),1935003);
            rejected("incomplete_commit",()->service.complete(a,session.uploadId()),1935005);
            rejected("part_content_mismatch",()->service.upload(a,session.uploadId(),0,new Part(new byte[]{9,8,7})),1935004);
            service.upload(a,session.uploadId(),0,new Part(one));
            check("part_replay",service.upload(a,session.uploadId(),0,new Part(one)).uploadedIndexes().equals(List.of(0)));
            service.upload(a,session.uploadId(),1,new Part(two));
            ExecutorService executor=Executors.newSingleThreadExecutor();
            Future<MiniappDemoCollageUploadService.View> future=executor.submit(()->service.complete(a,session.uploadId()));
            check("render_entered",entered.await(3,TimeUnit.SECONDS));
            rejected("parallel_complete_busy",()->service.complete(a,session.uploadId()),1935006);
            release.countDown();check("complete_success",future.get(3,TimeUnit.SECONDS).state().equals("COMPLETE"));executor.shutdown();
            check("complete_replay_no_second_render",service.complete(a,session.uploadId()).result().id().equals("result")&&renders.get()==1);
            check("completed_images_removed",Files.list(props.getRoot().resolve(session.uploadId())).findAny().isEmpty());
            var second=service.create(a,manifest,"key2");check("completed_releases_principal_slot",!second.uploadId().equals(session.uploadId()));
            service.cancel(a,second.uploadId());
            rejected("cancel_hidden",()->service.complete(a,second.uploadId()),1935003);
            rejected("extra_manifest_field",()->{JsonNode altered=manifest.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)altered).put("extra",1);service.create(a,altered,"new");},1935002);
            check("shared_root_private",Files.getPosixFilePermissions(props.getRoot()).equals(MiniappDemoCollageUploadService.DIR));
            service.cancel(a,session.uploadId());
            check("cancel_does_not_delete_result",files.get(a,"result").ready());
            shutdownBlockedWrite(service,files,a,manifest(one));
        } finally {
            release.countDown();service.stop();
            try(var walk=Files.walk(storage)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
        errorExits();
        System.out.println("STAGED_UPLOAD_CHECKS_PASS "+passed);
    }
    static void shutdownBlockedWrite(MiniappDemoCollageUploadService service,FakeFiles files,MiniappDemoIdentity identity,JsonNode manifest)throws Exception{
        var s=service.create(identity,manifest,"blocked");CountDownLatch reading=new CountDownLatch(1),release=new CountDownLatch(1);
        MultipartFile blocked=new Part(new byte[]{1,2,3}){
            public InputStream getInputStream(){return new ByteArrayInputStream(bytes){
                public synchronized int read(byte[] b,int off,int len){
                    reading.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();return -1;}
                    return super.read(b,off,len);
                }
            };}
        };
        ExecutorService executor=Executors.newSingleThreadExecutor();
        Future<?> write=executor.submit(()->service.upload(identity,s.uploadId(),0,blocked));
        check("blocked_part_active",reading.await(3,TimeUnit.SECONDS));
        Thread shutdown=new Thread(service::stop);shutdown.start();shutdown.interrupt();shutdown.join(3000);
        check("undrained_shutdown_retains_os_lock",files.retained&&!shutdown.isAlive());
        release.countDown();write.get(3,TimeUnit.SECONDS);executor.shutdown();
    }
    static void errorExits() throws Exception {
        Path storage=Files.createTempDirectory("staged-errors-");
        Files.setPosixFilePermissions(storage,MiniappDemoCollageUploadService.DIR);
        var props=new MiniappDemoCollageUploadProperties();props.setEnabled(true);props.setRoot(storage.resolve("image-collage-upload-scratch"));
        var fp=new MiniappDemoFileProperties();fp.setStorageRoot(storage);
        var cp=new MiniappDemoImageCollageProperties();cp.setEnabled(true);
        FakeFiles files=new FakeFiles();AtomicInteger mode=new AtomicInteger();
        var service=new MiniappDemoCollageUploadService(props,files,(i,p,k)->{
            if(mode.get()==0)throw new MiniappDemoImageCollageException(MiniappDemoImageCollageException.Reason.RENDER_BUSY);
            if(mode.get()==1)throw new MiniappDemoFileException(MiniappDemoFileException.Reason.IDEMPOTENCY_CONFLICT);
            throw new MiniappDemoSessionException(MiniappDemoSessionException.Reason.SESSION_INVALID);
        },fp,cp);
        service.initialize();
        try {
            var a=identity("errors");byte[] data={1};var m=manifest(data);var s=service.create(a,m,"errors");
            service.upload(a,s.uploadId(),0,new Part(data));
            try{service.complete(a,s.uploadId());throw new AssertionError();}catch(MiniappDemoImageCollageException e){check("transient_renderer_busy_retains_open",service.create(a,m,"errors").state().equals("OPEN"));}
            mode.set(1);
            try{service.complete(a,s.uploadId());throw new AssertionError();}catch(MiniappDemoFileException e){check("final_content_conflict_propagated",e.code()==1927007);}
            String conflictId=s.uploadId();rejected("final_conflict_session_removed",()->service.complete(a,conflictId),1935003);
            s=service.create(a,m,"session-invalid");service.upload(a,s.uploadId(),0,new Part(data));String id=s.uploadId();mode.set(2);
            try{service.complete(a,id);throw new AssertionError();}catch(MiniappDemoSessionException e){check("session_invalid_propagated",true);}
            rejected("session_invalid_cleanup",()->service.complete(a,id),1935003);
        } finally {
            service.stop();try(var walk=Files.walk(storage)){for(Path p:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}
        }
    }
    static void nativeChannel(){
        var p=new MiniappDemoSessionProperties();p.setEnabled(true);p.setIssuanceEnabled(true);p.setTenantId(1L);p.setStoreId(1L);
        p.setAllowedOrigin("http://demo.local");p.setEdgeHost("demo.local");
        p.requireWriteRequest("http://demo.local",null,"demo.local");check("h5_retained",true);
        denyNative("empty_origin_default_rejected",()->p.requireWriteRequest(null,"https://servicewechat.com/wx0123456789abcdef/1/page-frame.html","demo.local"));
        p.setWechatAppId("wx0123456789abcdef");p.setWechatNativeEnabled(true);
        p.requireIssuanceRequest(null,"https://servicewechat.com/wx0123456789abcdef/devtools/page-frame.html","demo.local");
        check("native_configured_channel",true);
        denyNative("wrong_appid",()->p.requireWriteRequest(null,"https://servicewechat.com/wx1123456789abcdef/1/page-frame.html","demo.local"));
        denyNative("wrong_host",()->p.requireWriteRequest(null,"https://servicewechat.com/wx0123456789abcdef/1/page-frame.html","evil.local"));
        denyNative("wrong_origin",()->p.requireWriteRequest("http://evil.local","https://servicewechat.com/wx0123456789abcdef/1/page-frame.html","demo.local"));
        denyNative("referer_suffix_rejected",()->p.requireWriteRequest(null,"https://servicewechat.com/wx0123456789abcdef/1/page-frame.html/evil","demo.local"));
    }
    static void denyNative(String name,Runnable action){try{action.run();throw new AssertionError(name);}catch(MiniappDemoSessionException e){check(name,true);}}
}
