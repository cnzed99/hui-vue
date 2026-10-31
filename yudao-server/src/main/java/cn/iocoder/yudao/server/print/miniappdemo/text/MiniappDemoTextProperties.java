package cn.iocoder.yudao.server.print.miniappdemo.text;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.nio.file.Path;

@Component
@ConfigurationProperties(prefix = "print.miniapp-demo.text")
public class MiniappDemoTextProperties {
    public static final String FONT_HASH = "79c18ebe7b811951e8311bad7103ebeae8c337ed9988ea69e8a78a66cfe029b9";
    public static final String FONT_NAME = "WenQuanYiZenHei";
    public static final String RENDER_VERSION = "TEXT_A4_WQY_V1";
    private boolean enabled;
    private Path fontPath;
    private String fontSha256;
    private String fontName;
    private String renderVersion;
    private int maxCodePoints;
    private int maxUtf8Bytes;
    private int maxConcurrentRenders;
    private int maxPages;
    private int maxPdfBytes;
    private long renderTimeoutMillis;

    public void validateEnabled() {
        if (!enabled) return;
        if (fontPath == null || !fontPath.isAbsolute() || !FONT_HASH.equals(fontSha256)
                || !FONT_NAME.equals(fontName) || !RENDER_VERSION.equals(renderVersion)
                || maxCodePoints < 1 || maxCodePoints > 10000 || maxUtf8Bytes < 1 || maxUtf8Bytes > 60 * 1024
                || maxConcurrentRenders != 1 || maxPages < 1 || maxPages > 20
                || maxPdfBytes < 1 || maxPdfBytes > 2 * 1024 * 1024
                || renderTimeoutMillis < 1 || renderTimeoutMillis > 5000) {
            throw new IllegalStateException("Enabled text renderer requires the approved font and explicit bounded limits");
        }
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public Path getFontPath() { return fontPath; }
    public void setFontPath(Path value) { fontPath = value; }
    public String getFontSha256() { return fontSha256; }
    public void setFontSha256(String value) { fontSha256 = value; }
    public String getFontName() { return fontName; }
    public void setFontName(String value) { fontName = value; }
    public String getRenderVersion() { return renderVersion; }
    public void setRenderVersion(String value) { renderVersion = value; }
    public int getMaxCodePoints() { return maxCodePoints; }
    public void setMaxCodePoints(int value) { maxCodePoints = value; }
    public int getMaxUtf8Bytes() { return maxUtf8Bytes; }
    public void setMaxUtf8Bytes(int value) { maxUtf8Bytes = value; }
    public int getMaxConcurrentRenders() { return maxConcurrentRenders; }
    public void setMaxConcurrentRenders(int value) { maxConcurrentRenders = value; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int value) { maxPages = value; }
    public int getMaxPdfBytes() { return maxPdfBytes; }
    public void setMaxPdfBytes(int value) { maxPdfBytes = value; }
    public long getRenderTimeoutMillis() { return renderTimeoutMillis; }
    public void setRenderTimeoutMillis(long value) { renderTimeoutMillis = value; }
}
