package cn.iocoder.yudao.server.print.miniappdemo.photo;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoProcessAdmission;
import cn.iocoder.yudao.server.print.miniappdemo.file.*;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class MiniappDemoPhotoProcessRenderer {
    private final MiniappDemoFileService files;private final MiniappDemoFileProperties config;
    private final MiniappDemoProcessAdmission admission;private boolean stopping;private int active;
    public MiniappDemoPhotoProcessRenderer(MiniappDemoFileService files,MiniappDemoFileProperties config,MiniappDemoProcessAdmission admission){this.files=files;this.config=config;this.admission=admission;}
    @PreDestroy public synchronized void stop(){
        stopping=true;long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);boolean interrupted=false;
        while(active>0 && System.nanoTime()<end){try{wait(100);}catch(InterruptedException e){interrupted=true;break;}}
        if(active>0)files.retainStorageLockUntilProcessExit();if(interrupted)Thread.currentThread().interrupt();
    }
    protected Process startProcess(List<String> command)throws java.io.IOException{
        return new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }
    protected int timeoutSeconds(){return 30;}
    public byte[] render(List<Path> images,String variant,String digest){
        synchronized(this){if(stopping)throw MiniappDemoPhotoException.unavailable();files.requireStorageLockHeld();active++;}
        Path task=null;Process process=null;MiniappDemoProcessAdmission.Lease lease=null;
        try{
            lease=admission.tryAcquire();
            if(lease==null)throw new MiniappDemoPhotoException(429,43204,"Photo renderer busy");
            Path root=files.idPhotoScratchRoot();
            if(Files.getFileStore(root).getUsableSpace()<config.getMinFreeBytes())throw MiniappDemoPhotoException.unavailable();
            task=Files.createTempDirectory(root,"task-");Files.setPosixFilePermissions(task,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            Path output=task.resolve("output.pdf");
            var command=new ArrayList<String>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Xms16m","-Xmx256m","-XX:MaxMetaspaceSize=96m","-XX:+UseSerialGC","-Djava.awt.headless=true",
                "-Djava.io.tmpdir="+task,"-Dloader.main="+MiniappDemoPhotoRendererMain.class.getName(),"-cp",config.getRuntimeJar().toRealPath().toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher",output.toString(),variant,digest,Integer.toString(images.size())));
            images.forEach(p->command.add(p.toString()));
            process=startProcess(command);
            if(!process.waitFor(timeoutSeconds(),TimeUnit.SECONDS))throw MiniappDemoPhotoException.unavailable();
            if(process.exitValue()!=0)throw new MiniappDemoPhotoException(422,43206,"Photo decode or PDF limit rejected");
            if(Files.isSymbolicLink(output)||!Files.isRegularFile(output,LinkOption.NOFOLLOW_LINKS)||Files.size(output)>2L*1024*1024)throw MiniappDemoPhotoException.unavailable();
            byte[] pdf=Files.readAllBytes(output);
            if(pdf.length<8 || !new String(pdf,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))throw MiniappDemoPhotoException.unavailable();
            return pdf;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw MiniappDemoPhotoException.unavailable();
        }catch(java.io.IOException e){throw MiniappDemoPhotoException.unavailable();
        }finally{
            boolean interrupted=Thread.interrupted();
            if(process!=null && process.isAlive()){process.destroyForcibly();while(process.isAlive())try{process.waitFor();}catch(InterruptedException e){interrupted=true;}}
            try{
                if(task!=null && !files.cleanupIdPhotoScratchTask(task))files.retainStorageLockUntilProcessExit();
            }finally{
                // Keep the shared process permit through confirmed child exit AND scratch ownership handling.
                if(lease!=null)lease.close();
                synchronized(this){active--;notifyAll();}if(interrupted)Thread.currentThread().interrupt();
            }
        }
    }
}
