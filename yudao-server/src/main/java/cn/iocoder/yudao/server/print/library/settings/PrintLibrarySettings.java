package cn.iocoder.yudao.server.print.library.settings;

/** Server-owned path remains unconfigured until a real mini-program resource is available. */
public record PrintLibrarySettings(String version, boolean configured, String name, String description,
                                   String miniProgramPathStatus, String miniProgramPath) {
    public static PrintLibrarySettings of(long version, boolean configured, String name, String description) {
        return new PrintLibrarySettings(Long.toString(version), configured, name, description,
                "NOT_CONFIGURED", null);
    }
}
