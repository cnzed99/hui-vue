package cn.iocoder.yudao.server.print.file;

/** Package-private persistence row, never returned as an API response. */
record PrintFileRow(String id, long tenantId, long storeId, long ownerUserId,
                    String idempotencySha256, String contentSha256, String displayName,
                    long sizeBytes, Integer pageCount, String status, String failureCode,
                    String storageKey, long createdAtMs, long updatedAtMs, long expiresAtMs,
                    int processingAttempts) {
}
