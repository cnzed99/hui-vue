package cn.iocoder.yudao.server.print.miniappdemo.text;

import org.springframework.http.HttpStatus;

/** Fixed public failures: never attach user text or an underlying exception. */
public final class MiniappDemoTextException extends RuntimeException {
    public enum Reason {
        DISABLED(1_932_001, HttpStatus.NOT_FOUND, "Miniapp demo text printing is disabled"),
        INVALID_INPUT(1_932_002, HttpStatus.BAD_REQUEST, "Invalid miniapp demo text request"),
        INPUT_TOO_LARGE(1_932_003, HttpStatus.PAYLOAD_TOO_LARGE, "Miniapp demo text exceeds the input limit"),
        UNSUPPORTED_CHARACTER(1_932_004, HttpStatus.UNPROCESSABLE_ENTITY, "Text contains unsupported characters"),
        RENDER_BUSY(1_932_005, HttpStatus.TOO_MANY_REQUESTS, "Miniapp demo text renderer is busy"),
        RENDER_UNAVAILABLE(1_932_006, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo text renderer is unavailable"),
        PAGE_LIMIT(1_932_007, HttpStatus.PAYLOAD_TOO_LARGE, "Miniapp demo text exceeds the page limit"),
        PDF_TOO_LARGE(1_932_008, HttpStatus.PAYLOAD_TOO_LARGE, "Generated PDF exceeds the size limit"),
        RENDER_TIMEOUT(1_932_009, HttpStatus.SERVICE_UNAVAILABLE, "Miniapp demo text rendering timed out");

        private final int code;
        private final HttpStatus status;
        private final String message;
        Reason(int code, HttpStatus status, String message) { this.code = code; this.status = status; this.message = message; }
    }
    private final Reason reason;
    public MiniappDemoTextException(Reason reason) { super(reason.message); this.reason = reason; }
    public int code() { return reason.code; }
    public HttpStatus status() { return reason.status; }
    public Reason reason() { return reason; }
}
