package cn.iocoder.yudao.server.print.file;

import java.time.Instant;

/** No disk path, storage key, owner credential or public URL is serialized. */
public record PrintFileMetadata(String id, String displayName, String mediaType, long sizeBytes,
                                Integer pageCount, String status, String failureCode,
                                Instant createdAt, Instant expiresAt, boolean contentAvailable) {
}
