package cn.iocoder.yudao.server.print.reservation;

import cn.iocoder.yudao.server.print.scope.PrintScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class PrintReservationSettingsRepository {
    private final JdbcTemplate jdbc;
    public PrintReservationSettingsRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<PrintReservationSettings> current(PrintScope s) {
        return jdbc.query("SELECT p.version,p.pickup_enabled,p.timed_delivery_enabled,p.vip_delivery_enabled,p.pickup_code_prefix,p.automatic_print_enabled "
                +"FROM print_store s LEFT JOIN print_reservation_settings p ON p.tenant_id=s.tenant_id AND p.store_id=s.id "
                +"WHERE s.tenant_id=? AND s.id=? AND s.status=0 AND s.deleted=b'0'",
                (rs,n)->rs.getObject("version")==null ? PrintReservationSettings.unconfigured() : PrintReservationSettings.of(
                        rs.getLong("version"),true,rs.getBoolean("pickup_enabled"),rs.getBoolean("timed_delivery_enabled"),
                        rs.getBoolean("vip_delivery_enabled"),rs.getString("pickup_code_prefix"),rs.getBoolean("automatic_print_enabled")),
                s.tenantId(),s.storeId()).stream().findFirst();
    }
    public boolean lockCurrentStore(PrintScope s) {
        return jdbc.query("SELECT id FROM print_store WHERE tenant_id=? AND id=? AND status=0 AND deleted=b'0' FOR UPDATE",
                (rs,n)->rs.getLong("id"),s.tenantId(),s.storeId()).size()==1;
    }
    public boolean replace(PrintScope s, PrintReservationSettingsCommand c, boolean configured) {
        if (!configured) {
            if (c.expectedVersion()!=0) return false;
            return jdbc.update("INSERT INTO print_reservation_settings(tenant_id,store_id,version,pickup_enabled,timed_delivery_enabled,"
                    +"vip_delivery_enabled,pickup_code_prefix,automatic_print_enabled,creator,updater) VALUES(?,?,1,?,?,?,?,?,?,?)",
                    s.tenantId(),s.storeId(),c.pickupEnabled(),c.timedDeliveryEnabled(),c.vipDeliveryEnabled(),c.pickupCodePrefix(),
                    c.automaticPrintEnabled(),Long.toString(s.userId()),Long.toString(s.userId()))==1;
        }
        return jdbc.update("UPDATE print_reservation_settings SET version=version+1,pickup_enabled=?,timed_delivery_enabled=?,"
                +"vip_delivery_enabled=?,pickup_code_prefix=?,automatic_print_enabled=?,updater=?,update_time=CURRENT_TIMESTAMP "
                +"WHERE tenant_id=? AND store_id=? AND version=?",c.pickupEnabled(),c.timedDeliveryEnabled(),c.vipDeliveryEnabled(),
                c.pickupCodePrefix(),c.automaticPrintEnabled(),Long.toString(s.userId()),s.tenantId(),s.storeId(),c.expectedVersion())==1;
    }
}
