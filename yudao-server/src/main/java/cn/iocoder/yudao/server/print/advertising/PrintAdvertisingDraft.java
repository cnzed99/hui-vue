package cn.iocoder.yudao.server.print.advertising;
import java.util.List;
/** Material drafts only. No consumer, device push, order printing or mini-program integration. */
public record PrintAdvertisingDraft(String position,String version,boolean configured,boolean customEnabled,
        String successMessageHtml,List<Asset> assets,String deliveryStatus) {
    public record Asset(String slot,String url) {}
    public static PrintAdvertisingDraft empty(PrintAdPosition p){return new PrintAdvertisingDraft(p.name(),"0",false,false,"",List.of(),"NOT_CONNECTED");}
}
