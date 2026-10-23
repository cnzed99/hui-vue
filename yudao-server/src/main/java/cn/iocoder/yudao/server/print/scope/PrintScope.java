package cn.iocoder.yudao.server.print.scope;

/** Server-resolved staff scope. Never bind this record from request input. */
public record PrintScope(long tenantId, long storeId, long userId) {
}
