package cn.iocoder.yudao.server.print.miniappdemo.file;

public record MiniappDemoFileMetadata(String id, String displayName, long sizeBytes, Integer pageCount,
                                      String status, String failureCode, long createdAtMs, long expiresAtMs,
                                      boolean ready) {}
