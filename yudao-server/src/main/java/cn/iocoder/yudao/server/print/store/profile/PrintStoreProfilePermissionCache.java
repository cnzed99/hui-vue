package cn.iocoder.yudao.server.print.store.profile;

import cn.iocoder.yudao.module.system.dal.redis.RedisKeyConstants;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.Objects;

/** Internal post-commit bridge for the future reviewed migration runner. No public HTTP route. */
@Component
public class PrintStoreProfilePermissionCache {
    public static final long QUERY_MENU_ID=2010L, UPDATE_MENU_ID=2011L;
    private final CacheManager manager;
    public PrintStoreProfilePermissionCache(CacheManager manager) { this.manager=manager; }
    public void evictAfterCommittedPermissionMigration(long tenantId) {
        if (tenantId<=0 || TenantContextHolder.isIgnore() || !Objects.equals(tenantId,TenantContextHolder.getTenantId()))
            throw new IllegalStateException("permission_cache_requires_explicit_target_tenant");
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("permission_cache_requires_committed_migration");
        Cache permissions=Objects.requireNonNull(manager.getCache(RedisKeyConstants.PERMISSION_MENU_ID_LIST));
        Cache roles=Objects.requireNonNull(manager.getCache(RedisKeyConstants.MENU_ROLE_ID_LIST));
        // The actual manager applies tenant/key serialization. Never concatenate physical Redis keys.
        permissions.evict("print:store:query"); permissions.evict("print:store:update");
        roles.evict(QUERY_MENU_ID); roles.evict(UPDATE_MENU_ID);
    }
}
