package cn.iocoder.yudao.server.print.order;

import org.springframework.util.MultiValueMap;
import java.util.Set;

/** Read-only filters; identity always comes from PrintScope, never request parameters. */
public record PrintOrderQuery(int pageNo,int pageSize,String orderNo,Long createdFromMs,Long createdToMs,
        String orderState,String paymentState,String refundState,String printState) {
    private static final Set<String> KEYS=Set.of("pageNo","pageSize","orderNo","createdFromMs","createdToMs",
            "orderState","paymentState","refundState","printState");
    public PrintOrderQuery {
        if(pageNo<1 || pageNo>1_000_000 || pageSize<1 || pageSize>100
                || (orderNo!=null && !orderNo.matches("[A-Za-z0-9_-]{1,34}"))
                || !validTime(createdFromMs) || !validTime(createdToMs)
                || (createdFromMs!=null && createdToMs!=null && createdFromMs>createdToMs)
                || !validState(orderState) || !validState(paymentState) || !validState(refundState) || !validState(printState))throw invalid();
    }
    public static PrintOrderQuery parse(MultiValueMap<String,String> query){
        if(!KEYS.containsAll(query.keySet()))throw invalid();
        return new PrintOrderQuery(page(query,"pageNo",1,1_000_000),page(query,"pageSize",20,100),
                one(query,"orderNo"),time(query,"createdFromMs"),time(query,"createdToMs"),
                one(query,"orderState"),one(query,"paymentState"),one(query,"refundState"),one(query,"printState"));
    }
    private static String one(MultiValueMap<String,String> query,String key){
        var values=query.get(key);if(values==null)return null;
        if(values.size()!=1 || values.get(0)==null || values.get(0).isEmpty())throw invalid();
        return values.get(0);
    }
    private static int page(MultiValueMap<String,String> query,String key,int fallback,int max){
        String value=one(query,key);if(value==null)return fallback;
        if(!value.matches("[1-9][0-9]{0,6}"))throw invalid();
        int parsed=Integer.parseInt(value);if(parsed>max)throw invalid();return parsed;
    }
    private static Long time(MultiValueMap<String,String> query,String key){
        String value=one(query,key);if(value==null)return null;
        if(!value.matches("[0-9]{1,16}"))throw invalid();
        try{return Long.parseLong(value);}catch(NumberFormatException ignored){throw invalid();}
    }
    private static boolean validTime(Long value){return value==null || value>=0 && value<=8640000000000000L;}
    private static boolean validState(String value){return value==null || value.matches("[A-Z][A-Z_]{0,31}");}
    private static PrintOrderException invalid(){return new PrintOrderException(PrintOrderException.Reason.INVALID_INPUT);}
}
