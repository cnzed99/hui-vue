package cn.iocoder.yudao.server.print.miniappdemo.image;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "print.miniapp-demo.image")
public class MiniappDemoImageProperties {
    public static final String RENDER_VERSION = "IMAGE_A4_PORTRAIT_CONTAIN_V1";
    private boolean enabled;
    private String renderVersion;
    private long maxInputBytes;
    private long maxPixels;
    private int maxDimension;
    private int maxPdfBytes;
    private int maxConcurrentRenders;
    private int renderTimeoutSeconds;

    public void validateEnabled() {
        if (!enabled) return;
        if (!RENDER_VERSION.equals(renderVersion)
                || maxInputBytes < 1 || maxInputBytes > 2L * 1024 * 1024
                || maxPixels < 1 || maxPixels > 20_000_000L
                || maxDimension < 1 || maxDimension > 12_000
                || maxPdfBytes < 1 || maxPdfBytes > 2 * 1024 * 1024
                || maxConcurrentRenders != 1
                || renderTimeoutSeconds < 1 || renderTimeoutSeconds > 20) {
            throw new IllegalStateException("Enabled image renderer requires explicit bounded limits");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getRenderVersion() { return renderVersion; }
    public void setRenderVersion(String value) { renderVersion = value; }
    public long getMaxInputBytes() { return maxInputBytes; }
    public void setMaxInputBytes(long value) { maxInputBytes = value; }
    public long getMaxPixels() { return maxPixels; }
    public void setMaxPixels(long value) { maxPixels = value; }
    public int getMaxDimension() { return maxDimension; }
    public void setMaxDimension(int value) { maxDimension = value; }
    public int getMaxPdfBytes() { return maxPdfBytes; }
    public void setMaxPdfBytes(int value) { maxPdfBytes = value; }
    public int getMaxConcurrentRenders() { return maxConcurrentRenders; }
    public void setMaxConcurrentRenders(int value) { maxConcurrentRenders = value; }
    public int getRenderTimeoutSeconds() { return renderTimeoutSeconds; }
    public void setRenderTimeoutSeconds(int value) { renderTimeoutSeconds = value; }
}
