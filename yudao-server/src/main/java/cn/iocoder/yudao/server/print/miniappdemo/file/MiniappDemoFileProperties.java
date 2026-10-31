package cn.iocoder.yudao.server.print.miniappdemo.file;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "print.miniapp-demo.files")
public class MiniappDemoFileProperties {
    private boolean enabled;
    private Path storageRoot;
    private Path runtimeJar;
    private long maxBytes;
    private int maxPages;
    private long retentionSeconds;
    private int queueCapacity = 8;
    private int maxActiveFilesPerPrincipal = 3;
    private int maxProcessingAttempts = 3;
    private int parserTimeoutSeconds = 10;
    private int maxConcurrentUploads = 2;
    private long minFreeBytes = 10L * 1024 * 1024 * 1024;

    public void validateEnabled() {
        if (!enabled) return;
        if (storageRoot == null || !storageRoot.isAbsolute() || runtimeJar == null || !runtimeJar.isAbsolute()
                || maxBytes <= 0 || maxBytes > 2L * 1024 * 1024
                || maxPages <= 0 || maxPages > 20 || retentionSeconds <= 0 || retentionSeconds > 3600
                || queueCapacity <= 0 || queueCapacity > 32 || maxActiveFilesPerPrincipal <= 0 || maxActiveFilesPerPrincipal > 10
                || maxProcessingAttempts <= 0 || maxProcessingAttempts > 5 || parserTimeoutSeconds <= 0 || parserTimeoutSeconds > 30
                || maxConcurrentUploads <= 0 || maxConcurrentUploads > 4 || minFreeBytes < 10L * 1024 * 1024 * 1024) {
            throw new IllegalStateException("Enabled miniapp demo files require explicit bounded limits and private storage");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public Path getStorageRoot() { return storageRoot; }
    public void setStorageRoot(Path value) { storageRoot = value; }
    public Path getRuntimeJar() { return runtimeJar; }
    public void setRuntimeJar(Path value) { runtimeJar = value; }
    public long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(long value) { maxBytes = value; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int value) { maxPages = value; }
    public long getRetentionSeconds() { return retentionSeconds; }
    public void setRetentionSeconds(long value) { retentionSeconds = value; }
    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int value) { queueCapacity = value; }
    public int getMaxActiveFilesPerPrincipal() { return maxActiveFilesPerPrincipal; }
    public void setMaxActiveFilesPerPrincipal(int value) { maxActiveFilesPerPrincipal = value; }
    public int getMaxProcessingAttempts() { return maxProcessingAttempts; }
    public void setMaxProcessingAttempts(int value) { maxProcessingAttempts = value; }
    public int getParserTimeoutSeconds() { return parserTimeoutSeconds; }
    public void setParserTimeoutSeconds(int value) { parserTimeoutSeconds = value; }
    public int getMaxConcurrentUploads() { return maxConcurrentUploads; }
    public void setMaxConcurrentUploads(int value) { maxConcurrentUploads = value; }
    public long getMinFreeBytes() { return minFreeBytes; }
    public void setMinFreeBytes(long value) { minFreeBytes = value; }
}
