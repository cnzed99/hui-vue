package cn.iocoder.yudao.server.print.reservation;

/** P06 configuration only; no order dispatch or device execution is connected. */
public record PrintReservationSettings(String version, boolean configured, boolean pickupEnabled,
        boolean timedDeliveryEnabled, boolean vipDeliveryEnabled, String pickupCodePrefix,
        boolean automaticPrintEnabled, String automaticPrintExecutionStatus, String storeSettingsLinkStatus) {
    public static PrintReservationSettings of(long version, boolean configured, boolean pickup,
            boolean timed, boolean vip, String prefix, boolean automatic) {
        return new PrintReservationSettings(Long.toString(version), configured, pickup, timed, vip, prefix,
                automatic, "SIMULATED_CONFIG_ONLY", "PENDING_CONFIRMATION");
    }
    public static PrintReservationSettings unconfigured() { return of(0, false, false, false, false, "", false); }
}
