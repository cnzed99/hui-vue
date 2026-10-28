package cn.iocoder.yudao.server.print.store.profile;

import cn.iocoder.yudao.server.print.scope.PrintScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Optional;

@Repository
public class PrintStoreProfileRepository {
    private final JdbcTemplate jdbc;
    public PrintStoreProfileRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    /** One statement gives name, version and optional profile from the same committed snapshot. */
    public Optional<PrintStoreProfileView> current(PrintScope s) {
        return jdbc.query("SELECT s.name,s.profile_version,p.store_id,p.notice,p.province_code,p.province_name,"
                +"p.city_code,p.city_name,p.district_code,p.district_name,p.street_code,p.street_name,"
                +"p.address,p.contact,p.telephone,p.qq,p.open_time,p.close_time,p.business_open "
                +"FROM print_store s LEFT JOIN print_store_profile p ON p.tenant_id=s.tenant_id AND p.store_id=s.id "
                +"WHERE s.tenant_id=? AND s.id=? AND s.status=0 AND s.deleted=b'0'", (rs,n)-> {
                    Object business=rs.getObject("business_open");
                    return PrintStoreProfileView.of(rs.getLong("profile_version"),rs.getObject("store_id")!=null,
                            new PrintStoreProfileValue(rs.getString("name"),rs.getString("notice"),
                            rs.getString("province_code"),rs.getString("province_name"),rs.getString("city_code"),rs.getString("city_name"),
                            rs.getString("district_code"),rs.getString("district_name"),rs.getString("street_code"),rs.getString("street_name"),
                            rs.getString("address"),rs.getString("contact"),rs.getString("telephone"),rs.getString("qq"),
                            rs.getString("open_time"),rs.getString("close_time"),business==null?null:rs.getBoolean("business_open")));
                },s.tenantId(),s.storeId()).stream().findFirst();
    }
    public boolean compareAndSet(PrintScope s,PrintStoreProfileCommand command) {
        return jdbc.update("UPDATE print_store SET name=?,profile_version=profile_version+1,updater=?,update_time=CURRENT_TIMESTAMP "
                        +"WHERE tenant_id=? AND id=? AND status=0 AND deleted=b'0' AND profile_version=?",
                command.profile().storeName(),Long.toString(s.userId()),s.tenantId(),s.storeId(),command.expectedVersion())==1;
    }
    /** CAS holds the store row lock before this upsert. No second request can create with the same version. */
    public void replace(PrintScope s,PrintStoreProfileValue p) {
        String columns="notice,province_code,province_name,city_code,city_name,district_code,district_name,street_code,street_name,"
                +"address,contact,telephone,qq,open_time,close_time,business_open";
        String updates=Arrays.stream(columns.split(",")).map(c->c+"=VALUES("+c+")").collect(java.util.stream.Collectors.joining(","));
        var values=new ArrayList<Object>(); values.add(s.tenantId()); values.add(s.storeId());
        values.addAll(Arrays.asList(p.notice(),p.provinceCode(),p.provinceName(),p.cityCode(),p.cityName(),
                p.districtCode(),p.districtName(),p.streetCode(),p.streetName(),p.address(),p.contact(),p.telephone(),p.qq(),
                p.openTime(),p.closeTime(),p.businessOpen()));
        values.add(Long.toString(s.userId())); values.add(Long.toString(s.userId()));
        jdbc.update("INSERT INTO print_store_profile(tenant_id,store_id,"+columns+",creator,updater) VALUES("
                +String.join(",",java.util.Collections.nCopies(values.size(),"?"))+") ON DUPLICATE KEY UPDATE "+updates
                +",updater=VALUES(updater),update_time=CURRENT_TIMESTAMP",values.toArray());
    }
}
