package cn.iocoder.yudao.server.print.order;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Component
@ConfigurationProperties(prefix="print.orders")
public class PrintOrderProperties {
    private boolean enabled;
    public boolean isEnabled(){return enabled;}
    public void setEnabled(boolean value){enabled=value;}
    public void requireEnabled(){if(!enabled)throw new PrintOrderException(PrintOrderException.Reason.DISABLED);}
}
