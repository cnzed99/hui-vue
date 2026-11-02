package cn.iocoder.yudao.server.print.miniappdemo.imagecollage;
import org.springframework.http.HttpStatus;
public final class MiniappDemoCollageUploadException extends RuntimeException {
 public enum Reason {
 DISABLED(1935001,404,"Staged collage upload is disabled"), INVALID_INPUT(1935002,400,"Invalid staged collage upload request"),
 NOT_FOUND(1935003,404,"Staged collage upload was not found"), CONFLICT(1935004,409,"Staged collage upload content conflicts"),
 INCOMPLETE(1935005,409,"Staged collage upload is incomplete"), BUSY(1935006,429,"Staged collage upload is busy"),
 STORAGE_UNAVAILABLE(1935007,503,"Staged collage upload storage is unavailable"), TOO_LARGE(1935008,413,"Staged collage upload exceeds the size limit");
 final int code,status; final String message;
 Reason(int code,int status,String message){this.code=code;this.status=status;this.message=message;}
 }
 private final Reason reason;
 public MiniappDemoCollageUploadException(Reason reason){super(reason.message);this.reason=reason;}
 public int code(){return reason.code;} public HttpStatus status(){return HttpStatus.valueOf(reason.status);}
 public Reason reason(){return reason;}
}
