package cn.iocoder.yudao.server.print.library.settings;

import org.springframework.http.HttpStatus;

public final class PrintLibrarySettingsException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT(1510060001, HttpStatus.BAD_REQUEST, "Invalid library settings"),
        VERSION_CONFLICT(1510060002, HttpStatus.CONFLICT, "Library settings changed; reload before saving"),
        NOT_AVAILABLE(1510060003, HttpStatus.NOT_FOUND, "Current store is unavailable"),
        STORAGE_UNAVAILABLE(1510060004, HttpStatus.SERVICE_UNAVAILABLE, "Library settings storage unavailable");
        public final int code; public final HttpStatus status; final String message;
        Reason(int code,HttpStatus status,String message){this.code=code;this.status=status;this.message=message;}
    }
    public final Reason reason;
    public PrintLibrarySettingsException(Reason reason){super(reason.message);this.reason=reason;}
}
