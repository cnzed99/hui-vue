package cn.iocoder.yudao.server.print.miniappdemo.order;

import org.springframework.http.HttpStatus;

public class MiniappDemoPaymentException extends RuntimeException {
    public enum Reason {
        DISABLED(1_930_001, HttpStatus.NOT_FOUND, "Miniapp demo payments are disabled"),
        INVALID_INPUT(1_930_002, HttpStatus.BAD_REQUEST, "Invalid miniapp demo payment request"),
        IDEMPOTENCY_CONFLICT(1_930_003, HttpStatus.CONFLICT, "Idempotency key was already used for another payment"),
        ORDER_NOT_FOUND(1_930_004, HttpStatus.NOT_FOUND, "Miniapp demo order was not found"),
        INVALID_ORDER_STATE(1_930_005, HttpStatus.CONFLICT, "Miniapp demo order cannot be paid in its current state"),
        ALREADY_PAID(1_930_006, HttpStatus.CONFLICT, "Miniapp demo order was already paid"),
        FILE_NOT_FOUND(1_930_007, HttpStatus.NOT_FOUND, "Miniapp demo file was not found"),
        FILE_NOT_READY(1_930_008, HttpStatus.CONFLICT, "Miniapp demo file is not ready"),
        SNAPSHOT_INVALID(1_930_009, HttpStatus.CONFLICT, "Miniapp demo quote snapshot is invalid"),
        RECEIPT_NOT_FOUND(1_930_010, HttpStatus.NOT_FOUND, "Miniapp demo payment receipt was not found"),
        STORAGE_UNAVAILABLE(1_930_011, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo payment storage is unavailable");

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
    public MiniappDemoPaymentException(Reason reason) { super(reason.message()); this.reason = reason; }
    public int code() { return reason.code(); }
    public HttpStatus status() { return reason.status(); }
}
