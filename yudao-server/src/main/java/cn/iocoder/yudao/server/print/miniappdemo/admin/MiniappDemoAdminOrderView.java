package cn.iocoder.yudao.server.print.miniappdemo.admin;

import cn.iocoder.yudao.server.print.miniappdemo.printtask.MiniappDemoPrintTaskView;
import cn.iocoder.yudao.server.print.miniappdemo.quote.MiniappDemoQuoteSnapshot;
import java.util.List;

public record MiniappDemoAdminOrderView(String id, String orderNo, String source, boolean simulation,
        String currency, String totalAmount, String orderState, String paymentState, String refundState,
        String printState, long version, String principalId, long createdAtMs, long updatedAtMs,
        Item item, Payment payment, MiniappDemoPrintTaskView printTask, List<Event> events) {
    public record Item(String id, String quoteId, String fileId, String fileSha256,
                       String amount, MiniappDemoQuoteSnapshot quoteSnapshot) {}
    public record Payment(String id, String provider, String mode, String state, boolean simulation,
                          String currency, String amount, long createdAtMs) {}
    public record Event(String id, String type, String actorType, String actorId,
                        boolean simulation, long aggregateVersion, long createdAtMs) {}
}
