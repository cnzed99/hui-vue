package cn.iocoder.yudao.server.print.email;

import org.springframework.http.HttpStatus;

public final class PrintEmailSettingsException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT(1510200001, HttpStatus.BAD_REQUEST, "Invalid email settings"),
        VERSION_CONFLICT(1510200002, HttpStatus.CONFLICT, "Email settings changed; reload before saving"),
        NOT_AVAILABLE(1510200003, HttpStatus.NOT_FOUND, "Current store is unavailable"),
        STORAGE_UNAVAILABLE(1510200004, HttpStatus.SERVICE_UNAVAILABLE, "Email settings storage unavailable"),
        WRITE_DISABLED(1510200005, HttpStatus.SERVICE_UNAVAILABLE, "Email settings writes are not activated"),
        SECRET_UNAVAILABLE(1510200006, HttpStatus.SERVICE_UNAVAILABLE, "Email settings secret protection is unavailable");
        public final int code; public final HttpStatus status; final String message;
        Reason(int code, HttpStatus status, String message) { this.code = code; this.status = status; this.message = message; }
    }
    public final Reason reason;
    public PrintEmailSettingsException(Reason reason) { super(reason.message); this.reason = reason; }
}
