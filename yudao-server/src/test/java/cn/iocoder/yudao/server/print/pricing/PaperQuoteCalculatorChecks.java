package cn.iocoder.yudao.server.print.pricing;

import java.math.BigDecimal;

/**
 * Explicit, dependency-free unit checks. Run main; Maven/Surefire does not discover these.
 * Expected examples are literal business cases, not a second implementation of the formula.
 */
public final class PaperQuoteCalculatorChecks {

    private static final PaperQuoteCalculator CALCULATOR = new PaperQuoteCalculator();
    private static int passed;

    public static void main(String[] args) {
        run("simplex copies", () -> checkQuote(request(3, 2, PrintSide.SIMPLEX),
                prices("0.10", "0.20"), 3, 6, "0.10", "0.60", false));
        run("confirmed odd duplex example", () -> checkQuote(request(3, 2, PrintSide.DUPLEX),
                prices("0.10", "0.20"), 2, 4, "0.20", "0.80", false));
        run("even duplex copies", () -> checkQuote(request(4, 3, PrintSide.DUPLEX),
                prices("0.10", "0.20"), 2, 6, "0.20", "1.20", false));
        run("one page per separate copy", () -> checkQuote(request(1, 5, PrintSide.DUPLEX),
                prices("0.10", "0.20"), 1, 5, "0.20", "1.00", false));
        run("odd duplex does not share paper across copies", () -> checkQuote(request(5, 3, PrintSide.DUPLEX),
                prices("0.10", "0.20"), 3, 9, "0.20", "1.80", false));
        run("strict tier boundary at ten", () -> checkQuote(request(10, 1, PrintSide.SIMPLEX),
                tierPrices(), 10, 10, "0.20", "2.00", false));
        run("confirmed eleven-sheet full-tier example", () -> checkQuote(request(11, 1, PrintSide.SIMPLEX),
                tierPrices(), 11, 11, "0.15", "1.65", true));
        run("tier uses all copies of this document", () -> checkQuote(request(3, 6, PrintSide.DUPLEX),
                tierPrices(), 2, 12, "0.15", "1.80", true));
        run("duplex tier has its own configured price", () -> checkQuote(request(21, 1, PrintSide.DUPLEX),
                new PaperPriceSchedule("v1", decimal("0.20"), decimal("0.30"),
                        new PaperPriceSchedule.SingleTier(10, decimal("0.15"), decimal("0.25"))),
                11, 11, "0.25", "2.75", true));
        run("no implicit tier", () -> checkQuote(request(11, 1, PrintSide.SIMPLEX),
                prices("0.20", "0.20"), 11, 11, "0.20", "2.20", false));
        run("explicit zero price", () -> checkQuote(request(3, 2, PrintSide.SIMPLEX),
                prices("0", "0.20"), 3, 6, "0", "0", false));
        run("no unconfirmed cent rounding", () -> checkQuote(request(3, 1, PrintSide.SIMPLEX),
                prices("0.015", "0.02"), 3, 3, "0.015", "0.045", false));
        run("large duplex page count does not overflow ceiling", () -> checkQuote(
                request(Long.MAX_VALUE, 1, PrintSide.DUPLEX), prices("0.10", "0.20"),
                4611686018427387904L, 4611686018427387904L, "0.20", "922337203685477580.80", false));
        run("sheet multiplication overflow is rejected", () -> expectRejected(() -> CALCULATOR.quote(
                request(Long.MAX_VALUE, 2, PrintSide.SIMPLEX), prices("0.10", "0.20"))));
        run("zero and negative page counts rejected", () -> {
            expectRejected(() -> request(0, 1, PrintSide.SIMPLEX));
            expectRejected(() -> request(-1, 1, PrintSide.DUPLEX));
        });
        run("zero and negative copies rejected", () -> {
            expectRejected(() -> request(1, 0, PrintSide.SIMPLEX));
            expectRejected(() -> request(1, -1, PrintSide.DUPLEX));
        });
        run("missing side rejected", () -> expectRejected(() -> request(1, 1, null)));
        run("missing request and schedule rejected", () -> {
            expectRejected(() -> CALCULATOR.quote(null, prices("0.10", "0.20")));
            expectRejected(() -> CALCULATOR.quote(request(1, 1, PrintSide.SIMPLEX), null));
        });
        run("missing base prices rejected", () -> {
            expectRejected(() -> new PaperPriceSchedule("v1", null, decimal("0.20"), null));
            expectRejected(() -> new PaperPriceSchedule("v1", decimal("0.10"), null, null));
        });
        run("negative base prices rejected", () -> {
            expectRejected(() -> prices("-0.01", "0.20"));
            expectRejected(() -> prices("0.10", "-0.01"));
        });
        run("invalid tier configuration rejected", () -> {
            expectRejected(() -> new PaperPriceSchedule.SingleTier(-1, decimal("0.15"), decimal("0.15")));
            expectRejected(() -> new PaperPriceSchedule.SingleTier(10, null, decimal("0.15")));
            expectRejected(() -> new PaperPriceSchedule.SingleTier(10, decimal("0.15"), null));
            expectRejected(() -> new PaperPriceSchedule.SingleTier(10, decimal("-0.15"), decimal("0.15")));
            expectRejected(() -> new PaperPriceSchedule.SingleTier(10, decimal("0.15"), decimal("-0.15")));
        });
        run("version must be explicit", () -> {
            expectRejected(() -> new PaperPriceSchedule(null, decimal("0.10"), decimal("0.20"), null));
            expectRejected(() -> new PaperPriceSchedule("   ", decimal("0.10"), decimal("0.20"), null));
        });
        run("quote retains immutable price version and amounts", () -> {
            PaperPrintRequest request = request(3, 2, PrintSide.DUPLEX);
            PaperQuote oldQuote = CALCULATOR.quote(request, prices("0.10", "0.20"));
            PaperQuote newQuote = CALCULATOR.quote(request,
                    new PaperPriceSchedule("v2", decimal("0.12"), decimal("0.30"), null));
            equal("v1", oldQuote.priceScheduleVersion(), "old version");
            amount("0.80", oldQuote.totalAmount(), "old amount");
            equal("v2", newQuote.priceScheduleVersion(), "new version");
            amount("1.20", newQuote.totalAmount(), "new amount");
        });
        System.out.println("pricing_unit_checks_passed=" + passed);
    }

    private static PaperPrintRequest request(long pages, long copies, PrintSide side) {
        return new PaperPrintRequest(pages, copies, side);
    }

    private static PaperPriceSchedule prices(String simplex, String duplex) {
        return new PaperPriceSchedule("v1", decimal(simplex), decimal(duplex), null);
    }

    private static PaperPriceSchedule tierPrices() {
        return new PaperPriceSchedule("v1", decimal("0.20"), decimal("0.20"),
                new PaperPriceSchedule.SingleTier(10, decimal("0.15"), decimal("0.15")));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static void checkQuote(PaperPrintRequest request, PaperPriceSchedule schedule,
                                   long perCopy, long total, String unit, String amount, boolean tier) {
        PaperQuote quote = CALCULATOR.quote(request, schedule);
        equal(schedule.version(), quote.priceScheduleVersion(), "price version");
        equal(request.side(), quote.side(), "side");
        equal(request.pagesPerCopy(), quote.pagesPerCopy(), "requested pages");
        equal(request.copies(), quote.copies(), "requested copies");
        equal(perCopy, quote.sheetsPerCopy(), "sheets per copy");
        equal(total, quote.totalSheets(), "total sheets");
        amount(unit, quote.pricePerSheet(), "unit price");
        amount(amount, quote.totalAmount(), "total amount");
        equal(tier, quote.tierApplied(), "tier flag");
        equal(tier ? Long.valueOf(10) : null, quote.appliedTierThreshold(), "tier threshold snapshot");
    }

    private static void amount(String expected, BigDecimal actual, String field) {
        if (decimal(expected).compareTo(actual) != 0) {
            throw new AssertionError(field + ": expected " + expected + ", got " + actual);
        }
    }

    private static void equal(Object expected, Object actual, String field) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(field + ": expected " + expected + ", got " + actual);
        }
    }

    private static void expectRejected(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void run(String name, Runnable action) {
        try {
            action.run();
            passed++;
        } catch (Throwable failure) {
            throw new AssertionError("Failed case: " + name, failure);
        }
    }
}
