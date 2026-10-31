package cn.iocoder.yudao.server.print.miniappdemo.quote;

import org.springframework.http.HttpStatus;

public class MiniappDemoQuoteException extends RuntimeException {
    public enum Reason {
        DISABLED(1_928_001, HttpStatus.NOT_FOUND, "Miniapp demo quotes are disabled"),
        INVALID_INPUT(1_928_002, HttpStatus.BAD_REQUEST, "Invalid miniapp demo quote request"),
        IDEMPOTENCY_CONFLICT(1_928_003, HttpStatus.CONFLICT, "Idempotency key was already used for another quote"),
        PRINCIPAL_LIMIT(1_928_004, HttpStatus.TOO_MANY_REQUESTS, "Miniapp demo quote limit reached"),
        FILE_NOT_FOUND(1_928_005, HttpStatus.NOT_FOUND, "Miniapp demo file was not found"),
        FILE_NOT_READY(1_928_006, HttpStatus.CONFLICT, "Miniapp demo file is not ready"),
        PROFILE_UNAVAILABLE(1_928_007, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo price profile is unavailable"),
        QUOTE_NOT_FOUND(1_928_008, HttpStatus.NOT_FOUND, "Miniapp demo quote was not found"),
        QUOTE_EXPIRED(1_928_009, HttpStatus.GONE, "Miniapp demo quote has expired"),
        CONFIGURATION_INVALID(1_928_010, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo quote configuration is invalid"),
        STORAGE_UNAVAILABLE(1_928_011, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo quote storage is unavailable");

        private final int code;
        private final HttpStatus status;
        private final String message;
        Reason(int code, HttpStatus status, String message) { this.code = code; this.status = status; this.message = message; }
        public int code() { return code; }
        public HttpStatus status() { return status; }
        public String message() { return message; }
    }

    private final Reason reason;
    public MiniappDemoQuoteException(Reason reason) { super(reason.message()); this.reason = reason; }
    public int code() { return reason.code(); }
    public HttpStatus status() { return reason.status(); }
}
