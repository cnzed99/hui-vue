package cn.iocoder.yudao.server.print.library.category;

import org.springframework.http.HttpStatus;

public final class PrintLibraryCategoryException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT(1510080001, HttpStatus.BAD_REQUEST, "Invalid category input"),
        VERSION_CONFLICT(1510080002, HttpStatus.CONFLICT, "Category changed; reload before saving"),
        NOT_FOUND(1510080003, HttpStatus.NOT_FOUND, "Category unavailable"),
        STORAGE_UNAVAILABLE(1510080004, HttpStatus.SERVICE_UNAVAILABLE, "Category storage unavailable"),
        RULE_PENDING(1510080005, HttpStatus.CONFLICT, "Category deletion and reference rules await confirmation");
        public final int code; public final HttpStatus status; final String message;
        Reason(int code,HttpStatus status,String message){this.code=code;this.status=status;this.message=message;}
    }
    public final Reason reason;
    public PrintLibraryCategoryException(Reason reason){super(reason.message);this.reason=reason;}
}
