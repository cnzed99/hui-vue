package cn.iocoder.yudao.server.print.payment;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
@Component
@ConfigurationProperties(prefix="print.payments")
public class PrintPaymentProperties {
    private boolean enabled;
    private String mode;
    public boolean isEnabled(){return enabled;}
    public void setEnabled(boolean value){enabled=value;}
    public String getMode(){return mode;}
    public void setMode(String value){mode=value;}
    public void requireWriteEnabled(){
        if(!enabled)throw new PrintPaymentException(PrintPaymentException.Reason.DISABLED);
        if(!"DEMO".equals(mode))throw new PrintPaymentException(PrintPaymentException.Reason.ENVIRONMENT_FORBIDDEN);
    }
}
