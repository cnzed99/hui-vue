package cn.iocoder.yudao.server.print.miniappdemo.file;
import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoProcessAdmission;
import java.util.*;
/** Persistence is stubbed; the OS lock uses unmodified production start/stop/require/retain methods. */
public final class CollageRealLockFixture {
 public static MiniappDemoFileService files(MiniappDemoFileProperties p){
  var repo=new MiniappDemoFileRepository(null){
   @Override void recoverInterrupted(long now){}
   @Override List<MiniappDemoFileRow> cleanupCandidates(long now,int limit){return List.of();}
   @Override Optional<MiniappDemoFileRow> claimNext(long now){return Optional.empty();}
   @Override boolean storageReferenced(String key){return false;}
  };
  return new MiniappDemoFileService(p,repo,null,null,new MiniappDemoProcessAdmission(),null);
 }
}
