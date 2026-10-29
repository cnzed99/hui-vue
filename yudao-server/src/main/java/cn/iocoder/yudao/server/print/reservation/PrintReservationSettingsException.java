package cn.iocoder.yudao.server.print.reservation;

import org.springframework.http.HttpStatus;

public final class PrintReservationSettingsException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT(1510100001, HttpStatus.BAD_REQUEST, "Invalid reservation settings"),
        VERSION_CONFLICT(1510100002, HttpStatus.CONFLICT, "Reservation settings changed; reload before saving"),
        NOT_AVAILABLE(1510100003, HttpStatus.NOT_FOUND, "Current store is unavailable"),
        STORAGE_UNAVAILABLE(1510100004, HttpStatus.SERVICE_UNAVAILABLE, "Reservation settings storage unavailable"),
        WRITE_DISABLED(1510100005, HttpStatus.SERVICE_UNAVAILABLE, "Reservation settings writes are not activated");
        public final int code; public final HttpStatus status; final String message;
        Reason(int code, HttpStatus status, String message) { this.code=code; this.status=status; this.message=message; }
    }
    public final Reason reason;
    public PrintReservationSettingsException(Reason reason) { super(reason.message); this.reason=reason; }
}
