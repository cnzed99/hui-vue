package cn.iocoder.yudao.server.print.miniappdemo.order;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "print.miniapp-demo.orders")
public class MiniappDemoOrderProperties {
    private boolean enabled;
    private int maxOrdersPerPrincipal;

    @PostConstruct
    public void validateAtStartup() {
        if (enabled && (maxOrdersPerPrincipal <= 0 || maxOrdersPerPrincipal > 100)) {
            throw new IllegalStateException("Enabled miniapp demo orders require a bounded principal limit");
        }
    }

    public void requireEnabled() { if (!enabled) throw new MiniappDemoOrderException(MiniappDemoOrderException.Reason.DISABLED); }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public int getMaxOrdersPerPrincipal() { return maxOrdersPerPrincipal; }
    public void setMaxOrdersPerPrincipal(int value) { maxOrdersPerPrincipal = value; }
}
