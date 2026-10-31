package cn.iocoder.yudao.server.print.miniappdemo.order;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "print.miniapp-demo.payments")
public class MiniappDemoPaymentProperties {
    private boolean enabled;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void requireEnabled() {
        if (!enabled) throw new MiniappDemoPaymentException(MiniappDemoPaymentException.Reason.DISABLED);
    }
}
