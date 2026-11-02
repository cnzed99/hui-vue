package cn.iocoder.yudao.server.print.miniappdemo.imagecollage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "print.miniapp-demo.image-collage")
public class MiniappDemoImageCollageProperties {
    public static final String RENDER_VERSION = "IMAGE_COLLAGE_A4_PORTRAIT_GRID_V1";
    private boolean enabled;
    private String renderVersion;
    private Path multipartRoot;
    private long maxMultipartBytes;
    private long maxImageBytes;
    private long maxTotalInputBytes;
    private long maxPixels;
    private long maxTotalPixels;
    private int maxDimension;
    private int maxPdfBytes;
    private int maxConcurrentUploads;
    private long minFreeBytes;
    private int renderTimeoutSeconds;

    public void validateEnabled() {
        if (!enabled) return;
        if (!RENDER_VERSION.equals(renderVersion) || multipartRoot == null
                || maxMultipartBytes < 1 || maxMultipartBytes > 13L * 1024 * 1024
                || maxImageBytes < 1 || maxImageBytes > 2L * 1024 * 1024
                || maxTotalInputBytes < maxImageBytes || maxTotalInputBytes > 12L * 1024 * 1024
                || maxPixels < 1 || maxPixels > 6_000_000L
                || maxTotalPixels < maxPixels || maxTotalPixels > 30_000_000L
                || maxDimension < 1 || maxDimension > 8192
                || maxPdfBytes < 1 || maxPdfBytes > 2 * 1024 * 1024
                || maxConcurrentUploads != 2 || minFreeBytes < 10L * 1024 * 1024 * 1024
                || renderTimeoutSeconds < 1 || renderTimeoutSeconds > 15) {
            throw new IllegalStateException("Enabled image collage requires explicit bounded limits");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getRenderVersion() { return renderVersion; }
    public void setRenderVersion(String value) { renderVersion = value; }
    public Path getMultipartRoot() { return multipartRoot; }
    public void setMultipartRoot(Path value) { multipartRoot = value; }
    public long getMaxMultipartBytes() { return maxMultipartBytes; }
    public void setMaxMultipartBytes(long value) { maxMultipartBytes = value; }
    public long getMaxImageBytes() { return maxImageBytes; }
    public void setMaxImageBytes(long value) { maxImageBytes = value; }
    public long getMaxTotalInputBytes() { return maxTotalInputBytes; }
    public void setMaxTotalInputBytes(long value) { maxTotalInputBytes = value; }
    public long getMaxPixels() { return maxPixels; }
    public void setMaxPixels(long value) { maxPixels = value; }
    public long getMaxTotalPixels() { return maxTotalPixels; }
    public void setMaxTotalPixels(long value) { maxTotalPixels = value; }
    public int getMaxDimension() { return maxDimension; }
    public void setMaxDimension(int value) { maxDimension = value; }
    public int getMaxPdfBytes() { return maxPdfBytes; }
    public void setMaxPdfBytes(int value) { maxPdfBytes = value; }
    public int getMaxConcurrentUploads() { return maxConcurrentUploads; }
    public void setMaxConcurrentUploads(int value) { maxConcurrentUploads = value; }
    public long getMinFreeBytes() { return minFreeBytes; }
    public void setMinFreeBytes(long value) { minFreeBytes = value; }
    public int getRenderTimeoutSeconds() { return renderTimeoutSeconds; }
    public void setRenderTimeoutSeconds(int value) { renderTimeoutSeconds = value; }
}
