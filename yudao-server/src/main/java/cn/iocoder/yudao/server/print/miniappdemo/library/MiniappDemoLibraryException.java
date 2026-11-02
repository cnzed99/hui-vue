package cn.iocoder.yudao.server.print.miniappdemo.library;

import org.springframework.http.HttpStatus;

/** Fixed failures without request values or underlying exceptions. */
public final class MiniappDemoLibraryException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT(1_933_001, HttpStatus.BAD_REQUEST, "Invalid miniapp demo library request"),
        NOT_FOUND(1_933_002, HttpStatus.NOT_FOUND, "Miniapp demo library entry was not found"),
        TOO_LARGE(1_933_003, HttpStatus.PAYLOAD_TOO_LARGE, "Miniapp demo library request exceeds the size limit");
        private final int code; private final HttpStatus status; private final String message;
        Reason(int code, HttpStatus status, String message) { this.code=code; this.status=status; this.message=message; }
    }
    private final Reason reason;
    public MiniappDemoLibraryException(Reason reason) { super(reason.message); this.reason=reason; }
    public int code() { return reason.code; }
    public HttpStatus status() { return reason.status; }
}
