package cn.iocoder.yudao.server.print.miniappdemo.image;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileMetadata;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import static cn.iocoder.yudao.server.print.miniappdemo.image.MiniappDemoImageException.Reason.INVALID_INPUT;

@Service
public class MiniappDemoImageFileService {
    private final MiniappDemoImageProcessRenderer renderer;
    private final MiniappDemoFileService files;

    public MiniappDemoImageFileService(MiniappDemoImageProcessRenderer renderer, MiniappDemoFileService files) {
        this.renderer = renderer; this.files = files;
    }

    public MiniappDemoFileMetadata create(MiniappDemoIdentity identity, MultipartFile image, String key) {
        if (identity == null || image == null || image.isEmpty() || key == null || !key.matches("[!-~]{1,128}")) {
            throw new MiniappDemoImageException(INVALID_INPUT);
        }
        try {
            try (InputStream source = image.getInputStream()) {
                byte[] pdf = renderer.render(source, image.getSize());
                return files.upload(identity, new GeneratedPdf(pdf), key);
            }
        } catch (MiniappDemoImageException failure) {
            throw failure;
        } catch (IOException ignored) {
            throw new MiniappDemoImageException(MiniappDemoImageException.Reason.RENDER_UNAVAILABLE);
        }
    }

    private record GeneratedPdf(byte[] bytes) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return "image-print.pdf"; }
        @Override public String getContentType() { return "application/pdf"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File destination) { throw new UnsupportedOperationException("Read-only generated PDF"); }
    }
}
