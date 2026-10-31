package cn.iocoder.yudao.server.print.miniappdemo.order;

import cn.iocoder.yudao.server.print.miniappdemo.quote.MiniappDemoQuoteSnapshot;

public record MiniappDemoOrderView(String id, String orderNo, String source, boolean simulation,
        String currency, String totalAmount, String orderState, String paymentState,
        String refundState, String printState, long version, long createdAtMs, long updatedAtMs, Item item) {
    public record Item(String id, String quoteId, String fileId, String fileSha256,
                       String amount, MiniappDemoQuoteSnapshot quoteSnapshot) {}
}
