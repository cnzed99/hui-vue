package cn.iocoder.yudao.server.print.miniappdemo.file;

import org.springframework.http.HttpStatus;

public class MiniappDemoFileException extends RuntimeException {
    public enum Reason {
        DISABLED(1_927_001, HttpStatus.NOT_FOUND, "Miniapp demo file upload is disabled"),
        INVALID_INPUT(1_927_002, HttpStatus.BAD_REQUEST, "Invalid miniapp demo file request"),
        TOO_LARGE(1_927_003, HttpStatus.PAYLOAD_TOO_LARGE, "Miniapp demo file exceeds the size limit"),
        QUEUE_FULL(1_927_004, HttpStatus.TOO_MANY_REQUESTS, "Miniapp demo file queue is full"),
        PRINCIPAL_LIMIT(1_927_005, HttpStatus.TOO_MANY_REQUESTS, "Miniapp demo file limit reached"),
        CAPACITY_LOW(1_927_006, HttpStatus.INSUFFICIENT_STORAGE, "Miniapp demo file storage capacity is low"),
        IDEMPOTENCY_CONFLICT(1_927_007, HttpStatus.CONFLICT, "Idempotency key was already used for another file"),
        NOT_FOUND(1_927_008, HttpStatus.NOT_FOUND, "Miniapp demo file was not found"),
        STORAGE_UNAVAILABLE(1_927_009, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo file storage is unavailable");

        private final int code;
        private final HttpStatus status;
        private final String message;
        Reason(int code, HttpStatus status, String message) { this.code = code; this.status = status; this.message = message; }
        public int code() { return code; }
        public HttpStatus status() { return status; }
        public String message() { return message; }
    }

    private final Reason reason;
    public MiniappDemoFileException(Reason reason) { super(reason.message()); this.reason = reason; }
    public int code() { return reason.code(); }
    public HttpStatus status() { return reason.status(); }
}
