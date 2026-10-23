package cn.iocoder.yudao.server.print.order;
/** List projection omits file hashes and full snapshots; detail is separately authorized. */
public record PrintOrderSummary(String id,String orderNo,String source,boolean simulation,String currency,
        String totalAmount,String orderState,String paymentState,String refundState,String deliveryMode,
        String deliveryState,String printState,String createdByActorType,long createdByActorId,long createdAtMs) { }
