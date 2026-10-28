package cn.iocoder.yudao.server.print.printer;
import java.util.*;
/** No client-selected tenant, store or owner; only an optional canonical P33 terminal ID. */
public record PrintPrinterDirectoryQuery(int page,int pageSize,String terminalId) {
    public static PrintPrinterDirectoryQuery parse(Map<String,List<String>> params){
        if(!Set.of("page","pageSize","terminalId").containsAll(params.keySet()))throw PrintPrinterDirectoryException.invalid();
        int page=number(params,"page",1,1000000),size=number(params,"pageSize",10,100);
        String terminal=text(params,"terminalId","");
        if(!terminal.isEmpty())try{if(!UUID.fromString(terminal).toString().equals(terminal))throw PrintPrinterDirectoryException.invalid();}catch(IllegalArgumentException e){throw PrintPrinterDirectoryException.invalid();}
        return new PrintPrinterDirectoryQuery(page,size,terminal);
    }
    private static int number(Map<String,List<String>> p,String key,int fallback,int max){String value=text(p,key,Integer.toString(fallback));if(!value.matches("[1-9][0-9]{0,6}"))throw PrintPrinterDirectoryException.invalid();int result=Integer.parseInt(value);if(result>max)throw PrintPrinterDirectoryException.invalid();return result;}
    private static String text(Map<String,List<String>> p,String key,String fallback){if(!p.containsKey(key))return fallback;List<String> values=p.get(key);if(values==null||values.size()!=1||values.get(0)==null||values.get(0).length()>36)throw PrintPrinterDirectoryException.invalid();return values.get(0);}
}
