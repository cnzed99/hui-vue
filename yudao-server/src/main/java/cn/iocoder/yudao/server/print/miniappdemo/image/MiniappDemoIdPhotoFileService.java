package cn.iocoder.yudao.server.print.miniappdemo.image;

import cn.iocoder.yudao.server.print.miniappdemo.MiniappDemoIdentity;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileMetadata;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileService;
import cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoFileVariant;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static cn.iocoder.yudao.server.print.miniappdemo.image.MiniappDemoIdPhotoException.Reason.INVALID_INPUT;

@Service
public final class MiniappDemoIdPhotoFileService {
    private final MiniappDemoIdPhotoProcessRenderer renderer;
    private final MiniappDemoFileService files;

    public MiniappDemoIdPhotoFileService(MiniappDemoIdPhotoProcessRenderer renderer, MiniappDemoFileService files) {
        this.renderer = renderer; this.files = files;
    }

    public MiniappDemoFileMetadata create(MiniappDemoIdentity identity, MultipartFile image,
            MiniappDemoFileVariant variant, String externalKey) {
        if (identity == null || image == null || image.isEmpty() || variant == null || externalKey == null
                || !externalKey.matches("[!-~]{1,128}")) throw failure(INVALID_INPUT);
        byte[] pdf = renderer.render(image, variant);
        String domainKey = "id-photo-file-v1:" + digest(variant.name() + "\n" + externalKey);
        return files.uploadGeneratedIdPhoto(identity, new GeneratedPdf(pdf, variant), domainKey, variant);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static MiniappDemoIdPhotoException failure(MiniappDemoIdPhotoException.Reason reason) {
        return new MiniappDemoIdPhotoException(reason);
    }

    private record GeneratedPdf(byte[] bytes, MiniappDemoFileVariant variant) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return "id-photo-" + variant.name().toLowerCase() + ".pdf"; }
        @Override public String getContentType() { return "application/pdf"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes.clone(); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File destination) { throw new UnsupportedOperationException("Read-only generated PDF"); }
    }
}
