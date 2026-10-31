package cn.iocoder.yudao.server.print.miniappdemo.order;

import org.springframework.http.HttpStatus;

public class MiniappDemoOrderException extends RuntimeException {
    public enum Reason {
        DISABLED(1_929_001, HttpStatus.NOT_FOUND, "Miniapp demo orders are disabled"),
        INVALID_INPUT(1_929_002, HttpStatus.BAD_REQUEST, "Invalid miniapp demo order request"),
        IDEMPOTENCY_CONFLICT(1_929_003, HttpStatus.CONFLICT, "Idempotency key was already used for another order"),
        QUOTE_NOT_FOUND(1_929_004, HttpStatus.NOT_FOUND, "Miniapp demo quote was not found"),
        QUOTE_EXPIRED(1_929_005, HttpStatus.CONFLICT, "Miniapp demo quote has expired"),
        QUOTE_CONSUMED(1_929_006, HttpStatus.CONFLICT, "Miniapp demo quote was already used"),
        FILE_NOT_FOUND(1_929_007, HttpStatus.NOT_FOUND, "Miniapp demo file was not found"),
        FILE_NOT_READY(1_929_008, HttpStatus.CONFLICT, "Miniapp demo file is not ready"),
        SNAPSHOT_INVALID(1_929_009, HttpStatus.CONFLICT, "Miniapp demo quote snapshot is invalid"),
        ORDER_NOT_FOUND(1_929_010, HttpStatus.NOT_FOUND, "Miniapp demo order was not found"),
        STORAGE_UNAVAILABLE(1_929_011, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo order storage is unavailable"),
        PRINCIPAL_LIMIT(1_929_012, HttpStatus.TOO_MANY_REQUESTS, "Miniapp demo order limit reached");

        private final int code;
        private final HttpStatus status;
        private final String message;
        Reason(int code, HttpStatus status, String message) { this.code = code; this.status = status; this.message = message; }
        public int code() { return code; }
        public HttpStatus status() { return status; }
        public String message() { return message; }
    }

    private final Reason reason;
    public MiniappDemoOrderException(Reason reason) { super(reason.message()); this.reason = reason; }
    public int code() { return reason.code(); }
    public HttpStatus status() { return reason.status(); }
}
