package cn.iocoder.yudao.server.print.miniappdemo.order;

public record MiniappDemoPaymentView(String id, String orderId, String provider, String mode,
        String state, boolean simulation, String currency, String amount, long createdAtMs,
        Journal debit, Journal credit) {
    public record Journal(String side, String account, String currency, String amount) {}
}
