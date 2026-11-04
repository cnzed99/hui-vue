package cn.iocoder.yudao.server.print.miniappdemo.photo;
import cn.iocoder.yudao.server.print.scope.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.*;
import java.util.*;

@Service
public final class MiniappDemoPhotoPriceService {
    public static final String KEY="MINIAPP_DEMO_PHOTO_6IN";
    private final PrintScopeService scopes;private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    public MiniappDemoPhotoPriceService(PrintScopeService scopes,JdbcTemplate jdbc,PlatformTransactionManager manager){this.scopes=scopes;this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);}
    public record View(String profileId,String profileKey,String profileVersion,String revision,String currency,String pricePerSheet,boolean quoteEnabled){}
    private record Binding(String id,long revision,boolean enabled){}
    public View get(){PrintScope scope=scopes.requireCurrent();return tx.execute(s->{lockScope(scope);return view(scope,binding(scope));});}
    public View update(JsonNode body){
        if(body==null||!body.isObject()||body.size()!=2||!body.has("expectedRevision")||!body.get("expectedRevision").isTextual()
            ||!body.has("pricePerSheet")||!body.get("pricePerSheet").isTextual())throw input();
        String expected=body.get("expectedRevision").textValue();String amount=normalizePrice(body.get("pricePerSheet").textValue());
        if(!expected.matches("[1-9][0-9]{0,17}"))throw input();
        long revision;try{revision=Long.parseLong(expected);}catch(NumberFormatException e){throw input();}
        PrintScope scope=scopes.requireCurrent();
        return tx.execute(s->{
            lockScope(scope);Binding current=binding(scope);if(current.revision!=revision)throw new MiniappDemoPhotoException(409,43101,"Photo price revision changed; refresh before retrying");
            long next=Math.addExact(revision,1);String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();
            jdbc.update("INSERT INTO print_price_profile(id,tenant_id,store_id,profile_key,version,mode,currency,simplex_price_per_sheet,duplex_price_per_sheet,tier_json,created_at_ms) VALUES(?,?,?,?,?,'DEMO','CNY',?,?,NULL,?)",id,scope.tenantId(),scope.storeId(),KEY,Long.toString(next),amount,amount,now);
            int changed=jdbc.update("UPDATE print_photo_price_binding SET active_profile_id=?,revision=?,updated_by=?,updated_at_ms=? WHERE tenant_id=? AND store_id=? AND revision=?",id,next,scope.userId(),now,scope.tenantId(),scope.storeId(),revision);
            if(changed!=1)throw new MiniappDemoPhotoException(409,43101,"Photo price revision changed; refresh before retrying");
            jdbc.update("INSERT INTO print_photo_price_event(id,tenant_id,store_id,actor_user_id,old_profile_id,new_profile_id,old_revision,new_revision,created_at_ms) VALUES(?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),scope.tenantId(),scope.storeId(),scope.userId(),current.id,id,revision,next,now);
            return view(scope,new Binding(id,next,current.enabled));
        });
    }
    public static String normalizePrice(String value){
        if(value==null||!value.matches("(0|[1-9][0-9]{0,2})(\\.[0-9]{1,2})?"))throw input();
        BigDecimal amount=new BigDecimal(value);if(amount.compareTo(new BigDecimal("0.01"))<0||amount.compareTo(new BigDecimal("999.00"))>0)throw input();
        return amount.setScale(2,RoundingMode.UNNECESSARY).toPlainString();
    }
    private void lockScope(PrintScope scope){
        var rows=jdbc.query("SELECT u.id FROM system_users u JOIN system_tenant t ON t.id=u.tenant_id JOIN print_staff_store m ON m.user_id=u.id AND m.tenant_id=u.tenant_id JOIN print_store st ON st.id=m.store_id AND st.tenant_id=m.tenant_id WHERE u.id=? AND u.tenant_id=? AND st.id=? AND u.status=0 AND u.deleted=0 AND t.status=0 AND t.deleted=0 AND t.expire_time>CURRENT_TIMESTAMP AND m.status=0 AND m.deleted=0 AND st.status=0 AND st.deleted=0 FOR UPDATE",(rs,n)->rs.getLong(1),scope.userId(),scope.tenantId(),scope.storeId());
        if(rows.size()!=1)throw unavailable();
        Long count=jdbc.queryForObject("SELECT COUNT(*) FROM print_staff_store WHERE tenant_id=? AND user_id=? AND status=0 AND deleted=0",Long.class,scope.tenantId(),scope.userId());
        if(count==null||count!=1)throw unavailable();
    }
    private Binding binding(PrintScope scope){
        var rows=jdbc.query("SELECT active_profile_id,revision,quote_enabled FROM print_photo_price_binding WHERE tenant_id=? AND store_id=? FOR UPDATE",(rs,n)->new Binding(rs.getString(1),rs.getLong(2),rs.getBoolean(3)),scope.tenantId(),scope.storeId());
        if(rows.size()!=1)throw unavailable();return rows.get(0);
    }
    private View view(PrintScope scope,Binding binding){
        var rows=jdbc.query("SELECT id,profile_key,version,currency,simplex_price_per_sheet,duplex_price_per_sheet,tier_json,mode FROM print_price_profile WHERE id=? AND tenant_id=? AND store_id=? FOR UPDATE",(rs,n)->{
            String price=rs.getString(5);
            if(!KEY.equals(rs.getString(2))||!Long.toString(binding.revision).equals(rs.getString(3))||!"CNY".equals(rs.getString(4))||!"DEMO".equals(rs.getString(8))||!price.equals(rs.getString(6))||rs.getString(7)!=null||!normalizePrice(price).equals(price))throw unavailable();
            return new View(rs.getString(1),KEY,rs.getString(3),Long.toString(binding.revision),"CNY",price,binding.enabled);
        },binding.id,scope.tenantId(),scope.storeId());if(rows.size()!=1)throw unavailable();return rows.get(0);
    }
    private static MiniappDemoPhotoException input(){return new MiniappDemoPhotoException(400,43100,"Invalid photo price input");}
    private static MiniappDemoPhotoException unavailable(){return new MiniappDemoPhotoException(503,43102,"Photo price configuration unavailable");}
}
