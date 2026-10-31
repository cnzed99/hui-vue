package cn.iocoder.yudao.server.print.miniappdemo;

public record MiniappDemoIdentity(String sessionId, String principalId, long tenantId, long storeId, long expiresAtMs) {}
