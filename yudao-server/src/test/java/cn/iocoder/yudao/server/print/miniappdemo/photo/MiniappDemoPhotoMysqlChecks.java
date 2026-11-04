package cn.iocoder.yudao.server.print.miniappdemo.photo;
import cn.iocoder.yudao.server.print.miniappdemo.*;
import cn.iocoder.yudao.server.print.miniappdemo.file.*;
import cn.iocoder.yudao.server.print.miniappdemo.quote.*;
import cn.iocoder.yudao.server.print.scope.*;
import cn.iocoder.yudao.server.print.miniappdemo.order.*;
import cn.iocoder.yudao.server.print.miniappdemo.printtask.*;
import cn.iocoder.yudao.server.print.miniappdemo.cancellation.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real independent MySQL transactions. Rendering/file-worker boundaries stubbed only for upload recovery cases. */
public final class MiniappDemoPhotoMysqlChecks {
    private static int checks;private static JdbcTemplate jdbc;private static DriverManagerDataSource ds;
    private static final String DOCUMENT="8cf69353-bc69-5039-ad47-b59e3c791c76";
    private static final ObjectMapper mapper=new ObjectMapper();
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void reject(Runnable action,String message){try{action.run();throw new AssertionError("Accepted "+message);}catch(org.springframework.dao.DataAccessException|MiniappDemoPhotoException|MiniappDemoQuoteException good){checks++;}}
    public static void main(String[] args)throws Exception{
        int port=Integer.parseInt(args[0]);if(port<40000||port>59999)throw new IllegalArgumentException();
        Path root=Path.of(args[1]).toRealPath();if(!root.startsWith("/tmp/print-photo-6in-db-")){
            if(!root.getParent().getFileName().toString().startsWith("print-photo-6in-db-"))throw new IllegalArgumentException("Unsafe fixture root");
        }
        ds=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:"+port+"/photo_candidate?connectTimeout=3000&socketTimeout=10000&sslMode=DISABLED","root","");jdbc=new JdbcTemplate(ds);
        var manager=new DataSourceTransactionManager(ds);var tx=new TransactionTemplate(manager);var sessions=new MiniappDemoSessionRepository(jdbc);
        long now=System.currentTimeMillis();String principal=UUID.randomUUID().toString(),session=UUID.randomUUID().toString();sessions.insert(principal,session,1,1,"a".repeat(64),now,now+3600_000L);
        var identity=new MiniappDemoIdentity(session,principal,1,1,now+3600_000L);
        String photo=file(identity,"PHOTO","PHOTO_6IN",30),idPhoto=file(identity,"ID_PHOTO","ONE_INCH",1),document=file(identity,"DOCUMENT",null,20);
        reject(()->file(identity,"DOCUMENT",null,21),"document21");reject(()->file(identity,"PHOTO","PHOTO_6IN",31),"photo31");reject(()->file(identity,"ID_PHOTO","ONE_INCH",2),"id2");reject(()->file(identity,"PHOTO","PHOTO_6IN_COLLAGE",2),"collage2");reject(()->file(identity,"PHOTO",null,1),"null variant");reject(()->file(identity,"BAD","PHOTO_6IN",1),"unknown kind");
        String initial=active();reject(()->quote(identity,photo,initial),"gate0");quote(identity,document,DOCUMENT);reject(()->quote(identity,document,initial),"document photo profile");reject(()->quote(identity,photo,DOCUMENT),"photo doc profile");
        jdbc.update("UPDATE print_photo_price_binding SET quote_enabled=1");quote(identity,photo,initial);quote(identity,idPhoto,initial);
        reject(()->jdbc.update("UPDATE print_price_profile SET simplex_price_per_sheet='2.00' WHERE id=?",initial),"immutable profile");
        PrintScopeService scope=new PrintScopeService(jdbc){@Override public PrintScope requireCurrent(){return new PrintScope(1,1,1);}};
        var prices=new MiniappDemoPhotoPriceService(scope,jdbc,manager);
        var pool=Executors.newFixedThreadPool(3);
        try{
            var barrier=new CyclicBarrier(2);AtomicInteger won=new AtomicInteger(),lost=new AtomicInteger();
            Callable<Void> update=()->{barrier.await();try{prices.update(mapper.readTree("{\"expectedRevision\":\"1\",\"pricePerSheet\":\"2.00\"}"));won.incrementAndGet();}catch(MiniappDemoPhotoException e){if(e.status()!=409)throw e;lost.incrementAndGet();}return null;};
            var a=pool.submit(update);var b=pool.submit(update);a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);
            check(won.get()==1&&lost.get()==1,"CAS single winner");check("2.00".equals(prices.get().pricePerSheet()),"canonical new price");check(!active().equals(initial),"new immutable version");
            check(jdbc.queryForObject("SELECT COUNT(*) FROM system_role_menu rm JOIN system_role r ON r.id=rm.role_id WHERE rm.menu_id=2011 AND (r.code<>'tenant_admin' OR rm.tenant_id<>1)",Integer.class)==0,"no other update grants");
            check(jdbc.queryForObject("SELECT COUNT(*) FROM system_role_menu rm JOIN system_role r ON r.id=rm.role_id WHERE rm.menu_id=2010 AND r.code='print_reader'",Integer.class)==1,"reader query grant");
            // Old RR read view must not bypass newly committed gate/active profile.
            try(Connection left=ds.getConnection()){
                left.setAutoCommit(false);left.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                try(var st=left.createStatement();var ignored=st.executeQuery("SELECT quote_enabled FROM print_photo_price_binding")){while(ignored.next())check(ignored.getInt(1)==1,"old snapshot open");}
                jdbc.update("UPDATE print_photo_price_binding SET quote_enabled=0");
                try{insertQuote(left,identity,photo,active());throw new AssertionError("stale view bypassed gate");}catch(SQLException expected){check("45000".equals(expected.getSQLState()),"current locking gate read");}finally{left.rollback();}
            }
            jdbc.update("UPDATE print_photo_price_binding SET quote_enabled=1");
            String before=active();
            try(Connection left=ds.getConnection()){
                left.setAutoCommit(false);left.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                try(var st=left.createStatement();var result=st.executeQuery("SELECT active_profile_id FROM print_photo_price_binding")){result.next();check(before.equals(result.getString(1)),"old profile snapshot");}
                prices.update(mapper.readTree("{\"expectedRevision\":\"2\",\"pricePerSheet\":\"3.00\"}"));
                try{insertQuote(left,identity,photo,before);throw new AssertionError("stale profile accepted");}catch(SQLException expected){check("45000".equals(expected.getSQLState()),"current locking profile read");}finally{left.rollback();}
            }
            // Quote first: trigger keeps binding lock until the outer transaction commits.
            try(Connection left=ds.getConnection()){
                left.setAutoCommit(false);insertQuote(left,identity,photo,active());CountDownLatch started=new CountDownLatch(1);
                var blocked=pool.submit(()->{started.countDown();return jdbc.update("UPDATE print_photo_price_binding SET quote_enabled=0");});started.await();Thread.sleep(150);check(!blocked.isDone(),"gate waits for quoting transaction");left.commit();check(blocked.get(5,TimeUnit.SECONDS)==1,"gate after quote commit");
            }
            jdbc.update("UPDATE print_photo_price_binding SET quote_enabled=1");
            var props=new MiniappDemoQuoteProperties();props.setEnabled(true);props.setTtlSeconds(600L);props.setPriceProfileId(DOCUMENT);props.setMaxActiveQuotesPerPrincipal(100);
            var quotes=new MiniappDemoQuoteService(props,new MiniappDemoQuoteRepository(jdbc),sessions,mapper,manager);set(quotes,"photoQuoteEnabled",true);
            JsonNode input=mapper.readTree("{\"fileId\":\""+photo+"\",\"paper\":\"PHOTO_6IN\",\"color\":\"COLOR\",\"copies\":2,\"side\":\"SIMPLEX\"}");
            var original=quotes.create(identity,input,"stable-price-key");check("180.00".equals(original.snapshot().totalAmount()),"30x2x3 price");
            prices.update(mapper.readTree("{\"expectedRevision\":\"3\",\"pricePerSheet\":\"4.00\"}"));jdbc.update("UPDATE print_photo_price_binding SET quote_enabled=0");set(quotes,"photoQuoteEnabled",false);
            var replay=quotes.create(identity,input,"stable-price-key");check(replay.id().equals(original.id())&&replay.snapshot().equals(original.snapshot()),"lost response original after price/gate change");
            reject(()->quotes.create(identity,input,"new-gate-closed"),"new quote closed");jdbc.update("UPDATE print_photo_price_binding SET quote_enabled=1");set(quotes,"photoQuoteEnabled",true);
            check("240.00".equals(quotes.create(identity,input,"new-price-key").snapshot().totalAmount()),"new key new price");
            var wrong=((com.fasterxml.jackson.databind.node.ObjectNode)input.deepCopy()).put("side","DUPLEX");reject(()->quotes.create(identity,wrong,"wrong-side"),"photo duplex");
            var other=new MiniappDemoIdentity(UUID.randomUUID().toString(),UUID.randomUUID().toString(),1,1,now+3600_000L);
            reject(()->quote(other,photo,active()),"foreign principal SQL");
            executionAdmission(root,identity,sessions,manager,quotes,scope);
            uploadRecovery(root,identity,sessions,manager,pool);
        }finally{pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
        System.out.println("PHOTO_MYSQL_CONCURRENCY_PASS checks="+checks+" real-db trigger CAS quote-replay staged-upload-recovery; renderer/fileworker stubbed in staged fixture only");
    }
    private static void executionAdmission(Path root,MiniappDemoIdentity identity,MiniappDemoSessionRepository sessions,DataSourceTransactionManager manager,MiniappDemoQuoteService quotes,PrintScopeService scope)throws Exception{
        Path objects=Files.createDirectory(root.resolve("objects"));byte[] content="%PDF-object-admission-fixture".getBytes(StandardCharsets.US_ASCII);String hash=hash(new String(content,StandardCharsets.US_ASCII));
        String file=file(identity,"PHOTO","PHOTO_6IN",1);Files.write(objects.resolve(file+".pdf"),content);jdbc.update("UPDATE print_miniapp_demo_file SET content_sha256=? WHERE id=?",hash,file);
        var q=quotes.create(identity,mapper.readTree("{\"fileId\":\""+file+"\",\"paper\":\"PHOTO_6IN\",\"color\":\"COLOR\",\"copies\":1,\"side\":\"SIMPLEX\"}"),"execution-quote");
        var orderProps=new MiniappDemoOrderProperties();orderProps.setEnabled(true);orderProps.setMaxOrdersPerPrincipal(100);
        var orders=new MiniappDemoOrderService(orderProps,new MiniappDemoOrderRepository(jdbc),sessions,new MiniappDemoCancellationRepository(jdbc),mapper,manager);
        var order=orders.create(identity,mapper.readTree("{\"quoteId\":\""+q.id()+"\"}"),"execution-order");
        var fileConfig=new MiniappDemoFileProperties();fileConfig.setStorageRoot(root);var admission=new MiniappDemoFileObjectAdmission(fileConfig);
        var payProps=new MiniappDemoPaymentProperties();payProps.setEnabled(true);var payments=new MiniappDemoPaymentService(payProps,new MiniappDemoOrderRepository(jdbc),new MiniappDemoPaymentRepository(jdbc),sessions,mapper,manager);set(payments,"objectAdmission",admission);
        JsonNode pay=mapper.readTree("{\"orderId\":\""+order.id()+"\",\"mode\":\"DEMO\"}");
        Files.delete(objects.resolve(file+".pdf"));
        try{payments.create(identity,pay,"execution-payment");throw new AssertionError("missing payment file accepted");}catch(MiniappDemoPaymentException good){checks++;}
        check(jdbc.queryForObject("SELECT COUNT(*) FROM print_miniapp_demo_payment_receipt",Integer.class)==0,"no payment on missing object");Files.write(objects.resolve(file+".pdf"),content);
        long expiry=jdbc.queryForObject("SELECT expires_at_ms FROM print_miniapp_demo_file WHERE id=?",Long.class,file);jdbc.update("UPDATE print_miniapp_demo_file SET expires_at_ms=created_at_ms+1 WHERE id=?",file);
        try{payments.create(identity,pay,"execution-payment");throw new AssertionError("expired payment file accepted");}catch(MiniappDemoPaymentException good){checks++;}
        jdbc.update("UPDATE print_miniapp_demo_file SET expires_at_ms=? WHERE id=?",expiry,file);payments.create(identity,pay,"execution-payment");
        var taskProps=new MiniappDemoPrintTaskProperties();taskProps.setEnabled(true);taskProps.setLeaseSeconds(30);var tasks=new MiniappDemoPrintTaskService(taskProps,new MiniappDemoPrintTaskRepository(jdbc),scope,mapper,manager);set(tasks,"objectAdmission",admission);
        Files.delete(objects.resolve(file+".pdf"));try{tasks.create(order.id(),mapper.readTree("{}"),"execution-task");throw new AssertionError("missing create task accepted");}catch(MiniappDemoPrintTaskException good){checks++;}
        check(jdbc.queryForObject("SELECT COUNT(*) FROM print_miniapp_demo_print_task",Integer.class)==0,"no task on missing object");Files.write(objects.resolve(file+".pdf"),content);
        var task=tasks.create(order.id(),mapper.readTree("{}"),"execution-task");
        for(String action:new String[]{"CLAIM","ACCEPT","START"}){
            String payload="{\"action\":\""+action+"\",\"expectedVersion\":"+task.version()+(action.equals("CLAIM")?"":",\"leaseId\":\""+task.leaseId()+"\",\"fence\":"+task.fence())+"}";
            Files.delete(objects.resolve(file+".pdf"));
            try{tasks.act(task.id(),mapper.readTree(payload),"execution-"+action);throw new AssertionError(action+" missing file accepted");}catch(MiniappDemoPrintTaskException good){checks++;}
            jdbc.update("UPDATE print_miniapp_demo_file SET expires_at_ms=created_at_ms+1 WHERE id=?",file);Files.write(objects.resolve(file+".pdf"),content);
            try{tasks.act(task.id(),mapper.readTree(payload),"execution-"+action);throw new AssertionError(action+" expired file accepted");}catch(MiniappDemoPrintTaskException good){checks++;}
            jdbc.update("UPDATE print_miniapp_demo_file SET expires_at_ms=? WHERE id=?",expiry,file);task=tasks.act(task.id(),mapper.readTree(payload),"execution-"+action).currentTask();
        }
        String lose="{\"action\":\"LOSE_RECEIPT\",\"expectedVersion\":"+task.version()+",\"leaseId\":\""+task.leaseId()+"\",\"fence\":"+task.fence()+"}";
        task=tasks.act(task.id(),mapper.readTree(lose),"execution-lose").currentTask();Files.delete(objects.resolve(file+".pdf"));jdbc.update("UPDATE print_miniapp_demo_file SET expires_at_ms=created_at_ms+1 WHERE id=?",file);set(quotes,"photoQuoteEnabled",false);jdbc.update("UPDATE print_photo_price_binding SET quote_enabled=0");
        check("RESULT_UNKNOWN".equals(tasks.getAdmin(task.id()).state()),"unknown evidence recoverable after expiry/gate close");check(orders.get(identity,order.id()).id().equals(order.id()),"v2 historical order after expiry");check(payments.get(identity,order.id()).orderId().equals(order.id()),"v2 payment evidence after expiry");
        check(jdbc.queryForObject("SELECT COUNT(*) FROM print_miniapp_demo_print_task",Integer.class)==1,"one task after recovery");set(quotes,"photoQuoteEnabled",true);jdbc.update("UPDATE print_photo_price_binding SET quote_enabled=1");
    }
    private static void uploadRecovery(Path root,MiniappDemoIdentity identity,MiniappDemoSessionRepository sessions,DataSourceTransactionManager manager,ExecutorService pool)throws Exception{
        var config=new MiniappDemoFileProperties();config.setStorageRoot(root);var count=new AtomicInteger();
        var fakeFiles=new MiniappDemoFileService(config,null,null,null,null,manager){
            @Override public void requireStorageLockHeld(){}
            @Override public MiniappDemoFileMetadata uploadGeneratedPhoto(MiniappDemoIdentity who,MultipartFile pdf,String key,MiniappDemoFileVariant variant){
                return new TransactionTemplate(manager).execute(status->{sessions.lockIdentity(who);var ids=jdbc.query("SELECT id FROM print_miniapp_demo_file WHERE tenant_id=? AND store_id=? AND principal_id=? AND idempotency_sha256=?",(rs,n)->rs.getString(1),who.tenantId(),who.storeId(),who.principalId(),hash(key));if(!ids.isEmpty())return get(who,ids.get(0));String id=insertFile(who,"PHOTO",variant.name(),1,hash(key));count.incrementAndGet();return get(who,id);});
            }
            @Override public MiniappDemoFileMetadata get(MiniappDemoIdentity who,String id){return jdbc.queryForObject("SELECT id,size_bytes,page_count,status,created_at_ms,expires_at_ms FROM print_miniapp_demo_file WHERE tenant_id=? AND store_id=? AND principal_id=? AND id=?",(rs,n)->new MiniappDemoFileMetadata(rs.getString(1),"photo.pdf",rs.getLong(2),rs.getInt(3),rs.getString(4),null,rs.getLong(5),rs.getLong(6),true),who.tenantId(),who.storeId(),who.principalId(),id);}
        };
        var render=new MiniappDemoPhotoProcessRenderer(fakeFiles,config,null){@Override public byte[] render(List<Path> paths,String variant,String digest){return "%PDF-fixture".getBytes(StandardCharsets.US_ASCII);}};
        var service=new MiniappDemoPhotoUploadService(jdbc,manager,sessions,fakeFiles,config,render);set(service,"enabled",true);service.initialize();
        try{
            JsonNode request=mapper.readTree("{\"variant\":\"PHOTO_6IN\",\"fileCount\":2}");var created=service.create(identity,request,"upload-original");
            check(created.uploadId().equals(service.create(identity,request,"upload-original").uploadId()),"persistent create key");
            String id=created.uploadId();var bytes=new Bytes("same-image".getBytes(StandardCharsets.US_ASCII));service.upload(identity,id,0,bytes);service.upload(identity,id,0,bytes);service.upload(identity,id,1,bytes);
            reject(()->service.upload(identity,id,0,new Bytes("different".getBytes(StandardCharsets.US_ASCII))),"same ordinal different content");
            var one=pool.submit(()->service.complete(identity,id));var two=pool.submit(()->service.complete(identity,id));String file=one.get(10,TimeUnit.SECONDS).result().id();check(file.equals(two.get(10,TimeUnit.SECONDS).result().id()),"parallel complete one final id");check(count.get()==1,"one generated durable file");
            var restarted=new MiniappDemoPhotoUploadService(jdbc,manager,sessions,fakeFiles,config,render);set(restarted,"enabled",true);restarted.initialize();
            try{
                check(file.equals(restarted.get(identity,id).result().id()),"response loss/restart recovery");
                reject(()->restarted.upload(identity,id,1,new Bytes("different".getBytes(StandardCharsets.US_ASCII))),"sealed append/replace");
                // Simulate committed file but process exit before final_file_id transaction.
                String recover=restarted.create(identity,mapper.readTree("{\"variant\":\"PHOTO_6IN\",\"fileCount\":1}"),"upload-crash").uploadId();restarted.upload(identity,recover,0,bytes);String result=restarted.complete(identity,recover).result().id();
                jdbc.update("UPDATE print_photo_upload_session SET final_file_id=NULL,state='FINALIZING' WHERE id=?",recover);
                check(result.equals(restarted.complete(identity,recover).result().id()),"file commit before final reference recovery");check(count.get()==2,"stable file key no second generation");
                jdbc.update("UPDATE print_photo_upload_session SET expires_at_ms=created_at_ms+1 WHERE id=?",recover);reject(()->restarted.complete(identity,recover),"TTL closed");
            }finally{restarted.stop();}
        }finally{service.stop();}
    }
    private static void set(Object object,String name,Object value)throws Exception{var field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static String active(){return jdbc.queryForObject("SELECT active_profile_id FROM print_photo_price_binding",String.class);}
    private static String file(MiniappDemoIdentity identity,String kind,String variant,int pages){return insertFile(identity,kind,variant,pages,hash(UUID.randomUUID().toString()));}
    private static String insertFile(MiniappDemoIdentity identity,String kind,String variant,int pages,String key){String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();jdbc.update("INSERT INTO print_miniapp_demo_file(id,tenant_id,store_id,principal_id,idempotency_sha256,content_sha256,display_name,size_bytes,page_count,status,storage_key,created_at_ms,updated_at_ms,expires_at_ms,source_kind,source_variant) VALUES(?,?,?,?,?,?,'fixture.pdf',100,?,'READY',?,?,?,?,?,?)",id,identity.tenantId(),identity.storeId(),identity.principalId(),key,"f".repeat(64),pages,id,now,now,now+3600_000L,kind,variant);return id;}
    private static void quote(MiniappDemoIdentity identity,String file,String profile){try(Connection connection=ds.getConnection()){insertQuote(connection,identity,file,profile);}catch(SQLException e){throw new org.springframework.dao.DataIntegrityViolationException("fixture quote",e);}}
    private static void insertQuote(Connection c,MiniappDemoIdentity identity,String file,String profile)throws SQLException{try(var st=c.prepareStatement("INSERT INTO print_miniapp_demo_quote(id,tenant_id,store_id,principal_id,file_id,price_profile_id,idempotency_sha256,input_sha256,snapshot_json,created_at_ms,expires_at_ms) VALUES(?,?,?,?,?,?,?,?,'{}',1000,2000)")){st.setString(1,UUID.randomUUID().toString());st.setLong(2,identity.tenantId());st.setLong(3,identity.storeId());st.setString(4,identity.principalId());st.setString(5,file);st.setString(6,profile);st.setString(7,hash(UUID.randomUUID().toString()));st.setString(8,"a".repeat(64));st.executeUpdate();}}
    private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private record Bytes(byte[] bytes)implements MultipartFile{public String getName(){return "file";}public String getOriginalFilename(){return "photo.jpg";}public String getContentType(){return "image/jpeg";}public boolean isEmpty(){return bytes.length==0;}public long getSize(){return bytes.length;}public byte[] getBytes(){return bytes.clone();}public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}public void transferTo(File f){throw new UnsupportedOperationException();}}
}
