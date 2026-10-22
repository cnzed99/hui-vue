package cn.iocoder.yudao.server.print.pricing;

import java.math.BigDecimal;

/** Pure calculation of the confirmed one-document paper-pricing rules. */
public final class PaperQuoteCalculator {

    public PaperQuote quote(PaperPrintRequest request, PaperPriceSchedule schedule) {
        if (request == null) {
            throw new IllegalArgumentException("request is required");
        }
        if (schedule == null) {
            throw new IllegalArgumentException("price schedule is required");
        }
        // Round each copy up independently; pages + 1 would overflow for Long.MAX_VALUE.
        long sheetsPerCopy = request.side() == PrintSide.SIMPLEX
                ? request.pagesPerCopy()
                : request.pagesPerCopy() / 2 + request.pagesPerCopy() % 2;
        long totalSheets;
        try {
            totalSheets = Math.multiplyExact(sheetsPerCopy, request.copies());
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("total sheet count exceeds supported range", ex);
        }
        PaperPriceSchedule.SingleTier tier = schedule.tier();
        boolean tierApplied = tier != null && totalSheets > tier.strictlyMoreThanSheets();
        BigDecimal pricePerSheet = tierApplied ? tier.price(request.side()) : schedule.basePrice(request.side());
        BigDecimal totalAmount = pricePerSheet.multiply(BigDecimal.valueOf(totalSheets));
        return new PaperQuote(schedule.version(), request.side(), request.pagesPerCopy(), request.copies(),
                sheetsPerCopy, totalSheets, pricePerSheet, totalAmount, tierApplied,
                tierApplied ? tier.strictlyMoreThanSheets() : null);
    }
}
