package cn.iocoder.yudao.server.print.miniappdemo.image;

import org.springframework.http.HttpStatus;

public final class MiniappDemoIdPhotoException extends RuntimeException {
    public enum Reason {
        DISABLED(1_936_001, HttpStatus.NOT_FOUND, "Miniapp demo id photo rendering is disabled"),
        INVALID_INPUT(1_936_002, HttpStatus.BAD_REQUEST, "Invalid miniapp demo id photo request"),
        INPUT_TOO_LARGE(1_936_003, HttpStatus.PAYLOAD_TOO_LARGE, "Miniapp demo id photo exceeds the input limit"),
        UNSUPPORTED_FORMAT(1_936_004, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported miniapp demo id photo format"),
        PIXEL_LIMIT(1_936_005, HttpStatus.UNPROCESSABLE_ENTITY, "Miniapp demo id photo exceeds the pixel limit"),
        RENDER_BUSY(1_936_006, HttpStatus.TOO_MANY_REQUESTS, "Miniapp demo id photo renderer is busy"),
        RENDER_TIMEOUT(1_936_007, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo id photo rendering timed out"),
        RENDER_UNAVAILABLE(1_936_008, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo id photo renderer is unavailable"),
        PDF_TOO_LARGE(1_936_009, HttpStatus.UNPROCESSABLE_ENTITY, "Miniapp demo id photo PDF exceeds the output limit"),
        CAPACITY_LOW(1_936_010, HttpStatus.INSUFFICIENT_STORAGE, "Miniapp demo id photo storage capacity is low");

        private final int code;
        private final HttpStatus status;
        private final String message;
        Reason(int code, HttpStatus status, String message) { this.code = code; this.status = status; this.message = message; }
        public int code() { return code; }
        public HttpStatus status() { return status; }
        public String message() { return message; }
    }

    private final Reason reason;
    public MiniappDemoIdPhotoException(Reason reason) { super(reason.message()); this.reason = reason; }
    public int code() { return reason.code(); }
    public HttpStatus status() { return reason.status(); }
}
