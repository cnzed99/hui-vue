package cn.iocoder.yudao.server.print.directory;
import java.util.*;
import static cn.iocoder.yudao.server.print.directory.PrintDirectoryModel.*;
/** One lightweight pure query/empty-state check group; no DB or service bootstrap. */
public class PrintDirectoryChecks {
    public static void main(String[] args){
        var defaults=PrintDirectoryQuery.parse(Kind.MY,Map.of());check(defaults.page()==1&&defaults.pageSize()==10);
        var q=PrintDirectoryQuery.parse(Kind.LIBRARY,Map.of("sort",List.of("PRICE_ASC"),"category",List.of("资格考试/外语类"),"page",List.of("2")));
        check(q.sort().equals("PRICE_ASC")&&q.page()==2);
        invalid(()->PrintDirectoryQuery.parse(Kind.MY,Map.of("tenantId",List.of("2"))));
        invalid(()->PrintDirectoryQuery.parse(Kind.TERMINAL,Map.of("name",List.of("a","b"))));
        invalid(()->PrintDirectoryQuery.parse(Kind.LIBRARY,Map.of("sort",List.of("price;DROP"))));
        check(PrintDirectoryQuery.literalLike("a%_!").equals("a!%!_!!"));
        var empty=new Page(List.of(),0,1,10,List.of(),"CURRENT_ACCOUNT_CATALOG_METADATA");check(empty.list().isEmpty()&&empty.total()==0);
        System.out.println("PASS_ONE_QUERY_EMPTY_GROUP");
    }
    private static void check(boolean value){if(!value)throw new AssertionError();}
    private static void invalid(Runnable task){try{task.run();throw new AssertionError();}catch(PrintDirectoryException expected){check(expected.status==400);}}
}
