package cn.iocoder.yudao.server.print.order;

import cn.iocoder.yudao.server.print.scope.PrintScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.util.LinkedMultiValueMap;
import java.util.Arrays;
import java.util.List;

/** One focused request-to-SQL contract check, without a database or live service. */
public final class PrintOrderQueryFocusedCheck {
    private static final class RecordingJdbc extends JdbcTemplate {
        String countSql,listSql;Object[] countArgs,listArgs;
        @Override public <T> T queryForObject(String sql,Class<T> type,Object...args){countSql=sql;countArgs=args;return type.cast(0L);}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object...args){listSql=sql;listArgs=args;return List.of();}
    }
    public static void main(String[] ignored){
        var params=new LinkedMultiValueMap<String,String>();
        params.add("pageNo","2");params.add("pageSize","10");params.add("orderNo","PD123");
        params.add("createdFromMs","1000");params.add("createdToMs","2000");
        params.add("orderState","OPEN");params.add("paymentState","UNPAID");params.add("refundState","NONE");params.add("printState","NOT_CREATED");
        var query=PrintOrderQuery.parse(params);var jdbc=new RecordingJdbc();var repository=new PrintOrderRepository(jdbc);
        repository.count(new PrintScope(11L,22L,33L),query);repository.list(new PrintScope(11L,22L,33L),query);
        String expected="tenant_id=? AND store_id=? AND owner_user_id=? AND order_no=? AND created_at_ms>=? AND created_at_ms<=? AND order_state=? AND payment_state=? AND refund_state=? AND print_state=?";
        check(jdbc.countSql.equals("SELECT COUNT(*) FROM print_order WHERE "+expected),"count filters");
        check(jdbc.listSql.contains(" WHERE "+expected+" ORDER BY created_at_ms DESC,id DESC LIMIT ? OFFSET ?"),"list filters and stable pagination");
        Object[] expectedArgs={11L,22L,33L,"PD123",1000L,2000L,"OPEN","UNPAID","NONE","NOT_CREATED"};
        check(Arrays.equals(expectedArgs,jdbc.countArgs),"parameterized filters preserve server owner");
        check(Arrays.equals(expectedArgs,Arrays.copyOf(jdbc.listArgs,10)) && jdbc.listArgs[10].equals(10) && jdbc.listArgs[11].equals(10L),"count and list share arguments");
        check(PrintOrderQuery.parse(new LinkedMultiValueMap<>()).pageSize()==20,"legacy pagination defaults");
        params.set("createdFromMs","2001");expectInvalid(params,"inverted time range");
        params.set("createdFromMs","1000");params.add("ownerUserId","999");expectInvalid(params,"client identity remains rejected");
        System.out.println("P22_QUERY_FOCUSED_CHECK_PASS: combined filters, stable paging, invalid time, client identity rejection");
    }
    private static void expectInvalid(LinkedMultiValueMap<String,String> params,String name){
        try{PrintOrderQuery.parse(params);throw new AssertionError(name);}catch(PrintOrderException expected){check(expected.status().value()==400,name);}
    }
    private static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
}
