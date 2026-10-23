package cn.iocoder.yudao.server.print.file;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.nio.file.Path;

/** No production upload/retention policy is inferred from a demo fixture. */
@Component
@ConfigurationProperties(prefix = "print.files")
public class PrintFileProperties {
    private boolean enabled;
    private Path storageRoot;
    private Long maxBytes;
    private Integer maxPages;
    private Long retentionSeconds;
    private int queueCapacity = 16;
    private int maxProcessingAttempts = 3;
    private long minFreeBytes = 10L * 1024 * 1024 * 1024;

    public void validateEnabled() {
        if (!enabled) return;
        if (storageRoot == null || !storageRoot.isAbsolute()
                || maxBytes == null || maxBytes <= 0 || maxPages == null || maxPages <= 0
                || retentionSeconds == null || retentionSeconds <= 0
                || retentionSeconds > (Long.MAX_VALUE - System.currentTimeMillis()) / 1000
                || queueCapacity <= 0 || maxProcessingAttempts <= 0 || minFreeBytes < 0) {
            throw new IllegalStateException("Enabled print file service requires explicit positive limits, retention and absolute private storage root");
        }
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public Path getStorageRoot() { return storageRoot; }
    public void setStorageRoot(Path value) { storageRoot = value; }
    public Long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(Long value) { maxBytes = value; }
    public Integer getMaxPages() { return maxPages; }
    public void setMaxPages(Integer value) { maxPages = value; }
    public Long getRetentionSeconds() { return retentionSeconds; }
    public void setRetentionSeconds(Long value) { retentionSeconds = value; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { queueCapacity = value; }
    public int getMaxProcessingAttempts() { return maxProcessingAttempts; }
    public void setMaxProcessingAttempts(int value) { maxProcessingAttempts = value; }
    public long getMinFreeBytes() { return minFreeBytes; }
    public void setMinFreeBytes(long value) { minFreeBytes = value; }
}
