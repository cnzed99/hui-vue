package cn.iocoder.yudao.server.print.miniappdemo.quote;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import static cn.iocoder.yudao.server.print.miniappdemo.quote.MiniappDemoQuoteException.Reason.*;

@Component
@ConfigurationProperties(prefix = "print.miniapp-demo.quotes")
public class MiniappDemoQuoteProperties {
    private boolean enabled;
    private Long ttlSeconds;
    private String priceProfileId;
    private int maxActiveQuotesPerPrincipal;

    @PostConstruct
    public void validateAtStartup() {
        if (enabled) requireLifetimeMillis(System.currentTimeMillis());
    }

    public long requireLifetimeMillis(long now) {
        if (!enabled) throw new MiniappDemoQuoteException(DISABLED);
        if (ttlSeconds == null || ttlSeconds <= 0 || ttlSeconds > 3600
                || ttlSeconds > (Long.MAX_VALUE - now) / 1000
                || priceProfileId == null || !priceProfileId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || maxActiveQuotesPerPrincipal <= 0 || maxActiveQuotesPerPrincipal > 100) {
            throw new MiniappDemoQuoteException(CONFIGURATION_INVALID);
        }
        return ttlSeconds * 1000;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public Long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(Long value) { ttlSeconds = value; }
    public String getPriceProfileId() { return priceProfileId; }
    public void setPriceProfileId(String value) { priceProfileId = value; }
    public int getMaxActiveQuotesPerPrincipal() { return maxActiveQuotesPerPrincipal; }
    public void setMaxActiveQuotesPerPrincipal(int value) { maxActiveQuotesPerPrincipal = value; }
}
