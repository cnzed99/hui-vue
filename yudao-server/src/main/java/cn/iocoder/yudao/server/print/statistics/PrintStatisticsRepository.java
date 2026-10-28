package cn.iocoder.yudao.server.print.statistics;

import cn.iocoder.yudao.server.print.scope.PrintScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

/** Read-only reporting. Existing order owner scope is preserved. No payment state interpretation. */
@Repository
public class PrintStatisticsRepository {
    private final JdbcTemplate jdbc;
    public PrintStatisticsRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public record OrderPoint(String day,String terminal,String scene,String currency,boolean simulation,
            String orders,String amountSnapshot,String invalidAmountCount) { }
    public record PaperRow(String id,String terminal,String printer,String paper,String remaining,String warning,
            String total,String simplex,String duplex,String day,String updatedAt,boolean simulation) { }
    public record Page(List<PaperRow> list,String total,int page,int size) { }
    public List<OrderPoint> orders(PrintScope s,PrintStatisticsQuery q){
        if(!q.terminal().isEmpty() && !"未关联终端".contains(q.terminal())) return List.of();
        var args=new ArrayList<Object>(List.of(s.tenantId(),s.storeId(),s.userId(),q.fromMs(),q.untilMs()));
        String where="tenant_id=? AND store_id=? AND owner_user_id=? AND created_at_ms>=? AND created_at_ms<?";
        if(!q.scene().isEmpty()){where+=" AND source=?";args.add(q.scene());}
        // Only exact representable decimal snapshots participate; invalid groups are explicit, never truncated.
        String valid="total_amount REGEXP '^[0-9]{1,38}([.][0-9]{1,18})?$'";
        String sql="SELECT DATE_FORMAT(DATE_ADD('1970-01-01', INTERVAL FLOOR((created_at_ms+28800000)/86400000) DAY),'%Y-%m-%d') day,"
                +"source,currency,simulation,COUNT(*) orders,SUM(CASE WHEN "+valid+" THEN 0 ELSE 1 END) invalid_amount_count,"
                +"CASE WHEN SUM(CASE WHEN "+valid+" THEN 0 ELSE 1 END)=0 THEN "
                +"CAST(SUM(CASE WHEN "+valid+" THEN CAST(total_amount AS DECIMAL(65,18)) ELSE NULL END) AS CHAR) ELSE NULL END amount_snapshot "
                +"FROM print_order WHERE "+where+" GROUP BY day,source,currency,simulation ORDER BY day,source,currency,simulation LIMIT 1001";
        var result=jdbc.query(sql,(rs,n)->new OrderPoint(rs.getString("day"),"未关联终端",rs.getString("source"),rs.getString("currency"),
                rs.getBoolean("simulation"),rs.getString("orders"),rs.getString("amount_snapshot"),rs.getString("invalid_amount_count")),args.toArray());
        if(result.size()>1000) throw new IllegalArgumentException("查询分组超过1000条，请缩小日期或场景范围。");
        return result;
    }
    public List<String> scenes(PrintScope s){
        return jdbc.query("SELECT DISTINCT source FROM print_order WHERE tenant_id=? AND store_id=? AND owner_user_id=? ORDER BY source LIMIT 101",
                (rs,n)->rs.getString(1),s.tenantId(),s.storeId(),s.userId());
    }
    public Page paper(PrintScope s,PrintStatisticsQuery q,boolean daily){
        // Table identifiers are fixed literals, not user input. Values use JDBC parameters throughout.
        String table=daily?"print_paper_daily_report":"print_paper_total_report";
        String where="tenant_id=? AND store_id=?";
        var args=new ArrayList<Object>(List.of(s.tenantId(),s.storeId()));
        if(!q.terminal().isEmpty()){where+=" AND terminal_name LIKE ? ESCAPE '!'";args.add(like(q.terminal()));}
        if(!q.printer().isEmpty()){where+=" AND printer_name LIKE ? ESCAPE '!'";args.add(like(q.printer()));}
        String total=jdbc.queryForObject("SELECT CAST(COUNT(*) AS CHAR) FROM "+table+" WHERE "+where,String.class,args.toArray());
        String fields=daily?"NULL remaining_sheets,NULL warning_sheets,total_sheets,simplex_sheets,duplex_sheets,CAST(stat_day AS CHAR) stat_day":
                "remaining_sheets,warning_sheets,total_sheets,NULL simplex_sheets,NULL duplex_sheets,NULL stat_day";
        args.add(q.size());args.add(q.offset());
        var rows=jdbc.query("SELECT id,terminal_name,printer_name,paper_spec,"+fields+",CAST(updated_at AS CHAR) updated_at,simulation FROM "+table+" WHERE "+where
                        +" ORDER BY "+(daily?"stat_day DESC,":"")+"updated_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs,n)->new PaperRow(rs.getString("id"),rs.getString("terminal_name"),rs.getString("printer_name"),rs.getString("paper_spec"),
                        rs.getString("remaining_sheets"),rs.getString("warning_sheets"),rs.getString("total_sheets"),rs.getString("simplex_sheets"),
                        rs.getString("duplex_sheets"),rs.getString("stat_day"),rs.getString("updated_at"),rs.getBoolean("simulation")),args.toArray());
        return new Page(rows,total,q.page(),q.size());
    }
    private static String like(String v){return "%"+v.replace("!","!!").replace("%","!%").replace("_","!_")+"%";}
}
