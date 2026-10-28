package cn.iocoder.yudao.server.print.store.profile;

public record PrintStoreProfileView(String profileVersion, boolean configured, PrintStoreProfileValue profile,
        String regionDictionaryStatus, String officialAccountQrStatus, String officialAccountQrUrl,
        String miniProgramQrStatus, String miniProgramQrUrl) {
    static PrintStoreProfileView of(long version, boolean configured, PrintStoreProfileValue profile) {
        return new PrintStoreProfileView(Long.toString(version), configured, profile,
                "NOT_CONFIGURED", "NOT_CONFIGURED", null, "NOT_CONFIGURED", null);
    }
}
