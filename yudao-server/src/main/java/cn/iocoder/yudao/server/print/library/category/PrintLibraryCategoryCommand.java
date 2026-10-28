package cn.iocoder.yudao.server.print.library.category;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import static cn.iocoder.yudao.server.print.library.category.PrintLibraryCategoryException.Reason.INVALID_INPUT;

public record PrintLibraryCategoryCommand(String name,String requestId,long expectedVersion) {
    public static PrintLibraryCategoryCommand parse(JsonNode body,boolean create){
        if(body==null||!body.isObject())throw invalid();
        Set<String> fields=new HashSet<>();body.fieldNames().forEachRemaining(fields::add);
        if(!fields.equals(create?Set.of("name","requestId"):Set.of("name","expectedVersion")))throw invalid();
        JsonNode name=body.get("name");
        if(!name.isTextual())throw invalid();
        String text=name.textValue();int length=text.codePointCount(0,text.length());
        // 100 is the new API storage limit, not an observed rule of the source form.
        if(text.isBlank()||length>100||text.indexOf('\0')>=0)throw invalid();
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=text.length()||!Character.isLowSurrogate(text.charAt(i)))throw invalid();}
            else if(Character.isLowSurrogate(c))throw invalid();
        }
        if(create){JsonNode id=body.get("requestId");if(!id.isTextual())throw invalid();return new PrintLibraryCategoryCommand(text,id(id.textValue()),0);}
        JsonNode v=body.get("expectedVersion");
        if(!v.isTextual()||!v.textValue().matches("[1-9][0-9]{0,18}"))throw invalid();
        long version;try{version=Long.parseLong(v.textValue());}catch(NumberFormatException e){throw invalid();}
        if(version==Long.MAX_VALUE)throw invalid();
        return new PrintLibraryCategoryCommand(text,null,version);
    }
    public static String id(String value){
        if(value==null||!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"))throw invalid();
        UUID.fromString(value);return value;
    }
    private static PrintLibraryCategoryException invalid(){return new PrintLibraryCategoryException(INVALID_INPUT);}
}
