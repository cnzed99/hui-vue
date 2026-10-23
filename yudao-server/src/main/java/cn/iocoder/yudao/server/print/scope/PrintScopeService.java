package cn.iocoder.yudao.server.print.scope;

import cn.iocoder.yudao.framework.common.enums.UserTypeEnum;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** Resolves the current staff scope from authentication and live database mappings. */
@Service
public class PrintScopeService {
    private final JdbcTemplate jdbcTemplate;

    public PrintScopeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PrintScope requireCurrent() {
        Authentication authentication = SecurityFrameworkUtils.getAuthentication();
        LoginUser actor = SecurityFrameworkUtils.getLoginUser();
        if (authentication == null || !authentication.isAuthenticated() || actor == null
                || actor.getId() == null || actor.getId() <= 0) {
            throw new ServiceException(PrintScopeErrorCodes.LOGIN_REQUIRED);
        }
        if (!Objects.equals(actor.getUserType(), UserTypeEnum.ADMIN.getValue())) {
            throw new ServiceException(PrintScopeErrorCodes.ACTOR_NOT_IMPLEMENTED);
        }
        Long tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null || tenantId <= 0) {
            throw new ServiceException(PrintScopeErrorCodes.TENANT_REQUIRED);
        }
        // A tenant header or cross-tenant visit must never override this staff's token owner.
        if (TenantContextHolder.isIgnore() || !Objects.equals(tenantId, actor.getTenantId())
                || (actor.getVisitTenantId() != null && !Objects.equals(tenantId, actor.getVisitTenantId()))) {
            throw new ServiceException(PrintScopeErrorCodes.SCOPE_FORBIDDEN);
        }
        long userId = actor.getId();
        List<Integer> userStatuses = jdbcTemplate.query(
                "SELECT status FROM system_users WHERE tenant_id = ? AND id = ? AND deleted = b'0'",
                (rs, rowNum) -> rs.getInt("status"), tenantId, userId);
        if (userStatuses.size() != 1 || userStatuses.get(0) != 0) {
            throw new ServiceException(PrintScopeErrorCodes.SCOPE_FORBIDDEN);
        }
        List<Long> stores = jdbcTemplate.query(
                "SELECT store_id FROM print_staff_store WHERE tenant_id = ? AND user_id = ? "
                        + "AND status = 0 AND deleted = b'0' ORDER BY store_id LIMIT 2",
                (rs, rowNum) -> rs.getLong("store_id"), tenantId, userId);
        if (stores.isEmpty()) {
            Integer disabled = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM print_staff_store WHERE tenant_id = ? AND user_id = ? AND deleted = b'0'",
                    Integer.class, tenantId, userId);
            throw new ServiceException(disabled != null && disabled > 0
                    ? PrintScopeErrorCodes.SCOPE_FORBIDDEN : PrintScopeErrorCodes.STORE_NOT_ASSIGNED);
        }
        if (stores.size() != 1) {
            throw new ServiceException(PrintScopeErrorCodes.STORE_AMBIGUOUS);
        }
        long storeId = stores.get(0);
        requireActiveStoreName(tenantId, storeId);
        return new PrintScope(tenantId, storeId, userId);
    }

    public PrintContext currentContext() {
        PrintScope scope = requireCurrent();
        return new PrintContext(scope.tenantId(), scope.storeId(), scope.userId(),
                requireActiveStoreName(scope.tenantId(), scope.storeId()), PrintActorType.STAFF.name());
    }

    private String requireActiveStoreName(long tenantId, long storeId) {
        List<String> names = jdbcTemplate.query(
                "SELECT name FROM print_store WHERE tenant_id = ? AND id = ? AND status = 0 AND deleted = b'0'",
                (rs, rowNum) -> rs.getString("name"), tenantId, storeId);
        if (names.size() != 1) {
            throw new ServiceException(PrintScopeErrorCodes.STORE_UNAVAILABLE);
        }
        return names.get(0);
    }
}
