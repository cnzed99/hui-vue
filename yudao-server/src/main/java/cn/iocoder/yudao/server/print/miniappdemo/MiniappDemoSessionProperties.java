package cn.iocoder.yudao.server.print.miniappdemo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "print.miniapp-demo")
public class MiniappDemoSessionProperties {
    private boolean enabled;
    private boolean issuanceEnabled;
    private boolean wechatNativeEnabled;
    private String wechatAppId;
    private Long tenantId;
    private Long storeId;
    private String allowedOrigin;
    private String edgeHost;
    private long ttlSeconds = 3600;
    private int maxActiveSessions = 50;
    private int maxIssuePerMinute = 10;

    @jakarta.annotation.PostConstruct
    public void validateNativeConfiguration() {
        if (wechatNativeEnabled) requireEnabled();
    }

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
                || ttlSeconds <= 0 || ttlSeconds > 3600
                || (wechatNativeEnabled && (wechatAppId == null || !wechatAppId.matches("wx[0-9a-f]{16}")))) {
            throw new MiniappDemoSessionException(MiniappDemoSessionException.Reason.CONFIGURATION_INVALID);
        }
    }

    public void requireIssuanceRequest(String origin, String host) { requireIssuanceRequest(origin, null, host); }
    public void requireIssuanceRequest(String origin, String referer, String host) {
        requireIssuanceEnabled();
        if (!allowedChannel(origin, referer, host)) {
            throw new MiniappDemoSessionException(MiniappDemoSessionException.Reason.ISSUANCE_FORBIDDEN);
        }
    }

    public void requireSessionRequest(String origin, String host) {
        requireEnabled();
        if (!edgeHost.equals(host) || (origin != null && !origin.isBlank() && !allowedOrigin.equals(origin))) {
            throw new MiniappDemoSessionException(MiniappDemoSessionException.Reason.ISSUANCE_FORBIDDEN);
        }
    }

    public void requireWriteRequest(String origin, String host) { requireWriteRequest(origin, null, host); }
    public void requireWriteRequest(String origin, String referer, String host) {
        requireEnabled();
        if (!allowedChannel(origin, referer, host)) {
            throw new MiniappDemoSessionException(MiniappDemoSessionException.Reason.ISSUANCE_FORBIDDEN);
        }
    }

    private boolean allowedChannel(String origin, String referer, String host) {
        if (!edgeHost.equals(host)) return false;
        if (allowedOrigin.equals(origin)) return true;
        return wechatNativeEnabled && (origin == null || origin.isEmpty()) && referer != null
                && referer.matches("https://servicewechat\\.com/" + wechatAppId + "/(?:devtools|[0-9]+)/page-frame\\.html");
    }
    public boolean isWechatNativeEnabled() { return wechatNativeEnabled; }
    public void setWechatNativeEnabled(boolean value) { wechatNativeEnabled=value; }
    public String getWechatAppId() { return wechatAppId; }
    public void setWechatAppId(String value) { wechatAppId=value; }

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
