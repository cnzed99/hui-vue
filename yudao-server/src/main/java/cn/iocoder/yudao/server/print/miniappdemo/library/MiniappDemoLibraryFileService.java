package cn.iocoder.yudao.server.print.miniappdemo.library;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileMetadata;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileService;
import cn.iocoder.yudao.server.print.miniappdemo.text.MiniappDemoTextPdfRenderer;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import static cn.iocoder.yudao.server.print.miniappdemo.library.MiniappDemoLibraryException.Reason.INVALID_INPUT;

@Service
public final class MiniappDemoLibraryFileService {
    private final MiniappDemoLibraryCatalog catalog;
    private final MiniappDemoTextPdfRenderer renderer;
    private final MiniappDemoFileService files;
    public MiniappDemoLibraryFileService(MiniappDemoLibraryCatalog catalog,MiniappDemoTextPdfRenderer renderer,MiniappDemoFileService files) {
        this.catalog=catalog; this.renderer=renderer; this.files=files;
    }
    public MiniappDemoFileMetadata create(MiniappDemoIdentity identity,JsonNode body,String key) {
        if (identity==null || key==null || !key.matches("[!-~]{1,128}") || body==null || !body.isObject()
                || body.size()!=1 || !body.has("entryId") || !body.get("entryId").isTextual()) throw new MiniappDemoLibraryException(INVALID_INPUT);
        var entry=catalog.require(body.get("entryId").textValue());
        byte[] pdf=renderer.render(renderer.normalize(entry.renderText()));
        return files.upload(identity,new GeneratedPdf(pdf,entry.filename()),key);
    }
    private record GeneratedPdf(byte[] bytes,String filename) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return filename; }
        @Override public String getContentType() { return "application/pdf"; }
        @Override public boolean isEmpty() { return bytes.length==0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File destination) { throw new UnsupportedOperationException("Read-only generated PDF"); }
    }
}
