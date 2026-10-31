package cn.iocoder.yudao.server.print.miniappdemo.admin;

public record MiniappDemoAdminOrderSummary(String id, String orderNo, String source, boolean simulation,
        String currency, String totalAmount, String orderState, String paymentState, String refundState,
        String printState, long version, String principalId, long createdAtMs, long updatedAtMs) {}
