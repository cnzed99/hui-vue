package cn.iocoder.yudao.server.print.library.category;

import cn.iocoder.yudao.server.print.scope.PrintScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;

@Repository
public class PrintLibraryCategoryRepository {
    private final JdbcTemplate jdbc;
    public PrintLibraryCategoryRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static final String ACTIVE=" FROM print_library_category c JOIN print_store s ON s.tenant_id=c.tenant_id AND s.id=c.store_id WHERE c.tenant_id=? AND c.store_id=? AND s.status=0 AND s.deleted=b'0'";
    private static PrintLibraryCategory row(java.sql.ResultSet rs)throws java.sql.SQLException{
        return new PrintLibraryCategory(rs.getString("id"),Long.toString(rs.getLong("version")),rs.getString("name"),null,null);
    }
    public List<PrintLibraryCategory> list(PrintScope s,int page,int size){return jdbc.query("SELECT c.id,c.version,c.name"+ACTIVE+" ORDER BY c.create_time,c.id LIMIT ? OFFSET ?",(rs,n)->row(rs),s.tenantId(),s.storeId(),size,(page-1L)*size);}
    public long count(PrintScope s){Long n=jdbc.queryForObject("SELECT COUNT(*)"+ACTIVE,Long.class,s.tenantId(),s.storeId());return n==null?0:n;}
    public Optional<PrintLibraryCategory> get(PrintScope s,String id){return jdbc.query("SELECT c.id,c.version,c.name"+ACTIVE+" AND c.id=?",(rs,n)->row(rs),s.tenantId(),s.storeId(),id).stream().findFirst();}
    /** Reuses the established settings write admission: lock the current active store. */
    public boolean lockCurrentStore(PrintScope s){return jdbc.query("SELECT id FROM print_store WHERE tenant_id=? AND id=? AND status=0 AND deleted=b'0' FOR UPDATE",(rs,n)->rs.getLong("id"),s.tenantId(),s.storeId()).size()==1;}
    public void create(PrintScope s,PrintLibraryCategoryCommand c){jdbc.update("INSERT INTO print_library_category(tenant_id,store_id,id,version,name,creator,updater) VALUES(?,?,?,1,?,?,?)",s.tenantId(),s.storeId(),c.requestId(),c.name(),Long.toString(s.userId()),Long.toString(s.userId()));}
    public boolean update(PrintScope s,String id,PrintLibraryCategoryCommand c){return jdbc.update("UPDATE print_library_category SET name=?,version=version+1,updater=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND store_id=? AND id=? AND version=?",c.name(),Long.toString(s.userId()),s.tenantId(),s.storeId(),id,c.expectedVersion())==1;}
}
