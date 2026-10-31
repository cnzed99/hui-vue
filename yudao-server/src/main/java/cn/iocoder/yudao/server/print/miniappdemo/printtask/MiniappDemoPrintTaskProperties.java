package cn.iocoder.yudao.server.print.miniappdemo.printtask;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "print.miniapp-demo.print-tasks")
public class MiniappDemoPrintTaskProperties {
    private boolean enabled;
    private int leaseSeconds;

    @PostConstruct
    public void validateAtStartup() {
        if (enabled && (leaseSeconds < 10 || leaseSeconds > 600)) {
            throw new IllegalStateException("Enabled miniapp demo print tasks require a 10..600 second lease");
        }
    }
    public void requireEnabled() {
        if (!enabled) throw new MiniappDemoPrintTaskException(MiniappDemoPrintTaskException.Reason.DISABLED);
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int leaseSeconds) { this.leaseSeconds = leaseSeconds; }
}
