package cn.iocoder.yudao.server.print.order;
public record PrintOrderEventView(String eventId,String orderId,String type,String actorType,long actorId,
        boolean simulation,long aggregateVersion,long createdAtMs) { }
