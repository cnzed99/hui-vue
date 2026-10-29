package cn.iocoder.yudao.server.print.email;

/** P35 account configuration only; no real mail transport is connected. */
public record PrintEmailSettings(String version, boolean configured, String username,
        String smtpHost, int smtpPort, boolean passwordConfigured,
        String sendingStatus, String tlsStatus) {
    public static PrintEmailSettings of(long version, boolean configured, String username,
            String smtpHost, int smtpPort, boolean passwordConfigured) {
        return new PrintEmailSettings(Long.toString(version), configured, username, smtpHost,
                smtpPort, passwordConfigured, "SIMULATED_NOT_SENT", "UNCONFIRMED_NOT_CONFIGURED");
    }
    public static PrintEmailSettings unconfigured() {
        return of(0, false, "", "", 0, false);
    }
}
