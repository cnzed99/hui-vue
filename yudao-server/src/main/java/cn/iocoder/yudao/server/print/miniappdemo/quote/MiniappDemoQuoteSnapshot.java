package cn.iocoder.yudao.server.print.miniappdemo.quote;

/** Versioned immutable evidence. Decimal amounts and long counts are strings for exact JavaScript transport. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record MiniappDemoQuoteSnapshot(int schemaVersion, String mode, String currency,
        String aggregationPolicy, String fileId, String fileSha256, String paper, String color,
        String priceProfileId, String priceProfileKey, String priceProfileVersion,
        int pagesPerCopy, String side, String copies, String sheetsPerCopy, String totalSheets,
        String simplexPricePerSheet, String duplexPricePerSheet, Tier tier,
        String pricePerSheet, String totalAmount, boolean tierApplied,
        String appliedTierThreshold, long createdAtMs, long expiresAtMs) {
    public record Tier(String strictlyMoreThanSheets, String simplexPricePerSheet, String duplexPricePerSheet) {}
}
