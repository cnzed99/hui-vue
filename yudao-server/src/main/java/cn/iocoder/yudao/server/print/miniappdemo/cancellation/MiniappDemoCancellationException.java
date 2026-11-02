package cn.iocoder.yudao.server.print.miniappdemo.cancellation;

import org.springframework.http.HttpStatus;

public class MiniappDemoCancellationException extends RuntimeException {
    public enum Reason {
        DISABLED(1934001, HttpStatus.NOT_FOUND, "Miniapp demo cancellation writes are disabled"),
        INVALID_INPUT(1934002, HttpStatus.BAD_REQUEST, "Invalid cancellation request"),
        ORDER_NOT_FOUND(1934003, HttpStatus.NOT_FOUND, "Order not found"),
        STALE_VERSION(1934004, HttpStatus.CONFLICT, "Order changed; refresh and confirm again"),
        IDEMPOTENCY_CONFLICT(1934005, HttpStatus.CONFLICT, "Cancellation idempotency conflict"),
        CANCELLATION_ALREADY_RECORDED(1934006, HttpStatus.CONFLICT, "Cancellation already recorded; restore current result"),
        STORAGE_UNAVAILABLE(1934007, HttpStatus.SERVICE_UNAVAILABLE, "Cancellation storage is unavailable");
        private final int code;
        private final HttpStatus status;
        private final String message;
        Reason(int code, HttpStatus status, String message) { this.code = code; this.status = status; this.message = message; }
    }
    private final Reason reason;
    private final AlreadyRecorded data;
    public record AlreadyRecorded(String cancellationId, String outcome) {}
    public MiniappDemoCancellationException(Reason reason) { this(reason, null); }
    private MiniappDemoCancellationException(Reason reason, AlreadyRecorded data) { super(reason.message); this.reason = reason; this.data=data; }
    public static MiniappDemoCancellationException alreadyRecorded(String id, String outcome) {
        return new MiniappDemoCancellationException(Reason.CANCELLATION_ALREADY_RECORDED, new AlreadyRecorded(id, outcome));
    }
    public AlreadyRecorded data() { return data; }
    public int code() { return reason.code; }
    public HttpStatus status() { return reason.status; }
}
