package cn.iocoder.yudao.server.print.miniappdemo.library;

import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import static cn.iocoder.yudao.server.print.miniappdemo.library.MiniappDemoLibraryException.Reason.*;

/** Public, original fixtures only. Never load tenant catalogs or third-party URLs here. */
@Component
public final class MiniappDemoLibraryCatalog {
    public static final String VERSION="DEMO_LIBRARY_V1";
    public static final String SCOPE="PUBLIC_DEMO_ONLY";
    private static final int LINES_PER_PAGE=42;
    private static final List<String> CATEGORIES=List.of("全部", "使用指南", "练习资料", "生活文档");
    private static final Set<String> LIST_KEYS=Set.of("pageNo","pageSize","keyword","category","sort");
    private static final List<Entry> ENTRIES=List.of(
        entry("demo-print-guide", "打印服务使用指南", "使用指南", List.of(List.of(
            "本资料由新系统编写,用于验证打印流程.", "1. 选择需要打印的文件.", "2. 确认纸张,单双面和打印份数.",
            "3. 核对服务端生成的报价.", "4. 使用模拟支付完成演示订单.", "5. 在订单中查看处理和模拟打印结果.",
            "请勿在演示环境上传包含个人敏感信息的文件."))),
        entry("demo-practice-notes", "学习练习记录", "练习资料", List.of(
            List.of("学习目标记录", "日期:________________", "本次学习主题:________________", "计划完成:________________", "重点内容:________________", "练习记录:________________"),
            List.of("学习回顾", "已经掌握:________________", "仍需练习:________________", "下次安排:________________", "补充笔记:________________"))),
        entry("demo-weekly-plan", "每周计划模板", "生活文档", List.of(
            List.of("本周目标", "周次:________________", "目标一:________________", "目标二:________________", "目标三:________________"),
            List.of("每日安排", "星期一:________________", "星期二:________________", "星期三:________________", "星期四:________________", "星期五:________________", "星期六:________________", "星期日:________________"),
            List.of("本周回顾", "已完成事项:________________", "需要调整:________________", "下周准备:________________", "备注:________________")))
    );

    public Page list(MultiValueMap<String,String> query) {
        validateKeys(query,LIST_KEYS);
        int pageNo=number(one(query,"pageNo"),1,1,1000);
        int pageSize=number(one(query,"pageSize"),20,1,20);
        String keyword=one(query,"keyword");
        if (keyword==null) keyword="";
        if (keyword.codePointCount(0,keyword.length())>80 || keyword.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        String category=one(query,"category");
        if (category==null || category.isEmpty()) category="全部";
        if (!CATEGORIES.contains(category)) throw invalid();
        String sort=one(query,"sort");
        if (sort==null || sort.isEmpty()) sort="DEFAULT";
        if (!Set.of("DEFAULT","TITLE_ASC","PAGES_ASC").contains(sort)) throw invalid();
        final String selectedCategory=category, needle=keyword.trim().toLowerCase(Locale.ROOT);
        var stream=ENTRIES.stream().map(Entry::item).filter(item ->
                (selectedCategory.equals("全部") || item.category().equals(selectedCategory))
                && item.title().toLowerCase(Locale.ROOT).contains(needle));
        if (sort.equals("TITLE_ASC")) stream=stream.sorted(Comparator.comparing(Item::title));
        if (sort.equals("PAGES_ASC")) stream=stream.sorted(Comparator.comparingInt(Item::pages).thenComparing(Item::id));
        List<Item> filtered=stream.toList();
        int start=Math.min((pageNo-1)*pageSize,filtered.size());
        return new Page(VERSION,SCOPE,filtered.subList(start,Math.min(start+pageSize,filtered.size())),filtered.size(),pageNo,pageSize,CATEGORIES);
    }
    public Detail detail(MultiValueMap<String,String> query) {
        validateKeys(query,Set.of("id"));
        Entry entry=require(one(query,"id"));
        return new Detail(VERSION,SCOPE,entry.item(),entry.previewPages(),new Capabilities("RESERVED_WECHAT_IDENTITY","RESERVED_WECHAT_APPID","DEMO_FILE_GENERATION"));
    }
    public Entry require(String id) {
        if (id==null || !id.matches("[a-z0-9-]{1,64}")) throw invalid();
        return ENTRIES.stream().filter(entry -> entry.item().id().equals(id)).findFirst().orElseThrow(() -> new MiniappDemoLibraryException(NOT_FOUND));
    }
    public static List<Entry> entries() { return ENTRIES; }
    private static Entry entry(String id,String title,String category,List<List<String>> contentPages) {
        String contentVersion="v1";
        List<PreviewPage> previews=new ArrayList<>();
        List<String> pdfLines=new ArrayList<>();
        for (int i=0;i<contentPages.size();i++) {
            List<String> lines=new ArrayList<>();
            // Identity is printable trusted content, so even equal bodies have different PDF hashes.
            lines.add(id+" / "+contentVersion+" / "+(i+1));
            lines.add(title); lines.addAll(contentPages.get(i));
            if (lines.size()>LINES_PER_PAGE || lines.stream().anyMatch(line -> line.codePointCount(0,line.length())>36)) throw new IllegalStateException("Invalid fixed demo page");
            previews.add(new PreviewPage(i+1,List.copyOf(lines)));
            pdfLines.addAll(lines);
            if (i<contentPages.size()-1) for (int n=lines.size();n<LINES_PER_PAGE;n++) pdfLines.add("");
        }
        return new Entry(new Item(id,title,category,"原创演示文库",contentPages.size(),0,0,contentVersion),List.copyOf(previews),String.join("\n",pdfLines),title+".pdf");
    }
    private static void validateKeys(MultiValueMap<String,String> query,Set<String> keys) {
        if (query==null || !keys.containsAll(query.keySet())) throw invalid();
        for (var value:query.values()) if (value==null || value.size()!=1 || value.get(0)==null) throw invalid();
    }
    private static String one(MultiValueMap<String,String> query,String key) { return query.getFirst(key); }
    private static int number(String raw,int fallback,int minimum,int maximum) {
        if (raw==null) return fallback;
        if (!raw.matches("[1-9][0-9]{0,3}")) throw invalid();
        int value=Integer.parseInt(raw);
        if (value<minimum || value>maximum) throw invalid();
        return value;
    }
    private static MiniappDemoLibraryException invalid() { return new MiniappDemoLibraryException(INVALID_INPUT); }
    public record Item(String id,String title,String category,String source,int pages,long views,long prints,String contentVersion) {}
    public record PreviewPage(int pageNo,List<String> lines) {}
    public record Page(String catalogVersion,String scope,List<Item> items,int total,int pageNo,int pageSize,List<String> categories) {}
    public record Capabilities(String favorite,String share,String print) {}
    public record Detail(String catalogVersion,String scope,Item entry,List<PreviewPage> previewPages,Capabilities capabilities) {}
    public record Entry(Item item,List<PreviewPage> previewPages,String renderText,String filename) {}
}
