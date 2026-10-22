package cn.iocoder.yudao.server.print.pricing;

import java.math.BigDecimal;

/**
 * Immutable calculation snapshot, with exact decimal amounts and no implicit rounding.
 * It is not a payment settlement instruction: currency precision and adjustments are separate.
 */
public record PaperQuote(String priceScheduleVersion, PrintSide side, long pagesPerCopy,
                         long copies, long sheetsPerCopy, long totalSheets,
                         BigDecimal pricePerSheet, BigDecimal totalAmount,
                         boolean tierApplied, Long appliedTierThreshold) {
}
