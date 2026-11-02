package cn.iocoder.yudao.server.print.miniappdemo.cancellation;

import cn.iocoder.yudao.server.print.miniappdemo.order.MiniappDemoOrderView;

public record MiniappDemoCancellationView(Capability capability, MiniappDemoOrderView currentOrder, ActionView cancellation) {
    public record Capability(String action, String reason, long expectedVersion) {}
    public record OriginalResult(String id, String orderId, String outcome, String reason,
            long orderVersionBefore, long orderVersionAfter, String taskId,
            String taskStateBefore, String taskStateAfter, Long taskVersionBefore, Long taskVersionAfter,
            Long taskFenceBefore, Long taskFenceAfter, long createdAtMs, boolean simulation) {}
    public record ActionView(OriginalResult originalResult, MiniappDemoOrderView currentOrder,
            RefundReceipt refundReceipt, ReviewSummary reviewSummary) {}
    public record RefundReceipt(String id, String cancellationId, String paymentReceiptId, String orderId,
            String provider, String mode, String state, boolean simulation, String currency, String amount,
            long createdAtMs, Journal debit, Journal credit) {}
    public record Journal(String side, String account, String currency, String amount) {}
    public record ReviewSummary(String cancellationId, String status, String taskId, String taskStateAtRequest,
            long taskVersionAtRequest, long fenceAtRequest, long createdAtMs) {}
}
