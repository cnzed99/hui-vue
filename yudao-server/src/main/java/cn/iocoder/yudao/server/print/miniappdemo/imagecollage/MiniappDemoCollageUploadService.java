package cn.iocoder.yudao.server.print.miniappdemo.imagecollage;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileService;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileProperties;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileMetadata;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static cn.iocoder.yudao.server.print.miniappdemo.imagecollage.MiniappDemoCollageUploadException.Reason.*;

/** Bounded, process-local staging. Only the derived PDF enters the existing durable file chain. */
@Service
public final class MiniappDemoCollageUploadService {
    static final long MAX_IMAGE=2L*1024*1024, MAX_TOTAL=12L*1024*1024, MIN_FREE=10L*1024*1024*1024;
    static final Set<PosixFilePermission> DIR=PosixFilePermissionsHolder.DIR, FILE=PosixFilePermissionsHolder.FILE;
    private final MiniappDemoCollageUploadProperties properties;
    private final MiniappDemoFileService files;
    private final CollageCreator collages;
    private final MiniappDemoFileProperties fileProperties;
    private final MiniappDemoImageCollageProperties collageProperties;
    private final Object lifecycle=new Object(), registry=new Object();
    private final Map<String,Session> sessions=new HashMap<>();
    private Path root;
    private ScheduledExecutorService cleaner;
    private boolean stopping;
    private int active;

    @org.springframework.beans.factory.annotation.Autowired
    public MiniappDemoCollageUploadService(MiniappDemoCollageUploadProperties properties,
            MiniappDemoFileService files, MiniappDemoImageCollageFileService collages,
            MiniappDemoFileProperties fileProperties, MiniappDemoImageCollageProperties collageProperties) {
        this(properties,files,collages::create,fileProperties,collageProperties);
    }
    @FunctionalInterface interface CollageCreator {
        MiniappDemoFileMetadata create(MiniappDemoIdentity identity,List<MultipartFile> images,String key);
    }
    MiniappDemoCollageUploadService(MiniappDemoCollageUploadProperties properties, MiniappDemoFileService files,
            CollageCreator collages, MiniappDemoFileProperties fileProperties, MiniappDemoImageCollageProperties collageProperties) {
        this.properties=properties;this.files=files;this.collages=collages;
        this.fileProperties=fileProperties;this.collageProperties=collageProperties;
    }
    @PostConstruct public void initialize() {
        properties.validateEnabled();
        if(!properties.isEnabled())return;
        if(!collageProperties.isEnabled())throw new IllegalStateException("Staged upload requires collage rendering");
        files.requireStorageLockHeld();
        try {
            Path expected=fileProperties.getStorageRoot().toRealPath().resolve("image-collage-upload-scratch");
            if(!properties.getRoot().normalize().equals(expected))throw new IOException("Unsafe staging root");
            root=privateDirectory(expected);
            try(var entries=Files.newDirectoryStream(root)) {
                for(Path entry:entries) deleteDirectory(entry);
            }
        } catch(IOException e){throw new IllegalStateException("Unable to initialize private collage upload storage",e);}
        cleaner=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"collage-upload-cleaner");t.setDaemon(true);return t;});
        cleaner.scheduleWithFixedDelay(this::expire,60,60,TimeUnit.SECONDS);
    }
    public record View(String uploadId,String state,int fileCount,List<Integer> uploadedIndexes,long expiresAtMs,
                       MiniappDemoFileMetadata result) {}
    record PartManifest(long size,String sha256) {}
    record Manifest(List<PartManifest> parts,String digest) {}
    private static final class Session {
        final String id,key,owner; final Manifest manifest; final long expires; final Path directory;
        final boolean[] uploaded; volatile String state="OPEN"; boolean cancelled; MiniappDemoFileMetadata result;
        Session(String id,String key,String owner,Manifest manifest,long expires,Path directory){
            this.id=id;this.key=key;this.owner=owner;this.manifest=manifest;this.expires=expires;this.directory=directory;
            uploaded=new boolean[manifest.parts.size()];
        }
    }
    public View create(MiniappDemoIdentity identity,JsonNode body,String key) {
        begin();
        try {
            checkIdentity(identity); if(key==null||!key.matches("[!-~]{1,128}"))throw failure(INVALID_INPUT);
            Manifest manifest=parseManifest(body); String owner=owner(identity); Session found=null;
            synchronized(registry) {
                for(Session s:sessions.values()) if(s.owner.equals(owner)&&s.key.equals(key)){found=s;break;}
            }
            if(found!=null) synchronized(found) {
                if(found.expires>System.currentTimeMillis()&&!found.cancelled){
                    if(!found.manifest.digest.equals(manifest.digest))throw failure(CONFLICT);
                    return view(found,identity);
                }
                remove(found);
            }
            synchronized(registry) {
                // Reserve atomically; no session monitor is acquired while holding registry.
                for(Session s:sessions.values()) {
                    if(s.owner.equals(owner)&&s.key.equals(key))throw failure(BUSY);
                }
                long count=sessions.values().stream().filter(s->!"COMPLETE".equals(s.state)).count();
                long own=sessions.values().stream().filter(s->s.owner.equals(owner)&&!"COMPLETE".equals(s.state)).count();
                if(count>=4||own>=1||sessions.size()>=64)throw failure(BUSY);
                capacity(MAX_TOTAL);
                String id=UUID.randomUUID().toString();
                Path dir=privateDirectory(root.resolve(id));
                Session s=new Session(id,key,owner,manifest,Math.min(identity.expiresAtMs(),
                    System.currentTimeMillis()+properties.getTtlSeconds()*1000L),dir);
                sessions.put(id,s); return view(s,identity);
            }
        } catch(IOException e){throw failure(STORAGE_UNAVAILABLE);} finally{end();}
    }
    public View upload(MiniappDemoIdentity identity,String id,int index,MultipartFile file) {
        begin();
        try {
            Session s=find(identity,id);
            synchronized(s) {
                requireOpen(s,identity);
                if(index<0||index>=s.uploaded.length||file==null||file.isEmpty())throw failure(INVALID_INPUT);
                PartManifest expected=s.manifest.parts.get(index);
                if(file.getSize()!=expected.size)throw failure(CONFLICT);
                capacity(MAX_IMAGE);
                Path temporary=Files.createTempFile(s.directory,"incoming-",".part");
                try {
                    Files.setPosixFilePermissions(temporary,FILE);
                    Content actual=copy(file.getInputStream(),temporary,MAX_IMAGE);
                    if(actual.size!=expected.size||!actual.digest.equals(expected.sha256))throw failure(CONFLICT);
                    checkAvailable(s,identity);
                    if(s.uploaded[index])return view(s,identity);
                    Path target=s.directory.resolve("part-"+index);
                    Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE);
                    forceDirectory(s.directory);
                    s.uploaded[index]=true; return view(s,identity);
                } finally {Files.deleteIfExists(temporary);}
            }
        } catch(IOException e){throw failure(STORAGE_UNAVAILABLE);} finally{end();}
    }
    public View complete(MiniappDemoIdentity identity,String id) {
        begin(); Session s=null; boolean committing=false;
        try {
            s=find(identity,id);
            List<MultipartFile> inputs=new ArrayList<>();
            synchronized(s) {
                checkAvailable(s,identity);
                if(s.result!=null)return view(s,identity);
                if(!"OPEN".equals(s.state))throw failure(BUSY);
                for(boolean ready:s.uploaded)if(!ready)throw failure(INCOMPLETE);
                // Recompute from actual private files before crossing the renderer boundary.
                List<PartManifest> actual=new ArrayList<>();
                for(int i=0;i<s.uploaded.length;i++){
                    Path path=s.directory.resolve("part-"+i);
                    if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path))throw failure(STORAGE_UNAVAILABLE);
                    Content c=copy(Files.newInputStream(path),null,MAX_IMAGE);
                    PartManifest expected=s.manifest.parts.get(i);
                    if(c.size!=expected.size||!c.digest.equals(expected.sha256))throw failure(CONFLICT);
                    actual.add(new PartManifest(c.size,c.digest)); inputs.add(new PrivatePart(path,c.size));
                }
                if(!digestManifest(actual).equals(s.manifest.digest))throw failure(CONFLICT);
                s.state="COMMITTING";committing=true;
            }
            MiniappDemoFileMetadata result=collages.create(identity,inputs,s.key);
            synchronized(s) {
                s.result=result;s.state="CLEANUP_PENDING";committing=false;
                clearImages(s);s.state="COMPLETE";
                if(s.cancelled||s.expires<=System.currentTimeMillis()){remove(s);throw failure(NOT_FOUND);}
                return view(s,identity);
            }
        } catch(MiniappDemoImageCollageException e){
            if(s!=null&&committing) synchronized(s) {
                boolean retry=switch(e.reason()){case RENDER_BUSY,RENDER_TIMEOUT,RENDER_UNAVAILABLE,CAPACITY_LOW -> true;default -> false;};
                if(retry&&!s.cancelled&&s.expires>System.currentTimeMillis())s.state="OPEN";else remove(s);
            }
            throw e;
        } catch(MiniappDemoSessionException e){
            if(s!=null&&committing)synchronized(s){remove(s);}
            throw e;
        } catch(MiniappDemoFileException e){
            if(s!=null&&committing)synchronized(s){
                if(e.code()==1_927_007 || s.cancelled || s.expires<=System.currentTimeMillis())remove(s);
                else s.state="OPEN";
            }
            throw e;
        } catch(IOException|RuntimeException e){
            if(s!=null&&committing)synchronized(s){s.state="OPEN";if(s.cancelled)remove(s);}
            if(e instanceof MiniappDemoCollageUploadException known)throw known;
            throw failure(STORAGE_UNAVAILABLE);
        } finally{end();}
    }
    public void cancel(MiniappDemoIdentity identity,String id){
        begin();try{Session s=find(identity,id);synchronized(s){
            checkAvailable(s,identity);s.cancelled=true;
            if(!"COMMITTING".equals(s.state))remove(s);
        }}finally{end();}
    }
    private Session find(MiniappDemoIdentity identity,String id){
        checkIdentity(identity);
        if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw failure(NOT_FOUND);
        Session s; synchronized(registry){s=sessions.get(id);}
        if(s==null||!s.owner.equals(owner(identity)))throw failure(NOT_FOUND);
        return s;
    }
    private void requireOpen(Session s,MiniappDemoIdentity identity){
        checkAvailable(s,identity);if(!"OPEN".equals(s.state))throw failure(BUSY);
    }
    private void checkAvailable(Session s,MiniappDemoIdentity identity){
        checkIdentity(identity);
        if(s.cancelled||s.expires<=System.currentTimeMillis()){
            if("COMMITTING".equals(s.state))s.cancelled=true;else remove(s);
            throw failure(NOT_FOUND);
        }
    }
    private View view(Session s,MiniappDemoIdentity identity){
        if(s.result!=null)try{clearImages(s);s.state="COMPLETE";}catch(IOException e){throw failure(STORAGE_UNAVAILABLE);}
        List<Integer> indexes=new ArrayList<>();for(int i=0;i<s.uploaded.length;i++)if(s.uploaded[i])indexes.add(i);
        MiniappDemoFileMetadata result=s.result==null?null:files.get(identity,s.result.id());
        return new View(s.id,s.state,s.uploaded.length,List.copyOf(indexes),s.expires,result);
    }
    private void capacity(long bytes)throws IOException{
        if(root==null||Files.getFileStore(root).getUsableSpace()<MIN_FREE+bytes)throw failure(STORAGE_UNAVAILABLE);
    }
    private void begin(){synchronized(lifecycle){
        if(!properties.isEnabled())throw failure(DISABLED);
        if(stopping||root==null)throw failure(STORAGE_UNAVAILABLE);
        files.requireStorageLockHeld();active++;
    }}
    private void end(){synchronized(lifecycle){active--;lifecycle.notifyAll();}}
    private void expire(){
        try{begin();}catch(RuntimeException e){return;}
        try{
            List<Session> snapshot;synchronized(registry){snapshot=List.copyOf(sessions.values());}
            for(Session s:snapshot)synchronized(s){if(s.expires<=System.currentTimeMillis()){
                if("COMMITTING".equals(s.state))s.cancelled=true;else remove(s);
            }}
        }finally{end();}
    }
    @PreDestroy public void stop(){
        synchronized(lifecycle){stopping=true;}
        if(cleaner!=null)cleaner.shutdownNow();
        boolean interrupted=false;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        synchronized(lifecycle){
            while(active>0&&System.nanoTime()<deadline)try{
                TimeUnit.NANOSECONDS.timedWait(lifecycle,deadline-System.nanoTime());
            }catch(InterruptedException e){interrupted=true;break;}
            if(active>0)files.retainStorageLockUntilProcessExit();
        }
        if(interrupted)Thread.currentThread().interrupt();
    }
    private void remove(Session s){
        s.cancelled=true;
        try{deleteDirectory(s.directory);}catch(IOException e){throw failure(STORAGE_UNAVAILABLE);}
        synchronized(registry){sessions.remove(s.id,s);}
    }
    private static void forceDirectory(Path dir)throws IOException{
        try(var channel=java.nio.channels.FileChannel.open(dir,StandardOpenOption.READ)){channel.force(true);}
    }
    private void clearImages(Session s)throws IOException{
        try(var entries=Files.newDirectoryStream(s.directory)){
            for(Path p:entries){if(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe stage");Files.delete(p);}
        }
        forceDirectory(s.directory);
    }
    private void deleteDirectory(Path directory)throws IOException{
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))return;
        if(root==null||!directory.toAbsolutePath().normalize().getParent().equals(root)
                ||!directory.getFileName().toString().matches("[0-9a-f-]{36}")
                ||Files.isSymbolicLink(directory)||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe stage directory");
        try(var entries=Files.newDirectoryStream(directory)){
            for(Path p:entries){if(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe stage");Files.delete(p);}
        }
        Files.deleteIfExists(directory);
    }
    private static Path privateDirectory(Path path)throws IOException{
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(path,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(DIR));
        if(Files.isSymbolicLink(path)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||!Files.getPosixFilePermissions(path).equals(DIR))
            throw new IOException("Unsafe private directory");
        return path.toRealPath();
    }
    static Manifest parseManifest(JsonNode body){
        if(body==null||!body.isObject()||body.size()!=2||!body.has("renderVersion")||!body.has("files")
                ||!body.path("renderVersion").isTextual()
                ||!MiniappDemoImageCollageProperties.RENDER_VERSION.equals(body.path("renderVersion").textValue()))throw failure(INVALID_INPUT);
        JsonNode list=body.path("files");
        if(!list.isArray()||list.size()<1||list.size()>9)throw failure(INVALID_INPUT);
        List<PartManifest> result=new ArrayList<>();long total=0;
        for(int i=0;i<list.size();i++){
            JsonNode p=list.get(i);
            if(!p.isObject()||p.size()!=3||!p.path("index").isIntegralNumber()||!p.path("index").canConvertToInt()
                ||p.path("index").intValue()!=i||!p.path("sizeBytes").isIntegralNumber()||!p.path("sizeBytes").canConvertToLong()
                ||!p.path("sha256").isTextual()||!p.path("sha256").textValue().matches("[0-9a-f]{64}"))throw failure(INVALID_INPUT);
            long size=p.path("sizeBytes").longValue();if(size<1||size>MAX_IMAGE)throw failure(TOO_LARGE);
            total+=size;if(total>MAX_TOTAL)throw failure(TOO_LARGE);
            result.add(new PartManifest(size,p.path("sha256").textValue()));
        }
        return new Manifest(List.copyOf(result),digestManifest(result));
    }
    private static String digestManifest(List<PartManifest> parts){
        MessageDigest md=sha256();md.update(MiniappDemoImageCollageProperties.RENDER_VERSION.getBytes(StandardCharsets.US_ASCII));
        md.update(ByteBuffer.allocate(4).putInt(parts.size()).array());
        for(PartManifest p:parts){md.update(ByteBuffer.allocate(8).putLong(p.size).array());md.update(HexFormat.of().parseHex(p.sha256));}
        return HexFormat.of().formatHex(md.digest());
    }
    private record Content(long size,String digest){}
    private static Content copy(InputStream source,Path target,long limit)throws IOException{
        MessageDigest md=sha256();long count=0;
        try(InputStream in=source;OutputStream out=target==null?OutputStream.nullOutputStream():Files.newOutputStream(target,StandardOpenOption.WRITE)){
            byte[] buffer=new byte[65536];int n;
            while((n=in.read(buffer))!=-1){if(n==0)continue;if(count>limit-n)throw failure(TOO_LARGE);count+=n;md.update(buffer,0,n);out.write(buffer,0,n);}
        }
        if(target!=null)try(var channel=java.nio.channels.FileChannel.open(target,StandardOpenOption.WRITE)){channel.force(true);}
        return new Content(count,HexFormat.of().formatHex(md.digest()));
    }
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException("SHA-256 unavailable");}}
    private static String owner(MiniappDemoIdentity i){return i.tenantId()+"/"+i.storeId()+"/"+i.principalId();}
    private static void checkIdentity(MiniappDemoIdentity i){if(i==null||i.expiresAtMs()<=System.currentTimeMillis())throw failure(NOT_FOUND);}
    private static MiniappDemoCollageUploadException failure(MiniappDemoCollageUploadException.Reason r){return new MiniappDemoCollageUploadException(r);}
    private record PrivatePart(Path path,long size) implements MultipartFile {
        public String getName(){return "files";}public String getOriginalFilename(){return "image.bin";}
        public String getContentType(){return "application/octet-stream";}public boolean isEmpty(){return size==0;}
        public long getSize(){return size;}public byte[] getBytes()throws IOException{return Files.readAllBytes(path);}
        public InputStream getInputStream()throws IOException{return Files.newInputStream(path);}
        public void transferTo(File ignored){throw new UnsupportedOperationException("Private read-only input");}
    }
    private static class PosixFilePermissionsHolder {
        static final Set<PosixFilePermission> DIR=java.nio.file.attribute.PosixFilePermissions.fromString("rwx------");
        static final Set<PosixFilePermission> FILE=java.nio.file.attribute.PosixFilePermissions.fromString("rw-------");
    }
}
