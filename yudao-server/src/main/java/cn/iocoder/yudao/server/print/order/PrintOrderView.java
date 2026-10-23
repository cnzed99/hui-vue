package cn.iocoder.yudao.server.print.order;
import cn.iocoder.yudao.server.print.quote.PrintQuoteSnapshot;
import java.util.List;
public record PrintOrderView(String id,String orderNo,String source,boolean simulation,String currency,
        String totalAmount,String orderState,String paymentState,String refundState,String deliveryMode,
        String deliveryState,String printState,String createdByActorType,long createdByActorId,
        long createdAtMs,List<Item> items) {
    public record Item(String id,String quoteId,String fileId,String fileSha256,String amount,PrintQuoteSnapshot quoteSnapshot) { }
}
