package cn.iocoder.yudao.server.print.store.profile;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import static cn.iocoder.yudao.server.print.store.profile.PrintStoreProfileException.Reason.*;

/** Strict full-replacement whitelist: absent fields and unknown fields are errors, never mass assignment. */
public record PrintStoreProfileCommand(long expectedVersion, PrintStoreProfileValue profile) {
    private static final Set<String> FIELDS=Set.of("expectedVersion","storeName","notice",
            "provinceCode","provinceName","cityCode","cityName","districtCode","districtName","streetCode","streetName",
            "address","contact","telephone","qq","openTime","closeTime","businessOpen");
    public static PrintStoreProfileCommand parse(JsonNode body) {
        if (body==null || !body.isObject()) throw invalid();
        Set<String> actual=new HashSet<>(); body.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(FIELDS)) throw invalid();
        JsonNode version=body.get("expectedVersion");
        if (!version.isTextual() || !version.textValue().matches("0|[1-9][0-9]{0,18}")) throw invalid();
        long expected;
        try { expected=Long.parseLong(version.textValue()); } catch (NumberFormatException ex) { throw invalid(); }
        // A successful CAS must have space for the next signed BIGINT version.
        if (expected==Long.MAX_VALUE) throw invalid();
        String name=text(body,"storeName",128);
        if (name==null || name.isBlank()) throw invalid();
        // No authoritative dictionary exists yet. Never accept arbitrary client code/name pairs.
        for (String field: new String[]{"provinceCode","provinceName","cityCode","cityName",
                "districtCode","districtName","streetCode","streetName"}) {
            if (!body.get(field).isNull()) throw new PrintStoreProfileException(REGION_NOT_CONFIGURED);
        }
        String open=text(body,"openTime",5), close=text(body,"closeTime",5);
        if ((open==null)!=(close==null) || (open!=null && (!time(open) || !time(close)))) throw invalid();
        JsonNode business=body.get("businessOpen");
        if (!business.isNull() && !business.isBoolean()) throw invalid();
        return new PrintStoreProfileCommand(expected,new PrintStoreProfileValue(name,text(body,"notice",2000),
                null,null,null,null,null,null,null,null,text(body,"address",500),text(body,"contact",128),
                text(body,"telephone",64),text(body,"qq",64),open,close,business.isNull()?null:business.booleanValue()));
    }
    private static String text(JsonNode body,String field,int capacity) {
        JsonNode node=body.get(field);
        if (node.isNull()) return null;
        if (!node.isTextual()) throw invalid();
        String value=node.textValue();
        if (value.codePointCount(0,value.length())>capacity || value.indexOf('\0')>=0) throw invalid();
        return value;
    }
    private static boolean time(String value) { return value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"); }
    private static PrintStoreProfileException invalid() { return new PrintStoreProfileException(INVALID_INPUT); }
}
