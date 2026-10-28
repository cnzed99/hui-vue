package cn.iocoder.yudao.server.print.advertising;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.util.*;
import static cn.iocoder.yudao.server.print.advertising.PrintAdvertisingException.Reason.*;
public record PrintAdvertisingCommand(long expectedVersion,String successMessageHtml,List<PrintAdvertisingDraft.Asset> assets) {
    public static PrintAdvertisingCommand parse(PrintAdPosition p,JsonNode body){
        fields(body,Set.of("expectedVersion","customEnabled","successMessageHtml","assets"));
        JsonNode version=body.get("expectedVersion"),enabled=body.get("customEnabled"),text=body.get("successMessageHtml"),items=body.get("assets");
        if(!version.isTextual()||!version.textValue().matches("0|[1-9][0-9]{0,18}")||!enabled.isBoolean()||!text.isTextual()||!items.isArray())throw invalid();
        long v;try{v=Long.parseLong(version.textValue());}catch(NumberFormatException e){throw invalid();}
        if(v==Long.MAX_VALUE||text.textValue().length()>10000||text.textValue().indexOf('\0')>=0||items.size()>p.maximum)throw invalid();
        // Enablement is intentionally blocked until delivery/display contracts are confirmed.
        if(enabled.booleanValue())throw new PrintAdvertisingException(PENDING);
        if(p!=PrintAdPosition.COMPUTER_TERMINAL&&!text.textValue().isEmpty())throw invalid();
        List<PrintAdvertisingDraft.Asset> assets=new ArrayList<>(); Set<String> slots=new HashSet<>(),urls=new HashSet<>();
        for(JsonNode item:items){
            fields(item,Set.of("slot","url"));
            if(!item.get("slot").isTextual()||!item.get("url").isTextual())throw invalid();
            String slot=item.get("slot").textValue(),url=item.get("url").textValue();
            if(p==PrintAdPosition.ORDER_PAGE ? !Set.of("FRONT","BACK").contains(slot) : !slot.matches("IMAGE_[1-5]"))throw invalid();
            if(p!=PrintAdPosition.ORDER_PAGE&&Integer.parseInt(slot.substring(6))>p.maximum)throw invalid();
            if(!slots.add(slot)||!urls.add(url)||url.length()>2048)throw invalid();
            try{URI uri=URI.create(url);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getFragment()!=null)throw invalid();}
            catch(IllegalArgumentException e){throw invalid();}
            assets.add(new PrintAdvertisingDraft.Asset(slot,url));
        }
        return new PrintAdvertisingCommand(v,text.textValue(),List.copyOf(assets));
    }
    static void fields(JsonNode node,Set<String> expected){
        if(node==null||!node.isObject())throw invalid();Set<String> actual=new HashSet<>();node.fieldNames().forEachRemaining(actual::add);if(!actual.equals(expected))throw invalid();
    }
    private static PrintAdvertisingException invalid(){return new PrintAdvertisingException(INVALID);}
}
