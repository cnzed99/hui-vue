package cn.iocoder.yudao.server.print.payment;
import java.util.List;
/** Immutable simulated receipt. Job IDs are stable; execution states belong to job queries. */
public record PrintPaymentView(String id,String orderId,String provider,String state,boolean simulation,
        String currency,String amount,String actorType,long actorId,long createdAtMs,List<String> jobIds) { }
