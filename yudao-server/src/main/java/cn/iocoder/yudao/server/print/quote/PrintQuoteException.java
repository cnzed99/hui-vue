package cn.iocoder.yudao.server.print.quote;
import org.springframework.http.HttpStatus;
/** Stable input-independent errors; SQL, request keys and file paths are never exposed. */
public final class PrintQuoteException extends RuntimeException {
    public enum Reason {
        DISABLED(1_510_020_000, HttpStatus.SERVICE_UNAVAILABLE, "Print quote service is disabled"),
        INVALID_INPUT(1_510_020_001, HttpStatus.BAD_REQUEST, "Invalid quote request or idempotency key"),
        PROFILE_NOT_FOUND(1_510_020_002, HttpStatus.NOT_FOUND, "Price profile is not available"),
        PROFILE_UNSUPPORTED(1_510_020_003, HttpStatus.CONFLICT, "Price profile requires unsupported rules"),
        FILE_NOT_FOUND(1_510_020_004, HttpStatus.NOT_FOUND, "File is not available"),
        FILE_NOT_READY(1_510_020_005, HttpStatus.CONFLICT, "File is not ready or has expired"),
        IDEMPOTENCY_CONFLICT(1_510_020_006, HttpStatus.CONFLICT, "Idempotency key has different quote input"),
        QUOTE_EXPIRED(1_510_020_007, HttpStatus.CONFLICT, "Quote has expired; a new idempotency key is required"),
        QUOTE_NOT_FOUND(1_510_020_008, HttpStatus.NOT_FOUND, "Quote is not available"),
        STORAGE_UNAVAILABLE(1_510_020_009, HttpStatus.SERVICE_UNAVAILABLE, "Quote storage is unavailable"),
        CONFIGURATION_INVALID(1_510_020_010, HttpStatus.SERVICE_UNAVAILABLE, "Explicit quote lifetime configuration is required"),
        SCOPE_CHANGED(1_510_020_011, HttpStatus.FORBIDDEN, "Current store assignment is no longer available");
        final int code; final HttpStatus status; final String message;
        Reason(int code, HttpStatus status, String message) { this.code=code;this.status=status;this.message=message; }
    }
    private final Reason reason;
    public PrintQuoteException(Reason reason) { super(reason.message);this.reason=reason; }
    public int code() { return reason.code; }
    public HttpStatus status() { return reason.status; }
}
