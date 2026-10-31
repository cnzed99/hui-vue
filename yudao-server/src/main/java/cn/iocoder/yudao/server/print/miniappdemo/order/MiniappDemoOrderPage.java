package cn.iocoder.yudao.server.print.miniappdemo.order;

import java.util.List;

public record MiniappDemoOrderPage(List<MiniappDemoOrderView> list, int pageNo, int pageSize,
                                   long total, boolean hasMore) {
}
