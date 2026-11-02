package cn.iocoder.yudao.server.print.miniappdemo.image;

import org.springframework.http.HttpStatus;

/** Fixed public failures: never attach image bytes, paths, or decoder details. */
public final class MiniappDemoImageException extends RuntimeException {
    public enum Reason {
        DISABLED(1_933_001, HttpStatus.NOT_FOUND, "Miniapp demo image printing is disabled"),
        INVALID_INPUT(1_933_002, HttpStatus.BAD_REQUEST, "Invalid miniapp demo image request"),
        INPUT_TOO_LARGE(1_933_003, HttpStatus.PAYLOAD_TOO_LARGE, "Miniapp demo image exceeds the input limit"),
        UNSUPPORTED_FORMAT(1_933_004, HttpStatus.UNPROCESSABLE_ENTITY, "Only JPEG and PNG images are supported"),
        PIXEL_LIMIT(1_933_005, HttpStatus.PAYLOAD_TOO_LARGE, "Miniapp demo image exceeds the pixel limit"),
        RENDER_BUSY(1_933_006, HttpStatus.TOO_MANY_REQUESTS, "Miniapp demo image renderer is busy"),
        RENDER_TIMEOUT(1_933_007, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo image rendering timed out"),
        PDF_TOO_LARGE(1_933_008, HttpStatus.PAYLOAD_TOO_LARGE, "Generated PDF exceeds the size limit"),
        RENDER_UNAVAILABLE(1_933_009, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo image renderer is unavailable");

        private final int code;
        private final HttpStatus status;
        private final String message;
        Reason(int code, HttpStatus status, String message) {
            this.code = code; this.status = status; this.message = message;
        }
    }

    private final Reason reason;
    public MiniappDemoImageException(Reason reason) { super(reason.message); this.reason = reason; }
    public int code() { return reason.code; }
    public HttpStatus status() { return reason.status; }
    public Reason reason() { return reason; }
}
