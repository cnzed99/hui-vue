package cn.iocoder.yudao.server.print.miniappdemo.image;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "print.miniapp-demo.id-photo")
public class MiniappDemoIdPhotoProperties {
    public static final String RENDER_VERSION = "id-photo-6in-v2";
    public static final long MAX_INPUT_BYTES = 2L * 1024 * 1024;
    public static final long MAX_MULTIPART_BYTES = 2100L * 1024;
    public static final int MAX_WIDTH = 2400;
    public static final int MAX_HEIGHT = 3394;
    public static final long MAX_PIXELS = (long) MAX_WIDTH * MAX_HEIGHT;
    public static final int MAX_PDF_BYTES = 2 * 1024 * 1024;
    private boolean enabled;
    private String renderVersion;
    private long maxInputBytes;
    private long maxMultipartBytes;
    private int maxWidth;
    private int maxHeight;
    private long maxPixels;
    private int maxPdfBytes;
    private int maxConcurrentRequests;
    private int renderTimeoutSeconds;

    public void validateEnabled() {
        if (!enabled) return;
        if (!RENDER_VERSION.equals(renderVersion) || maxInputBytes != MAX_INPUT_BYTES
                || maxMultipartBytes != MAX_MULTIPART_BYTES || maxWidth != MAX_WIDTH || maxHeight != MAX_HEIGHT
                || maxPixels != MAX_PIXELS || maxPdfBytes != MAX_PDF_BYTES || maxConcurrentRequests != 1
                || renderTimeoutSeconds != 15) {
            throw new IllegalStateException("Enabled id photo renderer requires the approved fixed limits");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getRenderVersion() { return renderVersion; }
    public void setRenderVersion(String value) { renderVersion = value; }
    public long getMaxInputBytes() { return maxInputBytes; }
    public void setMaxInputBytes(long value) { maxInputBytes = value; }
    public long getMaxMultipartBytes() { return maxMultipartBytes; }
    public void setMaxMultipartBytes(long value) { maxMultipartBytes = value; }
    public int getMaxWidth() { return maxWidth; }
    public void setMaxWidth(int value) { maxWidth = value; }
    public int getMaxHeight() { return maxHeight; }
    public void setMaxHeight(int value) { maxHeight = value; }
    public long getMaxPixels() { return maxPixels; }
    public void setMaxPixels(long value) { maxPixels = value; }
    public int getMaxPdfBytes() { return maxPdfBytes; }
    public void setMaxPdfBytes(int value) { maxPdfBytes = value; }
    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public void setMaxConcurrentRequests(int value) { maxConcurrentRequests = value; }
    public int getRenderTimeoutSeconds() { return renderTimeoutSeconds; }
    public void setRenderTimeoutSeconds(int value) { renderTimeoutSeconds = value; }
}
