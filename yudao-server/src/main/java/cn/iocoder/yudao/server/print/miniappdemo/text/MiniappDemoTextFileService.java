package cn.iocoder.yudao.server.print.miniappdemo.text;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileMetadata;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;

@Service
public class MiniappDemoTextFileService {
    private final MiniappDemoTextPdfRenderer renderer;
    private final MiniappDemoFileService files;
    public MiniappDemoTextFileService(MiniappDemoTextPdfRenderer renderer, MiniappDemoFileService files) {
        this.renderer = renderer; this.files = files;
    }
    public MiniappDemoFileMetadata create(MiniappDemoIdentity identity, JsonNode body, String key) {
        if (identity == null || key == null || !key.matches("[!-~]{1,128}") || body == null || !body.isObject()
                || body.size() != 1 || !body.has("content") || !body.get("content").isTextual()) {
            throw new MiniappDemoTextException(MiniappDemoTextException.Reason.INVALID_INPUT);
        }
        String normalized = renderer.normalize(body.get("content").textValue());
        byte[] pdf = renderer.render(normalized);
        return files.upload(identity, new GeneratedPdf(pdf), key);
    }
    private record GeneratedPdf(byte[] bytes) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return "custom-text.pdf"; }
        @Override public String getContentType() { return "application/pdf"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File destination) { throw new UnsupportedOperationException("Read-only generated PDF"); }
    }
}
