package cn.iocoder.yudao.server.print.advertising;
import org.springframework.http.HttpStatus;
public final class PrintAdvertisingException extends RuntimeException {
    public enum Reason {
        INVALID(1510100001,HttpStatus.BAD_REQUEST,"Invalid advertising draft"),
        CONFLICT(1510100002,HttpStatus.CONFLICT,"Draft changed; reload before saving"),
        UNAVAILABLE(1510100003,HttpStatus.NOT_FOUND,"Current store unavailable"),
        STORAGE(1510100004,HttpStatus.SERVICE_UNAVAILABLE,"Advertising storage unavailable"),
        PENDING(1510100005,HttpStatus.CONFLICT,"Advertising delivery and deletion rules await confirmation"),
        ASSET(1510100006,HttpStatus.BAD_REQUEST,"Use an existing image URL uploaded by the current account");
        public final int code; public final HttpStatus status; final String message;
        Reason(int code,HttpStatus status,String message){this.code=code;this.status=status;this.message=message;}
    }
    public final Reason reason;
    public PrintAdvertisingException(Reason reason){super(reason.message);this.reason=reason;}
}
