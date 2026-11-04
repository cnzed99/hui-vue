package cn.iocoder.yudao.server.print.miniappdemo.file;

import java.util.Set;

/** One source contract shared by parsing, quoting and execution admission. */
public final class MiniappDemoSourceRules {
    private static final Set<String> ID = Set.of("ONE_INCH", "TWO_INCH", "THREE_INCH");
    private static final Set<String> PHOTO = Set.of("PHOTO_3IN", "PHOTO_4IN", "PHOTO_5IN", "PHOTO_6IN", "PHOTO_6IN_COLLAGE");
    private MiniappDemoSourceRules() {}
    public static boolean valid(String kind, String variant) {
        return "DOCUMENT".equals(kind) ? variant == null : "ID_PHOTO".equals(kind) ? ID.contains(variant == null ? "" : variant)
                : "PHOTO".equals(kind) && PHOTO.contains(variant == null ? "" : variant);
    }
    public static boolean photo(String kind, String variant) { return valid(kind, variant) && !"DOCUMENT".equals(kind); }
    public static int maxPages(String kind, String variant) {
        if (!valid(kind, variant)) throw new IllegalArgumentException("Invalid source");
        return "DOCUMENT".equals(kind) ? 20 : "PHOTO".equals(kind) && !"PHOTO_6IN_COLLAGE".equals(variant) ? 30 : 1;
    }
    public static boolean pages(String kind, String variant, Integer pages) {
        return valid(kind, variant) && pages != null && pages >= 1 && pages <= maxPages(kind, variant);
    }
}
