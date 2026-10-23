package cn.iocoder.yudao.server.print.scope;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/** Scope error range: 1_510_000_001 through 1_510_000_099. */
public final class PrintScopeErrorCodes {
    public static final ErrorCode LOGIN_REQUIRED = new ErrorCode(1_510_000_001, "登录身份缺失或无效");
    public static final ErrorCode TENANT_REQUIRED = new ErrorCode(1_510_000_002, "租户上下文缺失");
    public static final ErrorCode SCOPE_FORBIDDEN = new ErrorCode(1_510_000_003, "当前员工无权访问此门店范围");
    public static final ErrorCode STORE_NOT_ASSIGNED = new ErrorCode(1_510_000_004, "当前员工尚未分配门店");
    public static final ErrorCode STORE_AMBIGUOUS = new ErrorCode(1_510_000_005, "存在多个有效门店映射，无法确定当前门店");
    public static final ErrorCode ACTOR_NOT_IMPLEMENTED = new ErrorCode(1_510_000_006, "当前访问主体类型暂未实现");
    public static final ErrorCode STORE_UNAVAILABLE = new ErrorCode(1_510_000_007, "当前门店不存在或已停用");

    private PrintScopeErrorCodes() {
    }
}
