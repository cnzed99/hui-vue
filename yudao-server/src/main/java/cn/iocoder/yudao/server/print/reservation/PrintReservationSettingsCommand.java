package cn.iocoder.yudao.server.print.reservation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import static cn.iocoder.yudao.server.print.reservation.PrintReservationSettingsException.Reason.INVALID_INPUT;

public record PrintReservationSettingsCommand(long expectedVersion, boolean pickupEnabled,
        boolean timedDeliveryEnabled, boolean vipDeliveryEnabled, String pickupCodePrefix, boolean automaticPrintEnabled) {
    public static PrintReservationSettingsCommand parse(JsonNode body) {
        if (body==null || !body.isObject()) throw invalid();
        Set<String> fields=new HashSet<>(); body.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(Set.of("expectedVersion","pickupEnabled","timedDeliveryEnabled","vipDeliveryEnabled",
                "pickupCodePrefix","automaticPrintEnabled"))) throw invalid();
        JsonNode v=body.get("expectedVersion"), p=body.get("pickupCodePrefix");
        if (!v.isTextual() || !v.textValue().matches("0|[1-9][0-9]{0,18}") || !p.isTextual()
                || !p.textValue().matches("[A-Za-z0-9]{0,2}")) throw invalid();
        long version;
        try { version=Long.parseLong(v.textValue()); } catch (NumberFormatException e) { throw invalid(); }
        if (version==Long.MAX_VALUE) throw invalid();
        return new PrintReservationSettingsCommand(version, bool(body,"pickupEnabled"), bool(body,"timedDeliveryEnabled"),
                bool(body,"vipDeliveryEnabled"), p.textValue(), bool(body,"automaticPrintEnabled"));
    }
    private static boolean bool(JsonNode body, String field) {
        JsonNode v=body.get(field); if (!v.isBoolean()) throw invalid(); return v.booleanValue();
    }
    private static PrintReservationSettingsException invalid() { return new PrintReservationSettingsException(INVALID_INPUT); }
}
