package cn.iocoder.yudao.server.print.quote;
/** Versioned immutable evidence; strings preserve exact decimals and long counts in JS. */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
public record PrintQuoteSnapshot(int schemaVersion, String mode, String currency,
        String aggregationPolicy, String fileId, String fileSha256, String priceProfileId,
        String priceProfileKey, String priceProfileVersion, int pagesPerCopy, String side,
        String copies, String sheetsPerCopy, String totalSheets, String simplexPricePerSheet,
        String duplexPricePerSheet, Tier tier, String pricePerSheet, String totalAmount,
        boolean tierApplied, String appliedTierThreshold, long createdAtMs, long expiresAtMs) {
    public record Tier(String strictlyMoreThanSheets, String simplexPricePerSheet, String duplexPricePerSheet) { }
}
