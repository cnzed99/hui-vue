package cn.iocoder.yudao.server.print.payment;
import org.springframework.http.HttpStatus;
public final class PrintPaymentException extends RuntimeException {
    public enum Reason {
        DISABLED(1_510_040_000,HttpStatus.SERVICE_UNAVAILABLE,"Simulated payment is disabled"),
        INVALID_INPUT(1_510_040_001,HttpStatus.BAD_REQUEST,"Invalid simulated payment request"),
        ENVIRONMENT_FORBIDDEN(1_510_040_002,HttpStatus.FORBIDDEN,"Explicit DEMO payment mode is required"),
        ORDER_NOT_FOUND(1_510_040_003,HttpStatus.NOT_FOUND,"Order is not available"),
        IDEMPOTENCY_CONFLICT(1_510_040_004,HttpStatus.CONFLICT,"Idempotency key has different payment input"),
        ALREADY_PAID(1_510_040_005,HttpStatus.CONFLICT,"Order has already been paid"),
        ORDER_STATE_INVALID(1_510_040_006,HttpStatus.CONFLICT,"Order state does not allow simulated payment"),
        FILE_NOT_FOUND(1_510_040_007,HttpStatus.NOT_FOUND,"Order file is not available"),
        FILE_NOT_READY(1_510_040_008,HttpStatus.CONFLICT,"Order file is not ready or has expired"),
        SNAPSHOT_MISMATCH(1_510_040_009,HttpStatus.CONFLICT,"Order and file snapshots do not match"),
        STORAGE_UNAVAILABLE(1_510_040_010,HttpStatus.SERVICE_UNAVAILABLE,"Payment storage is unavailable"),
        SCOPE_CHANGED(1_510_040_011,HttpStatus.FORBIDDEN,"Current store assignment is no longer available"),
        SOURCE_FORBIDDEN(1_510_040_012,HttpStatus.FORBIDDEN,"Order source does not allow simulated payment"),
        RECEIPT_NOT_FOUND(1_510_040_013,HttpStatus.NOT_FOUND,"Order has no payment receipt"),
        INTEGRITY_FAILURE(1_510_040_014,HttpStatus.SERVICE_UNAVAILABLE,"Saved payment records are inconsistent");
        final int code;final HttpStatus status;final String message;
        Reason(int code,HttpStatus status,String message){this.code=code;this.status=status;this.message=message;}
    }
    private final Reason reason;
    public PrintPaymentException(Reason reason){super(reason.message);this.reason=reason;}
    public int code(){return reason.code;}
    public HttpStatus status(){return reason.status;}
}
