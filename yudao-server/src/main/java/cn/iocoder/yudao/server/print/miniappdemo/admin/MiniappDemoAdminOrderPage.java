package cn.iocoder.yudao.server.print.miniappdemo.admin;

import java.util.List;

public record MiniappDemoAdminOrderPage<T>(List<T> list, long total, int pageNo, int pageSize) {}
