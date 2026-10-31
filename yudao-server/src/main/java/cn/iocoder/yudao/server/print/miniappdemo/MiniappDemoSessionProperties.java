package cn.iocoder.yudao.server.print.miniappdemo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "print.miniapp-demo")
public class MiniappDemoSessionProperties {
    private boolean enabled;
    private boolean issuanceEnabled;
    private Long tenantId;
    private Long storeId;
    private String allowedOrigin;
    private String edgeHost;
    private long ttlSeconds = 3600;
    private int maxActiveSessions = 50;
    private int maxIssuePerMinute = 10;

    public void requireIssuanceEnabled() {
        requireEnabled();
        if (!issuanceEnabled) throw new MiniappDemoSessionException(MiniappDemoSessionException.Reason.DISABLED);
        if (maxActiveSessions <= 0 || maxActiveSessions > 500
                || maxIssuePerMinute <= 0 || maxIssuePerMinute > 100) {
            throw new MiniappDemoSessionException(MiniappDemoSessionException.Reason.CONFIGURATION_INVALID);
        }
    }

    public void requireEnabled() {
        if (!enabled) throw new MiniappDemoSessionException(MiniappDemoSessionException.Reason.DISABLED);
        if (tenantId == null || tenantId <= 0 || storeId == null || storeId <= 0
                || allowedOrigin == null || !allowedOrigin.matches("https?://[A-Za-z0-9.-]+(:[1-9][0-9]{0,4})?")
                || edgeHost == null || !edgeHost.matches("[A-Za-z0-9.-]+(:[1-9][0-9]{0,4})?")
                || ttlSeconds <= 0 || ttlSeconds > 3600) {
            throw new MiniappDemoSessionException(MiniappDemoSessionException.Reason.CONFIGURATION_INVALID);
        }
    }

    public void requireIssuanceRequest(String origin, String host) {
        requireIssuanceEnabled();
        if (!allowedOrigin.equals(origin) || !edgeHost.equals(host)) {
            throw new MiniappDemoSessionException(MiniappDemoSessionException.Reason.ISSUANCE_FORBIDDEN);
        }
    }

    public void requireSessionRequest(String origin, String host) {
        requireEnabled();
        if (!edgeHost.equals(host) || (origin != null && !origin.isBlank() && !allowedOrigin.equals(origin))) {
            throw new MiniappDemoSessionException(MiniappDemoSessionException.Reason.ISSUANCE_FORBIDDEN);
        }
    }

    public void requireWriteRequest(String origin, String host) {
        requireEnabled();
        if (!allowedOrigin.equals(origin) || !edgeHost.equals(host)) {
            throw new MiniappDemoSessionException(MiniappDemoSessionException.Reason.ISSUANCE_FORBIDDEN);
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isIssuanceEnabled() { return issuanceEnabled; }
    public void setIssuanceEnabled(boolean value) { issuanceEnabled = value; }
    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long value) { tenantId = value; }
    public Long getStoreId() { return storeId; }
    public void setStoreId(Long value) { storeId = value; }
    public String getAllowedOrigin() { return allowedOrigin; }
    public void setAllowedOrigin(String value) { allowedOrigin = value; }
    public String getEdgeHost() { return edgeHost; }
    public void setEdgeHost(String value) { edgeHost = value; }
    public long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(long value) { ttlSeconds = value; }
    public int getMaxActiveSessions() { return maxActiveSessions; }
    public void setMaxActiveSessions(int value) { maxActiveSessions = value; }
    public int getMaxIssuePerMinute() { return maxIssuePerMinute; }
    public void setMaxIssuePerMinute(int value) { maxIssuePerMinute = value; }
}
