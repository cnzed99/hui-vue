package cn.iocoder.yudao.server.print.directory;
import cn.iocoder.yudao.server.print.scope.PrintScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import static cn.iocoder.yudao.server.print.directory.PrintDirectoryModel.*;
@Repository
public class PrintDirectoryRepository {
    private final JdbcTemplate jdbc;
    public PrintDirectoryRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static String table(Kind kind){return switch(kind){case MY,PLATFORM->"print_product_catalog_entry";case LIBRARY->"print_library_catalog_entry";case TERMINAL->"print_terminal_directory";};}
    public Page list(PrintScope scope,Kind kind,PrintDirectoryQuery q){
        String from=" FROM "+table(kind)+" d JOIN print_store s ON s.tenant_id=d.tenant_id AND s.id=d.store_id WHERE d.tenant_id=? AND d.store_id=? AND d.owner_user_id=? AND s.status=0 AND s.deleted=b'0'";
        List<Object> args=new ArrayList<>(List.of(scope.tenantId(),scope.storeId(),scope.userId()));
        if(kind==Kind.MY||kind==Kind.PLATFORM){from+=" AND d.catalog_kind=?";args.add(kind==Kind.MY?"MY":"PLATFORM");}
        String categoryFrom=from;Object[] categoryArgs=args.toArray();
        if(!q.name().isEmpty()){from+=" AND d."+(kind==Kind.LIBRARY?"file_name":"name")+" LIKE ? ESCAPE '!'";args.add("%"+PrintDirectoryQuery.literalLike(q.name())+"%");}
        if(kind!=Kind.TERMINAL&&!q.category().isEmpty()){from+=" AND (d.category_path=? OR d.category_path LIKE ? ESCAPE '!')";args.add(q.category());args.add(PrintDirectoryQuery.literalLike(q.category())+"/%");}
        if(kind==Kind.LIBRARY){
            if(!q.audit().equals("ALL")){from+=" AND d.audit_status=?";args.add(q.audit());}
            if(!q.visibility().equals("ALL")){from+=" AND d.visibility=?";args.add(q.visibility());}
        }
        if(kind==Kind.TERMINAL&&!q.host().isEmpty()){from+=" AND d.host_name LIKE ? ESCAPE '!'";args.add("%"+PrintDirectoryQuery.literalLike(q.host())+"%");}
        Long count=jdbc.queryForObject("SELECT COUNT(*)"+from,Long.class,args.toArray());
        String order=kind==Kind.LIBRARY?switch(q.sort()){case "PRINT_DESC"->"d.print_count DESC";case "VIEW_DESC"->"d.view_count DESC";case "FAVORITE_DESC"->"d.favorite_count DESC";case "PRICE_DESC"->"d.price DESC";case "PRICE_ASC"->"d.price ASC";default->"d.uploaded_at DESC";}:kind==Kind.TERMINAL?"d.updated_at DESC":"d.code ASC";
        List<Object> paging=new ArrayList<>(args);paging.add(q.pageSize());paging.add((q.page()-1L)*q.pageSize());
        String columns=switch(kind){case MY,PLATFORM->"d.id,d.code,d.name,d.category_path,d.unit_price,d.currency,d.price_description,d.catalog_kind";case LIBRARY->"d.id,d.library_store_name,d.file_name,d.category_path,d.visibility,d.audit_status,d.price,d.currency,d.pages,d.print_count,d.view_count,d.favorite_count,d.download_count,d.uploaded_at";case TERMINAL->"d.id,d.name,d.host_name,d.operating_system,d.client_version,d.ip_address,d.mobile_self_service,d.enabled,d.updated_at";};
        List<?> rows=jdbc.query("SELECT "+columns+from+" ORDER BY "+order+",d.id LIMIT ? OFFSET ?",(rs,n)->switch(kind){
            case MY,PLATFORM->new Product(rs.getString("id"),rs.getString("code"),rs.getString("name"),rs.getString("category_path"),rs.getString("unit_price"),rs.getString("currency"),rs.getString("price_description"),rs.getString("catalog_kind"));
            case LIBRARY->new LibraryEntry(rs.getString("id"),rs.getString("library_store_name"),rs.getString("file_name"),rs.getString("category_path"),rs.getString("visibility"),rs.getString("audit_status"),rs.getString("price"),rs.getString("currency"),(Integer)rs.getObject("pages"),rs.getString("print_count"),rs.getString("view_count"),rs.getString("favorite_count"),rs.getString("download_count"),rs.getString("uploaded_at"));
            case TERMINAL->new Terminal(rs.getString("id"),rs.getString("name"),rs.getString("host_name"),rs.getString("operating_system"),rs.getString("client_version"),rs.getString("ip_address"),nullableBoolean(rs,"mobile_self_service"),nullableBoolean(rs,"enabled"),rs.getString("updated_at"),"UNKNOWN");
        },paging.toArray());
        List<String> categories=kind==Kind.TERMINAL?List.of():jdbc.queryForList("SELECT DISTINCT d.category_path"+categoryFrom+" AND d.category_path<>'' ORDER BY d.category_path",String.class,categoryArgs);
        return new Page(rows,count==null?0:count,q.page(),q.pageSize(),categories,kind==Kind.TERMINAL?"METADATA_ONLY_HEARTBEAT_NOT_CONNECTED":"CURRENT_ACCOUNT_CATALOG_METADATA");
    }
    private static Boolean nullableBoolean(java.sql.ResultSet rs,String column)throws java.sql.SQLException{boolean v=rs.getBoolean(column);return rs.wasNull()?null:v;}
}
