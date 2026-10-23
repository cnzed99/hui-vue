package cn.iocoder.yudao.server.print.order;
import org.springframework.http.HttpStatus;
public final class PrintOrderException extends RuntimeException {
    public enum Reason {
        DISABLED(1_510_030_000,HttpStatus.SERVICE_UNAVAILABLE,"Print order service is disabled"),
        INVALID_INPUT(1_510_030_001,HttpStatus.BAD_REQUEST,"Invalid order request or pagination"),
        QUOTE_NOT_FOUND(1_510_030_002,HttpStatus.NOT_FOUND,"Quote is not available"),
        QUOTE_EXPIRED(1_510_030_003,HttpStatus.CONFLICT,"Quote has expired"),
        QUOTE_CONSUMED(1_510_030_004,HttpStatus.CONFLICT,"Quote has already been used by an order"),
        FILE_NOT_FOUND(1_510_030_005,HttpStatus.NOT_FOUND,"File is not available"),
        FILE_NOT_READY(1_510_030_006,HttpStatus.CONFLICT,"File is not ready or has expired"),
        QUOTE_FILE_MISMATCH(1_510_030_007,HttpStatus.CONFLICT,"Quote does not match the current file"),
        IDEMPOTENCY_CONFLICT(1_510_030_008,HttpStatus.CONFLICT,"Idempotency key has different order input"),
        ORDER_NOT_FOUND(1_510_030_009,HttpStatus.NOT_FOUND,"Order is not available"),
        STORAGE_UNAVAILABLE(1_510_030_010,HttpStatus.SERVICE_UNAVAILABLE,"Order storage is unavailable"),
        SCOPE_CHANGED(1_510_030_011,HttpStatus.FORBIDDEN,"Current store assignment is no longer available"),
        SNAPSHOT_INVALID(1_510_030_012,HttpStatus.CONFLICT,"Quote snapshot is not supported");
        final int code;final HttpStatus status;final String message;
        Reason(int code,HttpStatus status,String message){this.code=code;this.status=status;this.message=message;}
    }
    private final Reason reason;
    public PrintOrderException(Reason reason){super(reason.message);this.reason=reason;}
    public int code(){return reason.code;}
    public HttpStatus status(){return reason.status;}
}
