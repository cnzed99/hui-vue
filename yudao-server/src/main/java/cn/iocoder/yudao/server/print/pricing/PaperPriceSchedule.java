package cn.iocoder.yudao.server.print.pricing;

import java.math.BigDecimal;

/**
 * Explicit, immutable prices for one document. A null tier means there is no tier.
 * This contract deliberately cannot express unconfirmed multi-tier or cross-file aggregation.
 */
public record PaperPriceSchedule(String version, BigDecimal simplexPricePerSheet,
                                 BigDecimal duplexPricePerSheet, SingleTier tier) {

    public PaperPriceSchedule {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("price schedule version is required");
        }
        requirePrice(simplexPricePerSheet, "simplexPricePerSheet");
        requirePrice(duplexPricePerSheet, "duplexPricePerSheet");
    }

    BigDecimal basePrice(PrintSide side) {
        return side == PrintSide.SIMPLEX ? simplexPricePerSheet : duplexPricePerSheet;
    }

    private static void requirePrice(BigDecimal price, String field) {
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException(field + " must be present and non-negative");
        }
    }

    /** When the total sheet count is strictly larger, its price replaces the base price. */
    public record SingleTier(long strictlyMoreThanSheets, BigDecimal simplexPricePerSheet,
                             BigDecimal duplexPricePerSheet) {

        public SingleTier {
            if (strictlyMoreThanSheets < 0) {
                throw new IllegalArgumentException("tier threshold must be non-negative");
            }
            requirePrice(simplexPricePerSheet, "tier.simplexPricePerSheet");
            requirePrice(duplexPricePerSheet, "tier.duplexPricePerSheet");
        }

        BigDecimal price(PrintSide side) {
            return side == PrintSide.SIMPLEX ? simplexPricePerSheet : duplexPricePerSheet;
        }
    }
}
