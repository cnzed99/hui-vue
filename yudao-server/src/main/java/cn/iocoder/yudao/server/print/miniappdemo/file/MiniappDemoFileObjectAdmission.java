package cn.iocoder.yudao.server.print.miniappdemo.file;
import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
@Component
public final class MiniappDemoFileObjectAdmission {
    private final MiniappDemoFileProperties properties;
    public MiniappDemoFileObjectAdmission(MiniappDemoFileProperties properties){this.properties=properties;}
    /** No feature-switch dependency: existing compatible orders still use their frozen source evidence. */
    public boolean available(String key,String digest){
        if(key==null||!key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")||digest==null||properties.getStorageRoot()==null)return false;
        try{
            Path root=properties.getStorageRoot().toRealPath().resolve("objects");Path object=root.resolve(key+".pdf");
            if(Files.isSymbolicLink(root)||Files.isSymbolicLink(object)||!Files.isRegularFile(object,LinkOption.NOFOLLOW_LINKS)||Files.size(object)<1||Files.size(object)>2L*1024*1024)return false;
            MessageDigest sha=MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(object,LinkOption.NOFOLLOW_LINKS)){byte[] b=new byte[16384];int n;while((n=input.read(b))!=-1)sha.update(b,0,n);}
            return HexFormat.of().formatHex(sha.digest()).equals(digest);
        }catch(Exception e){return false;}
    }
}
