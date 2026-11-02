package cn.iocoder.yudao.server.print.miniappdemo.cancellation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "print.miniapp-demo.cancellations")
public class MiniappDemoCancellationProperties {
    private boolean enabled;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public void requireWritesEnabled() {
        if (!enabled) throw new MiniappDemoCancellationException(MiniappDemoCancellationException.Reason.DISABLED);
    }
}
