package cn.iocoder.yudao.server.print.miniappdemo.library;

import cn.iocoder.yudao.server.print.miniappdemo.text.MiniappDemoTextPdfRenderer;
import cn.iocoder.yudao.server.print.miniappdemo.text.MiniappDemoTextProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.util.LinkedMultiValueMap;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;

/** Standalone bounded checks: no database, session, network or production mutations. */
public final class MiniappDemoLibraryChecks {
    public static void main(String[] args) throws Exception {
        if (args.length!=1) throw new IllegalArgumentException("Supply approved font asset path");
        MiniappDemoTextProperties props=new MiniappDemoTextProperties();
        props.setEnabled(true); props.setFontPath(Path.of(args[0])); props.setFontSha256(MiniappDemoTextProperties.FONT_HASH);
        props.setFontName(MiniappDemoTextProperties.FONT_NAME); props.setRenderVersion(MiniappDemoTextProperties.RENDER_VERSION);
        props.setMaxCodePoints(10000); props.setMaxUtf8Bytes(60*1024); props.setMaxConcurrentRenders(1);
        props.setMaxPages(20); props.setMaxPdfBytes(2*1024*1024); props.setRenderTimeoutMillis(5000);
        var renderer=new MiniappDemoTextPdfRenderer(props); renderer.start();
        var catalog=new MiniappDemoLibraryCatalog();
        var query=new LinkedMultiValueMap<String,String>();
        if (catalog.list(query).total()!=3) throw new AssertionError("catalog count");
        query.add("category","练习资料"); if (catalog.list(query).items().size()!=1) throw new AssertionError("category");
        query.add("unknown","no"); try { catalog.list(query); throw new AssertionError("unknown query accepted"); } catch (MiniappDemoLibraryException expected) {}
        byte[] prior=null;
        for (var entry:MiniappDemoLibraryCatalog.entries()) {
            try (var ttc=new org.apache.fontbox.ttf.TrueTypeCollection(Path.of(args[0]).toFile()); var doc=new org.apache.pdfbox.pdmodel.PDDocument()) {
                var font=org.apache.pdfbox.pdmodel.font.PDType0Font.load(doc,ttc.getFontByName(MiniappDemoTextProperties.FONT_NAME),true);
                for (int cp:entry.renderText().codePoints().toArray()) if (cp!='\n' && !font.hasGlyph(cp)) throw new AssertionError("unsupported fixed catalog character U+"+Integer.toHexString(cp));
            }
            byte[] first=renderer.render(renderer.normalize(entry.renderText()));
            byte[] second=renderer.render(renderer.normalize(entry.renderText()));
            if (!Arrays.equals(first,second)) throw new AssertionError("determinism");
            if (prior!=null && Arrays.equals(prior,first)) throw new AssertionError("entry identity");
            try (var document=Loader.loadPDF(first)) {
                if (document.getNumberOfPages()!=entry.item().pages() || entry.previewPages().size()!=entry.item().pages()) throw new AssertionError("page count");
                for (var page:entry.previewPages()) {
                    var strip=new PDFTextStripper(); strip.setStartPage(page.pageNo()); strip.setEndPage(page.pageNo());
                    String actual=strip.getText(document);
                    for (String line:page.lines()) if (!actual.contains(line)) throw new AssertionError("preview text");
                }
            }
            System.out.println(entry.item().id()+" pages="+entry.item().pages()+" bytes="+first.length+" sha256="+java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(first)));
            prior=first;
        }
        String identicalBody="共同正文";
        byte[] idA=renderer.render(renderer.normalize("identity-a / v1\n"+identicalBody));
        byte[] idB=renderer.render(renderer.normalize("identity-b / v1\n"+identicalBody));
        byte[] versionB=renderer.render(renderer.normalize("identity-a / v2\n"+identicalBody));
        if (Arrays.equals(idA,idB) || Arrays.equals(idA,versionB)) throw new AssertionError("identity/version fingerprint");
        System.out.println("PASS catalog, strict query, 3 preview/PDF page counts, determinism, identity/version fingerprints");
    }
}
