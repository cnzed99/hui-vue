package cn.iocoder.yudao.server.print.miniappdemo;

import org.springframework.http.HttpStatus;

public class MiniappDemoSessionException extends RuntimeException {
    public enum Reason {
        DISABLED(1_926_001, HttpStatus.NOT_FOUND, "Miniapp demo session is disabled"),
        INVALID_INPUT(1_926_002, HttpStatus.BAD_REQUEST, "Invalid miniapp demo session request"),
        SESSION_INVALID(1_926_003, HttpStatus.UNAUTHORIZED, "Miniapp demo session is invalid or expired"),
        RATE_LIMITED(1_926_004, HttpStatus.TOO_MANY_REQUESTS, "Miniapp demo session limit reached"),
        STORE_UNAVAILABLE(1_926_005, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo store is unavailable"),
        CONFIGURATION_INVALID(1_926_006, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo session configuration is invalid"),
        STORAGE_UNAVAILABLE(1_926_007, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo session storage is unavailable"),
        ISSUANCE_FORBIDDEN(1_926_008, HttpStatus.FORBIDDEN, "Miniapp demo session issuance is forbidden");

        private final int code;
        private final HttpStatus status;
        private final String message;
        Reason(int code, HttpStatus status, String message) { this.code = code; this.status = status; this.message = message; }
        public int code() { return code; }
        public HttpStatus status() { return status; }
        public String message() { return message; }
    }

    private final Reason reason;
    public MiniappDemoSessionException(Reason reason) { super(reason.message()); this.reason = reason; }
    public int code() { return reason.code(); }
    public HttpStatus status() { return reason.status(); }
}
