package cn.iocoder.yudao.server.print.miniappdemo.quote;

import cn.iocoder.yudao.server.print.pricing.PaperPriceSchedule;
import cn.iocoder.yudao.server.print.pricing.PaperPrintRequest;
import cn.iocoder.yudao.server.print.pricing.PaperQuote;
import cn.iocoder.yudao.server.print.pricing.PaperQuoteCalculator;
import cn.iocoder.yudao.server.print.pricing.PrintSide;

import java.math.BigDecimal;

/** Independently recalculates a frozen quote snapshot without consulting the current price profile. */
public final class MiniappDemoQuoteVerifier {
    private static final PaperQuoteCalculator CALCULATOR = new PaperQuoteCalculator();

    private MiniappDemoQuoteVerifier() {}

    public static Verified verify(MiniappDemoQuoteSnapshot snapshot) {
        if (snapshot == null || snapshot.schemaVersion() != 1 || !"DEMO".equals(snapshot.mode())
                || !"CNY".equals(snapshot.currency()) || !"PER_FILE_ALL_COPIES".equals(snapshot.aggregationPolicy())
                || !uuid(snapshot.fileId()) || !digest(snapshot.fileSha256()) || !"A4".equals(snapshot.paper())
                || !"BLACK_WHITE".equals(snapshot.color()) || !uuid(snapshot.priceProfileId())
                || snapshot.priceProfileKey() == null || snapshot.priceProfileKey().isBlank()
                || snapshot.priceProfileVersion() == null || snapshot.priceProfileVersion().isBlank()
                || snapshot.pagesPerCopy() < 1 || snapshot.pagesPerCopy() > 20
                || snapshot.createdAtMs() <= 0 || snapshot.expiresAtMs() <= snapshot.createdAtMs()) {
            throw invalid();
        }
        PrintSide side;
        try {
            side = PrintSide.valueOf(snapshot.side());
        } catch (RuntimeException ex) {
            throw invalid();
        }
        long copies = positive(snapshot.copies(), 99);
        BigDecimal simplex = decimal(snapshot.simplexPricePerSheet());
        BigDecimal duplex = decimal(snapshot.duplexPricePerSheet());
        PaperPriceSchedule.SingleTier tier = null;
        if (snapshot.tier() != null) {
            long threshold = nonNegative(snapshot.tier().strictlyMoreThanSheets());
            tier = new PaperPriceSchedule.SingleTier(threshold,
                    decimal(snapshot.tier().simplexPricePerSheet()), decimal(snapshot.tier().duplexPricePerSheet()));
        }
        PaperPriceSchedule schedule;
        PaperQuote calculated;
        try {
            schedule = new PaperPriceSchedule(snapshot.priceProfileVersion(), simplex, duplex, tier);
            calculated = CALCULATOR.quote(new PaperPrintRequest(snapshot.pagesPerCopy(), copies, side), schedule);
        } catch (IllegalArgumentException ex) {
            throw invalid();
        }
        if (!Long.toString(calculated.sheetsPerCopy()).equals(snapshot.sheetsPerCopy())
                || !Long.toString(calculated.totalSheets()).equals(snapshot.totalSheets())
                || !calculated.pricePerSheet().toPlainString().equals(snapshot.pricePerSheet())
                || !calculated.totalAmount().toPlainString().equals(snapshot.totalAmount())
                || calculated.tierApplied() != snapshot.tierApplied()
                || !java.util.Objects.equals(calculated.appliedTierThreshold() == null ? null
                : calculated.appliedTierThreshold().toString(), snapshot.appliedTierThreshold())) {
            throw invalid();
        }
        return new Verified(calculated, snapshot.currency(), snapshot.totalAmount());
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.length() > 64 || !value.matches("(0|[1-9][0-9]*)(\\.[0-9]+)?")) throw invalid();
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ex) {
            throw invalid();
        }
    }

    private static long positive(String value, long maximum) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid();
        try {
            long parsed = Long.parseLong(value);
            if (parsed > maximum) throw invalid();
            return parsed;
        } catch (NumberFormatException ex) {
            throw invalid();
        }
    }

    private static long nonNegative(String value) {
        if (value == null || !value.matches("(0|[1-9][0-9]{0,18})")) throw invalid();
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw invalid();
        }
    }

    private static boolean uuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static boolean digest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid frozen miniapp demo quote"); }

    public record Verified(PaperQuote calculation, String currency, String totalAmount) {}
}
