package cn.iocoder.yudao.server.print.miniappdemo.imagecollage;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.nio.file.Path;
@Component
@ConfigurationProperties(prefix="print.miniapp-demo.image-collage-staged-upload")
public class MiniappDemoCollageUploadProperties {
 private boolean enabled; private Path root; private int ttlSeconds=900,maxActiveSessions=4,maxActiveSessionsPerPrincipal=1;
 public void validateEnabled(){if(enabled&&(root==null||!root.isAbsolute()||ttlSeconds!=900||maxActiveSessions!=4||maxActiveSessionsPerPrincipal!=1))
 throw new IllegalStateException("Staged collage upload requires explicit bounded configuration");}
 public boolean isEnabled(){return enabled;} public void setEnabled(boolean value){enabled=value;}
 public Path getRoot(){return root;} public void setRoot(Path value){root=value;}
 public int getTtlSeconds(){return ttlSeconds;} public void setTtlSeconds(int value){ttlSeconds=value;}
 public int getMaxActiveSessions(){return maxActiveSessions;} public void setMaxActiveSessions(int value){maxActiveSessions=value;}
 public int getMaxActiveSessionsPerPrincipal(){return maxActiveSessionsPerPrincipal;}
 public void setMaxActiveSessionsPerPrincipal(int value){maxActiveSessionsPerPrincipal=value;}
}
