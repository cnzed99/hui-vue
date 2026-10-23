package cn.iocoder.yudao.server.print.scope;

public record PrintContext(long tenantId, long storeId, long userId,
                           String storeName, String actorType) {
}
