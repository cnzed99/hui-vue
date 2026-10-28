package cn.iocoder.yudao.server.print.statistics;

import org.springframework.util.MultiValueMap;
import java.time.*;
import java.util.Set;

/** Only observed filters are accepted. Scope never comes from query parameters. */
public record PrintStatisticsQuery(String terminal, String printer, String scene,
        LocalDate startDate, LocalDate endDate, int page, int size) {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    public static PrintStatisticsQuery parse(MultiValueMap<String,String> q, boolean orders) {
        Set<String> allowed = orders ? Set.of("terminal","scene","startDate","endDate") : Set.of("terminal","printer","page","size");
        if (!allowed.containsAll(q.keySet()) || q.values().stream().anyMatch(v -> v.size()!=1)) throw invalid();
        try {
            LocalDate end=LocalDate.now(ZONE).minusDays(1), start=end.minusDays(29);
            if (orders) {
                start=LocalDate.parse(q.getFirst("startDate")==null?start.toString():q.getFirst("startDate"));
                end=LocalDate.parse(q.getFirst("endDate")==null?end.toString():q.getFirst("endDate"));
                if (start.getYear()<1970 || end.getYear()>9999 || end.isBefore(start) || end.toEpochDay()-start.toEpochDay()>365) throw invalid();
            }
            int page=Integer.parseInt(q.getFirst("page")==null?"1":q.getFirst("page"));
            int size=Integer.parseInt(q.getFirst("size")==null?"20":q.getFirst("size"));
            if(page<1 || page>1000000 || !Set.of(10,20,40,100).contains(size)) throw invalid();
            return new PrintStatisticsQuery(text(q,"terminal"),text(q,"printer"),text(q,"scene"),start,end,page,size);
        } catch (DateTimeException|NumberFormatException e) { throw invalid(); }
    }
    private static String text(MultiValueMap<String,String> q,String key) {
        String v=q.getFirst(key); v=v==null?"":v.strip();
        if(v.length()>128 || v.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return v;
    }
    public long fromMs(){return startDate.atStartOfDay(ZONE).toInstant().toEpochMilli();}
    public long untilMs(){return endDate.plusDays(1).atStartOfDay(ZONE).toInstant().toEpochMilli();}
    public long offset(){return (long)(page-1)*size;}
    public static IllegalArgumentException invalid(){return new IllegalArgumentException("统计筛选无效；日期范围最多366天，每页仅支持10/20/40/100条。");}
}
