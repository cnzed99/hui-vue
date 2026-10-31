package cn.iocoder.yudao.server.print.miniappdemo;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoSessionException.Reason.*;

@Service
public class MiniappDemoSessionService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final MiniappDemoSessionProperties properties;
    private final MiniappDemoSessionRepository repository;
    private final TransactionTemplate transaction;

    public MiniappDemoSessionService(MiniappDemoSessionProperties properties, MiniappDemoSessionRepository repository,
            PlatformTransactionManager manager) {
        this.properties = properties;
        this.repository = repository;
        this.transaction = new TransactionTemplate(manager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public IssuedSession issue(String origin, String host) {
        properties.requireIssuanceRequest(origin, host);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String principalId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        return transaction.execute(status -> {
            long tenantId = properties.getTenantId();
            long storeId = properties.getStoreId();
            if (!repository.lockActiveTenantAndStore(tenantId, storeId)) throw new MiniappDemoSessionException(STORE_UNAVAILABLE);
            long now = System.currentTimeMillis();
            long expiresAt = Math.addExact(now, properties.getTtlSeconds() * 1000);
            repository.expire(tenantId, storeId, now);
            if (repository.countActive(tenantId, storeId, now) >= properties.getMaxActiveSessions()
                    || repository.countIssuedSince(tenantId, storeId, now - 60_000) >= properties.getMaxIssuePerMinute()) {
                throw new MiniappDemoSessionException(RATE_LIMITED);
            }
            repository.insert(principalId, sessionId, tenantId, storeId, hash(token), now, expiresAt);
            return new IssuedSession(token, sessionId, principalId, expiresAt, "MINIAPP_DEMO");
        });
    }

    public MiniappDemoIdentity require(String authorization, String origin, String host) {
        properties.requireSessionRequest(origin, host);
        if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9_-]{43}")) {
            throw new MiniappDemoSessionException(SESSION_INVALID);
        }
        String token = authorization.substring(7);
        long now = System.currentTimeMillis();
        return repository.findActive(hash(token), properties.getTenantId(), properties.getStoreId(), now)
                .orElseThrow(() -> new MiniappDemoSessionException(SESSION_INVALID));
    }

    public MiniappDemoIdentity requireForWrite(String authorization, String origin, String host) {
        properties.requireWriteRequest(origin, host);
        if (authorization == null || !authorization.matches("Bearer [A-Za-z0-9_-]{43}")) {
            throw new MiniappDemoSessionException(SESSION_INVALID);
        }
        String token = authorization.substring(7);
        long now = System.currentTimeMillis();
        return repository.findActive(hash(token), properties.getTenantId(), properties.getStoreId(), now)
                .orElseThrow(() -> new MiniappDemoSessionException(SESSION_INVALID));
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public record IssuedSession(String accessToken, String sessionId, String principalId, long expiresAtMs, String actorType) {}
}
