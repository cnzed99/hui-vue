package cn.iocoder.yudao.server.print.order;
import java.util.List;
public record PrintOrderPage<T>(List<T> list,long total,int pageNo,int pageSize) { }
