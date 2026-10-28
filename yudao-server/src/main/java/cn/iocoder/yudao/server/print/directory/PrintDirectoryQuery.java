package cn.iocoder.yudao.server.print.directory;
import java.util.*;
import static cn.iocoder.yudao.server.print.directory.PrintDirectoryModel.Kind;
/** Explicit keys only; tenant/store/account can never be supplied by the caller. */
public record PrintDirectoryQuery(int page,int pageSize,String name,String category,String audit,String visibility,String sort,String host,String online) {
    public static PrintDirectoryQuery parse(Kind kind,Map<String,List<String>> q){
        Set<String> allowed=new HashSet<>(Set.of("page","pageSize","name"));
        if(kind==Kind.TERMINAL)allowed.addAll(Set.of("host","online"));
        else allowed.add("category");
        if(kind==Kind.LIBRARY)allowed.addAll(Set.of("audit","visibility","sort"));
        if(!allowed.containsAll(q.keySet()))throw PrintDirectoryException.invalid();
        int page=number(q,"page",1,1000000),size=number(q,"pageSize",10,100);
        String name=text(q,"name","",100),category=text(q,"category","",512),host=text(q,"host","",128);
        String audit=choice(q,"audit","ALL",Set.of("ALL","PENDING","APPROVED","REJECTED"));
        String visibility=choice(q,"visibility","ALL",Set.of("ALL","PUBLIC","STORE_USERS","STORE_STAFF"));
        String sort=choice(q,"sort","UPLOADED_DESC",Set.of("UPLOADED_DESC","PRINT_DESC","VIEW_DESC","FAVORITE_DESC","PRICE_DESC","PRICE_ASC"));
        String online=choice(q,"online","ALL",Set.of("ALL","ONLINE","OFFLINE","UNKNOWN"));
        return new PrintDirectoryQuery(page,size,name,category,audit,visibility,sort,host,online);
    }
    private static int number(Map<String,List<String>> q,String k,int fallback,int max){String v=text(q,k,Integer.toString(fallback),7);if(!v.matches("[1-9][0-9]*"))throw PrintDirectoryException.invalid();int n=Integer.parseInt(v);if(n>max)throw PrintDirectoryException.invalid();return n;}
    private static String text(Map<String,List<String>> q,String k,String fallback,int max){if(!q.containsKey(k))return fallback;List<String> values=q.get(k);if(values==null||values.size()!=1||values.get(0)==null)throw PrintDirectoryException.invalid();String v=values.get(0).strip();if(v.length()>max||v.codePoints().anyMatch(Character::isISOControl))throw PrintDirectoryException.invalid();return v;}
    private static String choice(Map<String,List<String>> q,String k,String fallback,Set<String> values){String v=text(q,k,fallback,32);if(!values.contains(v))throw PrintDirectoryException.invalid();return v;}
    public static String literalLike(String value){return value.replace("!","!!").replace("%","!%").replace("_","!_");}
}
