package cn.iocoder.yudao.server.print.miniappdemo.imagecollage;
import cn.iocoder.yudao.server.print.miniappdemo.file.*;
import java.nio.file.*;
import java.util.concurrent.*;
/** Independent JVM lock proof. Database and renderer are not exercised. */
public final class MiniappDemoCollageRealLockChecks {
 static MiniappDemoFileProperties props(Path root){
  var p=new MiniappDemoFileProperties();p.setEnabled(true);p.setStorageRoot(root);p.setRuntimeJar(root.resolve("unused.jar"));
  p.setMaxBytes(2097152);p.setMaxPages(20);p.setRetentionSeconds(3600);return p;
 }
 static MiniappDemoCollageUploadService stage(MiniappDemoFileService f,MiniappDemoFileProperties fp){
  var p=new MiniappDemoCollageUploadProperties();p.setEnabled(true);p.setRoot(fp.getStorageRoot().resolve("image-collage-upload-scratch"));
  var cp=new MiniappDemoImageCollageProperties();cp.setEnabled(true);
  return new MiniappDemoCollageUploadService(p,f,(a,b,c)->{throw new AssertionError("No renderer");},fp,cp);
 }
 static void probe(Path root)throws Exception{
  var fp=props(root);var f=CollageRealLockFixture.files(fp);
  try{f.start();throw new AssertionError("Second JVM acquired lock");}catch(IllegalStateException expected){System.out.println("PASS second_jvm_production_os_lock_denied");}
  var s=stage(f,fp);
  try{s.initialize();throw new AssertionError("Stage initialized without lock");}catch(IllegalStateException expected){System.out.println("PASS second_stage_start_refused");}
  var actor=MiniappDemoCollageUploadChecks.identity("probe");
  MiniappDemoCollageUploadChecks.rejected("second_instance_part_refused",()->s.upload(actor,"12345678-1234-1234-1234-123456789abc",0,new MiniappDemoCollageUploadChecks.Part(new byte[]{1})),1935007);
  MiniappDemoCollageUploadChecks.rejected("second_instance_complete_refused",()->s.complete(actor,"12345678-1234-1234-1234-123456789abc"),1935007);
  s.stop();f.stop();
 }
 static void child(Path root,String name)throws Exception{
  var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx96m","-cp",System.getProperty("java.class.path"),
   MiniappDemoCollageRealLockChecks.class.getName(),"probe",root.toString()).inheritIO().start();
  if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Child timeout");}
  if(process.exitValue()!=0)throw new AssertionError("Child failed");
  System.out.println("PASS "+name);
 }
 public static void main(String[] args)throws Exception{
  Path root=Path.of(args[1]).toAbsolutePath().normalize();if(args[0].equals("probe")){probe(root);return;}
  Files.createDirectory(root,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(MiniappDemoCollageUploadService.DIR));
  var fp=props(root);var f=CollageRealLockFixture.files(fp);f.start();f.requireStorageLockHeld();var s=stage(f,fp);s.initialize();
  Path marker=root.resolve("image-collage-upload-scratch/12345678-1234-1234-1234-123456789abc");Files.createDirectory(marker);Files.writeString(marker.resolve("owner.marker"),"active");
  child(root,"live_owner_blocks_second_jvm");MiniappDemoCollageUploadChecks.check("second_instance_did_not_clean_owner",Files.exists(marker.resolve("owner.marker")));
  var actor=MiniappDemoCollageUploadChecks.identity("owner");var session=s.create(actor,MiniappDemoCollageUploadChecks.manifest(new byte[]{1}),"key");
  var reading=new CountDownLatch(1);var release=new CountDownLatch(1);
  var part=new MiniappDemoCollageUploadChecks.Part(new byte[]{1}){
   @Override public java.io.InputStream getInputStream(){return new java.io.ByteArrayInputStream(bytes){
    @Override public synchronized int read(byte[] b,int off,int len){reading.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();return -1;}return super.read(b,off,len);}
   };}
  };
  var executor=Executors.newSingleThreadExecutor();var upload=executor.submit(()->s.upload(actor,session.uploadId(),0,part));
  if(!reading.await(3,TimeUnit.SECONDS))throw new AssertionError("Part never entered");
  Thread shutdown=new Thread(s::stop);shutdown.start();shutdown.interrupt();shutdown.join(3000);if(shutdown.isAlive())throw new AssertionError("Stop stuck");
  f.stop();f.requireStorageLockHeld();System.out.println("PASS production_file_service_retained_lock_after_undrained_stage");
  child(root,"retained_lock_blocks_second_jvm");release.countDown();upload.get(3,TimeUnit.SECONDS);executor.shutdown();
  System.out.println("REAL_OS_LOCK_CHECKS_PASS");
  // The real retained lock remains until JVM exit; the runner removes fixture afterward.
 }
}
