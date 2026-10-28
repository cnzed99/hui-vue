package cn.iocoder.yudao.server.print.advertising;
import cn.iocoder.yudao.server.print.scope.PrintScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
@Repository
public class PrintAdvertisingRepository {
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    public PrintAdvertisingRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public Optional<PrintAdvertisingDraft> current(PrintScope s,PrintAdPosition p){
        return jdbc.query("SELECT a.version,a.success_message_html,a.assets_json FROM print_store s LEFT JOIN print_advertising_draft a ON a.tenant_id=s.tenant_id AND a.store_id=s.id AND a.position=? WHERE s.tenant_id=? AND s.id=? AND s.status=0 AND s.deleted=b'0'",
            (rs,n)->{
                if(rs.getObject("version")==null)return PrintAdvertisingDraft.empty(p);
                try{return new PrintAdvertisingDraft(p.name(),Long.toString(rs.getLong("version")),true,false,rs.getString("success_message_html"),
                    List.of(json.readValue(rs.getString("assets_json"),PrintAdvertisingDraft.Asset[].class)),"NOT_CONNECTED");}
                catch(java.io.IOException e){throw new PrintAdvertisingException(PrintAdvertisingException.Reason.STORAGE);}
            },p.name(),s.tenantId(),s.storeId()).stream().findFirst();
    }
    public boolean lockStore(PrintScope s){return jdbc.query("SELECT id FROM print_store WHERE tenant_id=? AND id=? AND status=0 AND deleted=b'0' FOR UPDATE",(rs,n)->rs.getLong(1),s.tenantId(),s.storeId()).size()==1;}
    /** Existing infra files are global. Admit only current user's already persisted image metadata; never fetch remote URLs. */
    public boolean existingImage(PrintScope s,PrintAdPosition p,String url){
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM infra_file WHERE url=? AND creator=? AND deleted=b'0' AND type LIKE 'image/%' AND size>0"+(p==PrintAdPosition.ORDER_PAGE?" AND size<=1000000":""),Long.class,url,Long.toString(s.userId()));
        return count!=null&&count==1;
    }
    public boolean replace(PrintScope s,PrintAdPosition p,PrintAdvertisingCommand c){
        String assets;try{assets=json.writeValueAsString(c.assets());}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new PrintAdvertisingException(PrintAdvertisingException.Reason.INVALID);}
        if(c.expectedVersion()==0)return jdbc.update("INSERT INTO print_advertising_draft(tenant_id,store_id,position,version,success_message_html,assets_json,creator,updater) VALUES(?,?,?,1,?,?,?,?)",s.tenantId(),s.storeId(),p.name(),c.successMessageHtml(),assets,Long.toString(s.userId()),Long.toString(s.userId()))==1;
        return jdbc.update("UPDATE print_advertising_draft SET version=version+1,success_message_html=?,assets_json=?,updater=?,update_time=CURRENT_TIMESTAMP WHERE tenant_id=? AND store_id=? AND position=? AND version=?",c.successMessageHtml(),assets,Long.toString(s.userId()),s.tenantId(),s.storeId(),p.name(),c.expectedVersion())==1;
    }
}
