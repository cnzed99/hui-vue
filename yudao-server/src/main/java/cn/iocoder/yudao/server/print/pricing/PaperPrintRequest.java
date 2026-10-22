package cn.iocoder.yudao.server.print.pricing;

/** A single document's already-selected pages, repeated as separate copies. */
public record PaperPrintRequest(long pagesPerCopy, long copies, PrintSide side) {

    public PaperPrintRequest {
        if (pagesPerCopy <= 0) {
            throw new IllegalArgumentException("pagesPerCopy must be positive");
        }
        if (copies <= 0) {
            throw new IllegalArgumentException("copies must be positive");
        }
        if (side == null) {
            throw new IllegalArgumentException("side is required");
        }
    }
}
