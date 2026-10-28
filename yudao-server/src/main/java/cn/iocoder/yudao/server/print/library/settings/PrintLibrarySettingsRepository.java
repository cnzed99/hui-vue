package cn.iocoder.yudao.server.print.library.settings;

import cn.iocoder.yudao.server.print.scope.PrintScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class PrintLibrarySettingsRepository {
    private final JdbcTemplate jdbc;
    public PrintLibrarySettingsRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<PrintLibrarySettings> current(PrintScope s){
        return jdbc.query("SELECT p.version,p.name,p.description FROM print_store s LEFT JOIN print_library_settings p "
                +"ON p.tenant_id=s.tenant_id AND p.store_id=s.id WHERE s.tenant_id=? AND s.id=? AND s.status=0 AND s.deleted=b'0'",
                (rs,n)->rs.getObject("version")==null?PrintLibrarySettings.of(0,false,"",""):
                    PrintLibrarySettings.of(rs.getLong("version"),true,rs.getString("name"),rs.getString("description")),
                s.tenantId(),s.storeId()).stream().findFirst();
    }
    /** Parent row lock serializes first creation and keeps store admission stable through commit. */
    public boolean lockCurrentStore(PrintScope s){
        return jdbc.query("SELECT id FROM print_store WHERE tenant_id=? AND id=? AND status=0 AND deleted=b'0' FOR UPDATE",
                (rs,n)->rs.getLong("id"),s.tenantId(),s.storeId()).size()==1;
    }
    public boolean replace(PrintScope s,PrintLibrarySettingsCommand c,boolean configured){
        if(!configured){
            if(c.expectedVersion()!=0)return false;
            return jdbc.update("INSERT INTO print_library_settings(tenant_id,store_id,version,name,description,creator,updater) VALUES(?,?,1,?,?,?,?)",
                    s.tenantId(),s.storeId(),c.name(),c.description(),Long.toString(s.userId()),Long.toString(s.userId()))==1;
        }
        return jdbc.update("UPDATE print_library_settings SET version=version+1,name=?,description=?,updater=?,update_time=CURRENT_TIMESTAMP "
                +"WHERE tenant_id=? AND store_id=? AND version=?",c.name(),c.description(),Long.toString(s.userId()),
                s.tenantId(),s.storeId(),c.expectedVersion())==1;
    }
}
