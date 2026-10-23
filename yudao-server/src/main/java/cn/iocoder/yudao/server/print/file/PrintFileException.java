package cn.iocoder.yudao.server.print.file;

import org.springframework.http.HttpStatus;

/** Only stable, input-independent messages are exposed or logged. */
public final class PrintFileException extends RuntimeException {
    public enum Reason {
        DISABLED(1_510_010_000, HttpStatus.SERVICE_UNAVAILABLE, "Print file service is disabled"),
        INVALID_INPUT(1_510_010_001, HttpStatus.BAD_REQUEST, "Invalid file or idempotency key"),
        TOO_LARGE(1_510_010_002, HttpStatus.PAYLOAD_TOO_LARGE, "File exceeds configured byte limit"),
        QUEUE_FULL(1_510_010_003, HttpStatus.TOO_MANY_REQUESTS, "PDF processing queue is full"),
        NOT_FOUND(1_510_010_004, HttpStatus.NOT_FOUND, "File is not available"),
        IDEMPOTENCY_CONFLICT(1_510_010_005, HttpStatus.CONFLICT, "Idempotency key has different content"),
        NOT_READY(1_510_010_006, HttpStatus.CONFLICT, "File is not ready for download"),
        STORAGE_UNAVAILABLE(1_510_010_007, HttpStatus.SERVICE_UNAVAILABLE, "Private file storage is unavailable"),
        CAPACITY_LOW(1_510_010_008, HttpStatus.INSUFFICIENT_STORAGE, "Storage reserve would be exceeded");
        final int code;
        final HttpStatus status;
        final String safeMessage;
        Reason(int code, HttpStatus status, String safeMessage) {
            this.code = code; this.status = status; this.safeMessage = safeMessage;
        }
    }
    private final Reason reason;
    public PrintFileException(Reason reason) { super(reason.safeMessage); this.reason = reason; }
    public int code() { return reason.code; }
    public HttpStatus status() { return reason.status; }
}
