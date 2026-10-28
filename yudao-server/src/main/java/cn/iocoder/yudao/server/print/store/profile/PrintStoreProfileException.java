package cn.iocoder.yudao.server.print.store.profile;

import org.springframework.http.HttpStatus;

public final class PrintStoreProfileException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT(1_510_050_001, HttpStatus.BAD_REQUEST, "Invalid full store profile request"),
        VERSION_CONFLICT(1_510_050_002, HttpStatus.CONFLICT, "Store profile changed; reload before saving"),
        NOT_AVAILABLE(1_510_050_003, HttpStatus.NOT_FOUND, "Current store is unavailable"),
        STORAGE_UNAVAILABLE(1_510_050_004, HttpStatus.SERVICE_UNAVAILABLE, "Store profile storage unavailable"),
        REGION_NOT_CONFIGURED(1_510_050_005, HttpStatus.BAD_REQUEST, "Region dictionary is not configured");
        final int code; final HttpStatus status; final String message;
        Reason(int code, HttpStatus status, String message) { this.code=code; this.status=status; this.message=message; }
    }
    private final Reason reason;
    public PrintStoreProfileException(Reason reason) { super(reason.message); this.reason=reason; }
    public Reason reason() { return reason; }
    public int code() { return reason.code; }
    public HttpStatus status() { return reason.status; }
}
