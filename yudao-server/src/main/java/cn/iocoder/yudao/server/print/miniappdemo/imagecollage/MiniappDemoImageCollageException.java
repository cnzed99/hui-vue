package cn.iocoder.yudao.server.print.miniappdemo.imagecollage;

import org.springframework.http.HttpStatus;

/** Fixed public failures: never attach image bytes, names, paths, digests, or decoder details. */
public final class MiniappDemoImageCollageException extends RuntimeException {
    public enum Reason {
        DISABLED(1_934_001, HttpStatus.NOT_FOUND, "Miniapp demo image collage is disabled"),
        INVALID_INPUT(1_934_002, HttpStatus.BAD_REQUEST, "Invalid miniapp demo image collage request"),
        INPUT_TOO_LARGE(1_934_003, HttpStatus.PAYLOAD_TOO_LARGE, "Miniapp demo image collage exceeds the input limit"),
        UNSUPPORTED_FORMAT(1_934_004, HttpStatus.UNPROCESSABLE_ENTITY, "Only static JPEG and PNG images are supported"),
        PIXEL_LIMIT(1_934_005, HttpStatus.PAYLOAD_TOO_LARGE, "Miniapp demo image collage exceeds the pixel limit"),
        RENDER_BUSY(1_934_006, HttpStatus.TOO_MANY_REQUESTS, "Miniapp demo image collage renderer is busy"),
        RENDER_TIMEOUT(1_934_007, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo image collage rendering timed out"),
        PDF_TOO_LARGE(1_934_008, HttpStatus.PAYLOAD_TOO_LARGE, "Generated collage PDF exceeds the size limit"),
        RENDER_UNAVAILABLE(1_934_009, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo image collage renderer is unavailable"),
        CAPACITY_LOW(1_934_010, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo image collage storage capacity is low");

        private final int code;
        private final HttpStatus status;
        private final String message;
        Reason(int code, HttpStatus status, String message) {
            this.code = code; this.status = status; this.message = message;
        }
    }

    private final Reason reason;
    public MiniappDemoImageCollageException(Reason reason) { super(reason.message); this.reason = reason; }
    public int code() { return reason.code; }
    public HttpStatus status() { return reason.status; }
    public Reason reason() { return reason; }
}
