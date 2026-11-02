package cn.iocoder.yudao.server.print.miniappdemo.imagecollage;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileMetadata;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.List;

import static cn.iocoder.yudao.server.print.miniappdemo.imagecollage.MiniappDemoImageCollageException.Reason.INVALID_INPUT;

@Service
public final class MiniappDemoImageCollageFileService {
    private final MiniappDemoImageCollageProcessRenderer renderer;
    private final MiniappDemoFileService files;

    public MiniappDemoImageCollageFileService(MiniappDemoImageCollageProcessRenderer renderer,
            MiniappDemoFileService files) {
        this.renderer = renderer; this.files = files;
    }

    public MiniappDemoFileMetadata create(MiniappDemoIdentity identity, List<MultipartFile> images, String key) {
        if (identity == null || images == null || images.isEmpty() || images.size() > 9
                || key == null || !key.matches("[!-~]{1,128}")) throw new MiniappDemoImageCollageException(INVALID_INPUT);
        byte[] pdf = renderer.render(images);
        return files.upload(identity, new GeneratedPdf(pdf), key);
    }

    private record GeneratedPdf(byte[] bytes) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return "image-collage.pdf"; }
        @Override public String getContentType() { return "application/pdf"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File destination) { throw new UnsupportedOperationException("Read-only generated PDF"); }
    }
}
