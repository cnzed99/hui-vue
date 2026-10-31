package cn.iocoder.yudao.server.print.miniappdemo.printtask;

import org.springframework.http.HttpStatus;

public class MiniappDemoPrintTaskException extends RuntimeException {
    public enum Reason {
        DISABLED(1_931_001, HttpStatus.NOT_FOUND, "Miniapp demo print processing is disabled"),
        INVALID_INPUT(1_931_002, HttpStatus.BAD_REQUEST, "Invalid miniapp demo print request"),
        IDEMPOTENCY_CONFLICT(1_931_003, HttpStatus.CONFLICT, "Idempotency key was already used for another print action"),
        ORDER_NOT_FOUND(1_931_004, HttpStatus.NOT_FOUND, "Miniapp demo order was not found"),
        ORDER_NOT_PAYABLE(1_931_005, HttpStatus.CONFLICT, "Miniapp demo order is not ready for print processing"),
        TASK_NOT_FOUND(1_931_006, HttpStatus.NOT_FOUND, "Miniapp demo print task was not found"),
        INVALID_STATE(1_931_007, HttpStatus.CONFLICT, "Miniapp demo print task cannot perform this action"),
        STALE_VERSION(1_931_008, HttpStatus.CONFLICT, "Miniapp demo print task version is stale"),
        STALE_LEASE(1_931_009, HttpStatus.CONFLICT, "Miniapp demo print lease is stale or expired"),
        FILE_UNAVAILABLE(1_931_010, HttpStatus.CONFLICT, "Miniapp demo source file is unavailable"),
        SNAPSHOT_INVALID(1_931_011, HttpStatus.CONFLICT, "Miniapp demo print snapshot is invalid"),
        STORAGE_UNAVAILABLE(1_931_012, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo print storage is unavailable");

        private final int code;
        private final HttpStatus status;
        private final String message;
        Reason(int code, HttpStatus status, String message) {
            this.code = code; this.status = status; this.message = message;
        }
        public int code() { return code; }
        public HttpStatus status() { return status; }
        public String message() { return message; }
    }

    private final Reason reason;
    public MiniappDemoPrintTaskException(Reason reason) { super(reason.message()); this.reason = reason; }
    public int code() { return reason.code(); }
    public HttpStatus status() { return reason.status(); }
}
