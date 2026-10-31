package cn.iocoder.yudao.server.print.miniappdemo.printtask;

public record MiniappDemoPrintTaskView(String id, String jobId, String orderId, String orderItemId,
        String state, long version, long fence, String leaseId, Long leaseUntilMs,
        String plannedCopies, String plannedSheets, String fileId, String fileSha256,
        long eventSeq, long createdAtMs, long updatedAtMs, Receipt receipt) {
    public record Receipt(String id, long receiptSeq, String result, String reason,
            String plannedCopies, String plannedSheets, String completedCopies,
            String completedSheets, boolean simulation, long createdAtMs) {}
}
