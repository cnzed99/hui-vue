package cn.iocoder.yudao.server.print.quote;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Component
@ConfigurationProperties(prefix = "print.quotes")
public class PrintQuoteProperties {
    private boolean enabled;
    private Long ttlSeconds;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled=value; }
    public Long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(Long value) { ttlSeconds=value; }
    public long requireLifetimeMillis(long now) {
        if (!enabled) throw new PrintQuoteException(PrintQuoteException.Reason.DISABLED);
        if (ttlSeconds == null || ttlSeconds <= 0 || ttlSeconds > (Long.MAX_VALUE-now)/1000)
            throw new PrintQuoteException(PrintQuoteException.Reason.CONFIGURATION_INVALID);
        return ttlSeconds * 1000;
    }
}
