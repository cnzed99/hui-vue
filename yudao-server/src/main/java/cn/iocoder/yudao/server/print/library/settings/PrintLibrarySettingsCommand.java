package cn.iocoder.yudao.server.print.library.settings;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Set;
import static cn.iocoder.yudao.server.print.library.settings.PrintLibrarySettingsException.Reason.INVALID_INPUT;

public record PrintLibrarySettingsCommand(long expectedVersion,String name,String description) {
    public static PrintLibrarySettingsCommand parse(JsonNode body) {
        if(body==null||!body.isObject())throw invalid();
        Set<String> fields=new HashSet<>();body.fieldNames().forEachRemaining(fields::add);
        if(!fields.equals(Set.of("expectedVersion","name","description")))throw invalid();
        JsonNode v=body.get("expectedVersion");
        if(!v.isTextual()||!v.textValue().matches("0|[1-9][0-9]{0,18}"))throw invalid();
        long version;
        try{version=Long.parseLong(v.textValue());}catch(NumberFormatException e){throw invalid();}
        if(version==Long.MAX_VALUE)throw invalid();
        return new PrintLibrarySettingsCommand(version,text(body,"name",3,20),text(body,"description",1,30));
    }
    private static String text(JsonNode body,String field,int min,int max){
        JsonNode node=body.get(field);if(!node.isTextual())throw invalid();
        String value=node.textValue();
        int count=value.codePointCount(0,value.length());
        if(value.isBlank()||count<min||count>max||value.indexOf('\0')>=0)throw invalid();
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i)))throw invalid();}
            else if(Character.isLowSurrogate(c))throw invalid();
        }
        return value;
    }
    private static PrintLibrarySettingsException invalid(){return new PrintLibrarySettingsException(INVALID_INPUT);}
}
