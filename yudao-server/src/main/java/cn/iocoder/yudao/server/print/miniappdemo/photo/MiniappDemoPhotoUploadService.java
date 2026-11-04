package cn.iocoder.yudao.server.print.miniappdemo.photo;

import cn.iocoder.yudao.server.print.miniappdemo.*;
import cn.iocoder.yudao.server.print.miniappdemo.file.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Durable ordered manifest. A lost response or JVM restart never creates a new generation domain. */
@Service
public final class MiniappDemoPhotoUploadService {
    private static final String VERSION="photo-6in-v1";
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final MiniappDemoSessionRepository sessions;
    private final MiniappDemoFileService files;private final MiniappDemoFileProperties config;private final MiniappDemoPhotoProcessRenderer renderer;
    @Value("${PRINT_MINIAPP_DEMO_PHOTO_ENABLED:false}") private boolean enabled;
    private Path root;
    private java.util.concurrent.ScheduledExecutorService cleaner;
    public MiniappDemoPhotoUploadService(JdbcTemplate jdbc,PlatformTransactionManager manager,MiniappDemoSessionRepository sessions,
        MiniappDemoFileService files,MiniappDemoFileProperties config,MiniappDemoPhotoProcessRenderer renderer){
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.sessions=sessions;this.files=files;this.config=config;this.renderer=renderer;
    }
    @PostConstruct public void initialize()throws IOException{
        if(!enabled)return;files.requireStorageLockHeld();root=config.getStorageRoot().toRealPath().resolve("photo-upload-objects");
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS)){Files.createDirectory(root);Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));}
        if(Files.isSymbolicLink(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||!Files.getPosixFilePermissions(root).equals(PosixFilePermissions.fromString("rwx------")))throw new IOException("Unsafe photo root");
        cleaner=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"photo-upload-cleanup");t.setDaemon(true);return t;});
        cleaner.scheduleWithFixedDelay(this::cleanup,60,60,java.util.concurrent.TimeUnit.SECONDS);
    }
    @jakarta.annotation.PreDestroy public void stop(){
        if(cleaner!=null){cleaner.shutdownNow();try{if(!cleaner.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS))files.retainStorageLockUntilProcessExit();}catch(InterruptedException e){files.retainStorageLockUntilProcessExit();Thread.currentThread().interrupt();}}
    }
    private void cleanup(){
        try{
            files.requireStorageLockHeld();long safeBefore=System.currentTimeMillis()-720_000L;
            try(var entries=Files.newDirectoryStream(root)){
                for(Path p:entries){
                    if(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.getLastModifiedTime(p,LinkOption.NOFOLLOW_LINKS).toMillis()>safeBefore)continue;
                    String name=p.getFileName().toString();
                    if(name.matches("incoming-[A-Za-z0-9-]+\\.part")){Files.deleteIfExists(p);continue;}
                    if(!name.matches("[0-9a-f-]{36}\\.bin"))continue;
                    String key=name.substring(0,36);
                    Long live=jdbc.queryForObject("SELECT COUNT(*) FROM print_photo_upload_item i JOIN print_photo_upload_session s ON s.id=i.session_id AND s.tenant_id=i.tenant_id AND s.store_id=i.store_id AND s.principal_id=i.principal_id WHERE i.storage_key=? AND s.expires_at_ms>?",Long.class,key,System.currentTimeMillis());
                    if(live!=null&&live==0)Files.deleteIfExists(p);
                }
            }
        }catch(Exception ignored){/* Keep objects on any uncertain database or filesystem result. */}
    }
    public record View(String uploadId,String state,String variant,int fileCount,List<Integer> uploadedIndexes,long expiresAtMs,MiniappDemoFileMetadata result){}
    private record Session(String id,String variant,int count,String state,long expiry,String manifest,String finalId){}
    private record Item(int ordinal,long size,String digest,String key){}
    public View create(MiniappDemoIdentity identity,JsonNode body,String key){
        requireEnabled();if(identity==null||key==null||!key.matches("[!-~]{1,128}")||body==null||!body.isObject()||body.size()!=2
            ||!body.has("variant")||!body.get("variant").isTextual()||!body.has("fileCount")||!body.get("fileCount").isIntegralNumber()||!body.get("fileCount").canConvertToInt())throw MiniappDemoPhotoException.input();
        String variant=body.get("variant").textValue();int count=body.get("fileCount").intValue();
        if(!MiniappDemoSourceRules.valid("PHOTO",variant)||count<1||count>("PHOTO_6IN_COLLAGE".equals(variant)?9:30))throw MiniappDemoPhotoException.input();
        return tx.execute(status->{
            active(identity);String digest=hash(key);
            var existing=jdbc.query("SELECT id FROM print_photo_upload_session WHERE tenant_id=? AND store_id=? AND principal_id=? AND idempotency_sha256=? FOR UPDATE",
                (rs,n)->rs.getString(1),identity.tenantId(),identity.storeId(),identity.principalId(),digest);
            if(!existing.isEmpty()){
                Session old=locked(identity,existing.get(0));if(!old.variant.equals(variant)||old.count!=count)throw conflict();return view(identity,old);
            }
            Integer own=jdbc.queryForObject("SELECT COUNT(*) FROM print_photo_upload_session WHERE tenant_id=? AND store_id=? AND principal_id=? AND expires_at_ms>? AND state<>'COMPLETE'",Integer.class,identity.tenantId(),identity.storeId(),identity.principalId(),System.currentTimeMillis());
            Integer all=jdbc.queryForObject("SELECT COUNT(*) FROM print_photo_upload_session WHERE expires_at_ms>? AND state<>'COMPLETE'",Integer.class,System.currentTimeMillis());
            if(own!=null&&own>=1 || all!=null&&all>=4)throw new MiniappDemoPhotoException(429,43204,"Photo upload busy");
            String id=UUID.randomUUID().toString();long now=System.currentTimeMillis(),expiry=Math.min(identity.expiresAtMs(),now+600_000L);
            jdbc.update("INSERT INTO print_photo_upload_session(id,tenant_id,store_id,principal_id,idempotency_sha256,variant,expected_count,render_version,state,expires_at_ms,created_at_ms,updated_at_ms) VALUES(?,?,?,?,?,?,?,?,'OPEN',?,?,?)",id,identity.tenantId(),identity.storeId(),identity.principalId(),digest,variant,count,VERSION,expiry,now,now);
            return view(identity,locked(identity,id));
        });
    }
    public View get(MiniappDemoIdentity identity,String id){requireEnabled();return tx.execute(s->{active(identity);return view(identity,locked(identity,id));});}
    public View upload(MiniappDemoIdentity identity,String id,int ordinal,MultipartFile file){
        requireEnabled();if(file==null||file.isEmpty()||file.getSize()>2L*1024*1024)throw MiniappDemoPhotoException.input();
        Path temporary=null;try{
            capacity();temporary=Files.createTempFile(root,"incoming-",".part");Files.setPosixFilePermissions(temporary,PosixFilePermissions.fromString("rw-------"));
            MessageDigest sha=MessageDigest.getInstance("SHA-256");long size=0;
            try(InputStream input=file.getInputStream();OutputStream out=Files.newOutputStream(temporary)){
                byte[] buffer=new byte[16384];int n;while((n=input.read(buffer))!=-1){size+=n;if(size>2L*1024*1024)throw MiniappDemoPhotoException.input();sha.update(buffer,0,n);out.write(buffer,0,n);}
            }
            if(size<1)throw MiniappDemoPhotoException.input();
            try(var channel=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
            final long length=size;String digest=HexFormat.of().formatHex(sha.digest());Path temp=temporary;
            return tx.execute(status->{
                active(identity);Session session=locked(identity,id);available(session);
                if(ordinal<0||ordinal>=session.count)throw MiniappDemoPhotoException.input();
                List<Item> items=items(identity,id);Item old=items.stream().filter(p->p.ordinal==ordinal).findFirst().orElse(null);
                if(old!=null){if(old.size!=length||!old.digest.equals(digest))throw conflict();return view(identity,session);}
                if(!"OPEN".equals(session.state))throw conflict();
                long total=length+items.stream().mapToLong(Item::size).sum();if(total>("PHOTO_6IN_COLLAGE".equals(session.variant)?12L:30L)*1024*1024)throw MiniappDemoPhotoException.input();
                String object=UUID.randomUUID().toString();Path destination=object(object);
                try{Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE);try(var channel=java.nio.channels.FileChannel.open(root,StandardOpenOption.READ)){channel.force(true);}}
                catch(IOException e){throw MiniappDemoPhotoException.unavailable();}
                // On an unknown commit retain the private object; exact DB reachability cleanup happens only after TTL.
                jdbc.update("INSERT INTO print_photo_upload_item(session_id,tenant_id,store_id,principal_id,ordinal,content_sha256,size_bytes,storage_key) VALUES(?,?,?,?,?,?,?,?)",id,identity.tenantId(),identity.storeId(),identity.principalId(),ordinal,digest,length,object);
                return view(identity,session);
            });
        }catch(MiniappDemoPhotoException e){throw e;}catch(Exception e){throw MiniappDemoPhotoException.unavailable();}
        finally{if(temporary!=null)try{Files.deleteIfExists(temporary);}catch(IOException ignored){}}
    }
    public View complete(MiniappDemoIdentity identity,String id){
        requireEnabled();Session frozen=tx.execute(status->{
            active(identity);Session session=locked(identity,id);available(session);if(session.finalId!=null)return session;
            List<Item> items=items(identity,id);if(items.size()!=session.count)throw conflict();
            StringBuilder manifest=new StringBuilder(VERSION).append('\n').append(session.variant).append('\n').append(session.count);
            for(int i=0;i<items.size();i++){Item p=items.get(i);if(p.ordinal!=i)throw conflict();manifest.append('\n').append(i).append(':').append(p.size).append(':').append(p.digest);}
            String digest=hash(manifest.toString());if(session.manifest!=null&&!session.manifest.equals(digest))throw conflict();
            jdbc.update("UPDATE print_photo_upload_session SET state='FINALIZING',manifest_sha256=?,updated_at_ms=? WHERE id=? AND tenant_id=? AND store_id=? AND principal_id=? AND final_file_id IS NULL",digest,System.currentTimeMillis(),id,identity.tenantId(),identity.storeId(),identity.principalId());
            return locked(identity,id);
        });
        if(frozen.finalId!=null)return tx.execute(s->view(identity,frozen));
        String domain="photo-file-v1:"+hash(frozen.id+"\n"+frozen.manifest+"\n"+VERSION);
        MiniappDemoFileMetadata result;
        List<String> prior=jdbc.query("SELECT id FROM print_miniapp_demo_file WHERE tenant_id=? AND store_id=? AND principal_id=? AND idempotency_sha256=? AND source_kind='PHOTO' AND source_variant=?",(rs,n)->rs.getString(1),identity.tenantId(),identity.storeId(),identity.principalId(),hash(domain),frozen.variant);
        if(!prior.isEmpty())result=files.get(identity,prior.get(0));
        else{
            List<Item> inputs=items(identity,id);
            for(Item p:inputs){try{Path path=object(p.key);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path)||Files.size(path)!=p.size||!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))).equals(p.digest))throw MiniappDemoPhotoException.unavailable();}catch(Exception e){throw MiniappDemoPhotoException.unavailable();}}
            byte[] pdf=renderer.render(inputs.stream().map(p->object(p.key)).toList(),frozen.variant,frozen.manifest);
            result=files.uploadGeneratedPhoto(identity,new GeneratedPdf(pdf),domain,MiniappDemoFileVariant.valueOf(frozen.variant));
        }
        MiniappDemoFileMetadata generated=result;
        return tx.execute(status->{
            active(identity);Session current=locked(identity,id);available(current);
            if(current.finalId!=null){if(!current.finalId.equals(generated.id()))throw conflict();return view(identity,current);}
            if(!"FINALIZING".equals(current.state)||!Objects.equals(current.manifest,frozen.manifest))throw conflict();
            int changed=jdbc.update("UPDATE print_photo_upload_session SET final_file_id=?,state='COMPLETE',updated_at_ms=? WHERE id=? AND tenant_id=? AND store_id=? AND principal_id=? AND state='FINALIZING' AND final_file_id IS NULL",generated.id(),System.currentTimeMillis(),id,identity.tenantId(),identity.storeId(),identity.principalId());
            if(changed!=1)throw MiniappDemoPhotoException.unavailable();return view(identity,locked(identity,id));
        });
    }
    private Session locked(MiniappDemoIdentity identity,String id){
        if(identity==null||id==null||!id.matches("[0-9a-f-]{36}"))throw missing();
        var rows=jdbc.query("SELECT id,variant,expected_count,state,expires_at_ms,manifest_sha256,final_file_id,render_version FROM print_photo_upload_session WHERE tenant_id=? AND store_id=? AND principal_id=? AND id=? FOR UPDATE",(rs,n)->{
            if(!VERSION.equals(rs.getString("render_version")))throw MiniappDemoPhotoException.unavailable();
            return new Session(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getString(4),rs.getLong(5),rs.getString(6),rs.getString(7));},identity.tenantId(),identity.storeId(),identity.principalId(),id);
        if(rows.size()!=1)throw missing();return rows.get(0);
    }
    private List<Item> items(MiniappDemoIdentity identity,String id){return jdbc.query("SELECT ordinal,size_bytes,content_sha256,storage_key FROM print_photo_upload_item WHERE tenant_id=? AND store_id=? AND principal_id=? AND session_id=? ORDER BY ordinal",(rs,n)->new Item(rs.getInt(1),rs.getLong(2),rs.getString(3),rs.getString(4)),identity.tenantId(),identity.storeId(),identity.principalId(),id);}
    private View view(MiniappDemoIdentity identity,Session s){available(s);return new View(s.id,s.state,s.variant,s.count,items(identity,s.id).stream().map(Item::ordinal).toList(),s.expiry,s.finalId==null?null:files.get(identity,s.finalId));}
    private void active(MiniappDemoIdentity identity){if(identity==null||!sessions.lockIdentity(identity).orElseThrow(MiniappDemoPhotoUploadService::missing).activeAt(System.currentTimeMillis()))throw missing();}
    private void available(Session s){if(s.expiry<=System.currentTimeMillis())throw new MiniappDemoPhotoException(410,43203,"Photo upload expired");}
    private void requireEnabled(){if(!enabled||root==null)throw new MiniappDemoPhotoException(503,43202,"Photo generation disabled");files.requireStorageLockHeld();}
    private void capacity()throws IOException{if(Files.getFileStore(root).getUsableSpace()<config.getMinFreeBytes()+2L*1024*1024)throw MiniappDemoPhotoException.unavailable();}
    private Path object(String key){if(key==null||!key.matches("[0-9a-f-]{36}"))throw MiniappDemoPhotoException.unavailable();return root.resolve(key+".bin");}
    private static MiniappDemoPhotoException missing(){return new MiniappDemoPhotoException(404,43201,"Photo upload not found");}
    private static MiniappDemoPhotoException conflict(){return new MiniappDemoPhotoException(409,43207,"Photo upload conflict");}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private record GeneratedPdf(byte[] bytes)implements MultipartFile{
        public String getName(){return "file";}public String getOriginalFilename(){return "photo.pdf";}public String getContentType(){return "application/pdf";}public boolean isEmpty(){return bytes.length==0;}public long getSize(){return bytes.length;}public byte[] getBytes(){return bytes.clone();}public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}public void transferTo(File file){throw new UnsupportedOperationException();}
    }
}
