package cn.iocoder.yudao.server.print.miniappdemo.file;

record MiniappDemoFileRow(String id, long tenantId, long storeId, String principalId,
                          String idempotencySha256, String contentSha256, String displayName,
                          long sizeBytes, Integer pageCount, String status, String failureCode,
                          String storageKey, long createdAtMs, long updatedAtMs, long expiresAtMs,
                          int processingAttempts) {}
