package cn.iocoder.yudao.server.print.miniappdemo.photo;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdfwriter.compress.CompressParameters;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.GregorianCalendar;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/** Isolated JPEG/PNG decoder and bounded deterministic six-inch photo layout. */
public final class MiniappDemoPhotoRendererMain {
    private static final int EXIT_INVALID = 20;
    private static final int EXIT_UNSUPPORTED = 21;
    private static final int EXIT_PIXEL_LIMIT = 22;
    private static final int EXIT_PDF_TOO_LARGE = 23;
    private static final int EXIT_UNAVAILABLE = 24;
    private static final int CANVAS_WIDTH = 2400;
    private static final int CANVAS_HEIGHT = 3394;
    private static final int MARGIN = 117;
    private static final int GUTTER = 33;
    private static final Set<PosixFilePermission> PRIVATE_FILE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private MiniappDemoPhotoRendererMain() { }

    public static void main(String[] args) {
        int exit = EXIT_UNAVAILABLE;
        try {
            if (args.length < 5) throw new InvalidImage();
            Path output = Path.of(args[0]); String variant = args[1]; String digest = args[2];
            int count = Integer.parseInt(args[3]);
            boolean collage = "PHOTO_6IN_COLLAGE".equals(variant);
            if (!cn.iocoder.yudao.server.print.miniappdemo.file.MiniappDemoSourceRules.photo("PHOTO", variant)
                    || count < 1 || count > (collage ? 9 : 30) || args.length != count + 4
                    || !digest.matches("[0-9a-f]{64}") || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) throw new InvalidImage();
            Path[] inputs = new Path[count]; long total = 0;
            for (int i=0;i<count;i++) {
                inputs[i]=Path.of(args[4+i]);
                if (Files.isSymbolicLink(inputs[i]) || !Files.isRegularFile(inputs[i],LinkOption.NOFOLLOW_LINKS)
                        || Files.size(inputs[i])<1 || Files.size(inputs[i])>2L*1024*1024) throw new InvalidImage();
                total=Math.addExact(total,Files.size(inputs[i]));
            }
            if (total > (collage ? 12L : 30L)*1024*1024) throw new InvalidImage();
            byte[] pdf = photoPdf(inputs,variant,digest);
            Files.write(output,pdf,StandardOpenOption.CREATE_NEW); Files.setPosixFilePermissions(output,PRIVATE_FILE);
            try(FileChannel channel=FileChannel.open(output,StandardOpenOption.WRITE)){channel.force(true);}
            System.out.println("OK:"+HexFormat.of().formatHex(hash(pdf))); exit=0;
        } catch (UnsupportedImage e) { System.out.println("FAIL:UNSUPPORTED_FORMAT");exit=21;
        } catch (PixelLimit e) { System.out.println("FAIL:PIXEL_LIMIT");exit=22;
        } catch (PdfTooLarge e) { System.out.println("FAIL:PDF_TOO_LARGE");exit=23;
        } catch (Exception e) { System.out.println("FAIL:INVALID_IMAGE");exit=20; }
        System.exit(exit);
    }

    private static byte[] photoPdf(Path[] inputs,String variant,String digest) throws IOException {
        boolean collage="PHOTO_6IN_COLLAGE".equals(variant);
        try(PDDocument doc=new PDDocument()) {
            COSArray ids=new COSArray();byte[] id=Arrays.copyOf(HexFormat.of().parseHex(digest),16);
            ids.add(new COSString(id));ids.add(new COSString(id));doc.getDocument().setDocumentID(ids);doc.setDocumentId(1L);
            doc.getDocumentInformation().setProducer("photo-6in-v1");
            PDPage page=null; Grid grid=Grid.forCount(collage?inputs.length:1);
            if(collage){page=new PDPage(new PDRectangle(pt(152),pt(102)));doc.addPage(page);}
            for(int i=0;i<inputs.length;i++) {
                BufferedImage source=decode(inputs[i],8145600,8192).image();
                boolean portrait=!collage && source.getHeight()>source.getWidth();
                double pw=portrait?102:152,ph=portrait?152:102;
                if(!collage){page=new PDPage(new PDRectangle(pt(pw),pt(ph)));doc.addPage(page);}
                double w=152,h=102,x=0,y=0;
                if(collage){
                    w=(152-4-(grid.columns()-1)*2d)/grid.columns();h=(102-4-(grid.rows()-1)*2d)/grid.rows();
                    x=2+(i%grid.columns())*(w+2);y=102-2-(i/grid.columns()+1)*h-(i/grid.columns())*2;
                }else{
                    double[] box=switch(variant){case "PHOTO_3IN"->new double[]{89,63};case "PHOTO_4IN"->new double[]{102,76};case "PHOTO_5IN"->new double[]{127,89};default->new double[]{152,102};};
                    w=portrait?box[1]:box[0];h=portrait?box[0]:box[1];x=(pw-w)/2;y=(ph-h)/2;
                }
                double scale=Math.min(w/source.getWidth(),h/source.getHeight());
                double dw=source.getWidth()*scale,dh=source.getHeight()*scale;
                int tw=Math.max(1,(int)Math.round(dw*300/25.4)),th=Math.max(1,(int)Math.round(dh*300/25.4));
                BufferedImage small=new BufferedImage(tw,th,BufferedImage.TYPE_INT_RGB);Graphics2D g=small.createGraphics();
                try {g.setColor(Color.WHITE);g.fillRect(0,0,tw,th);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(source,0,0,tw,th,null);}finally{g.dispose();source.flush();}
                PDImageXObject image=JPEGFactory.createFromImage(doc,small,0.88f,300);small.flush();
                try(PDPageContentStream stream=new PDPageContentStream(doc,page,PDPageContentStream.AppendMode.APPEND,true,true)) {
                    stream.drawImage(image,pt(x+(w-dw)/2),pt(y+(h-dh)/2),pt(dw),pt(dh));
                }
            }
            var out=new BoundedOutput(2*1024*1024);doc.save(out,CompressParameters.NO_COMPRESSION);return out.toByteArray();
        }
    }
    private static float pt(double mm){return (float)(mm*72/25.4);}

    private static Decoded decode(Path path, long maxPixels, int maxDimension) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        try (ImageInputStream input = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
            if (input == null) throw new InvalidImage();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new UnsupportedImage();
            ImageReader reader = readers.next();
            try {
                // Frame-count validation requires random access; seekForwardOnly would make getNumImages(true) illegal.
                reader.setInput(input, false, true);
                String format = reader.getFormatName().toUpperCase(Locale.ROOT);
                boolean jpeg = "JPEG".equals(format) || "JPG".equals(format);
                boolean png = "PNG".equals(format);
                if (!jpeg && !png) throw new UnsupportedImage();
                if (png && isApng(bytes)) throw new UnsupportedImage();
                int frames;
                try { frames = reader.getNumImages(true); }
                catch (UnsupportedOperationException ignored) { frames = 1; }
                if (frames != 1) throw new UnsupportedImage();
                int width = reader.getWidth(0), height = reader.getHeight(0);
                long pixels = Math.multiplyExact((long) width, height);
                if (width < 1 || height < 1 || width > maxDimension || height > maxDimension || pixels > maxPixels) {
                    throw new PixelLimit();
                }
                BufferedImage raw = reader.read(0);
                if (raw == null || raw.getWidth() != width || raw.getHeight() != height) throw new InvalidImage();
                int orientation = jpeg ? exifOrientation(bytes) : 1;
                BufferedImage oriented = orient(raw, orientation);
                if (oriented != raw) raw.flush();
                return new Decoded(oriented, pixels);
            } finally {
                reader.dispose();
            }
        }
    }

    private static boolean isApng(byte[] png) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        if (png.length < signature.length || !Arrays.equals(signature, Arrays.copyOf(png, signature.length))) {
            throw new InvalidImage();
        }
        int offset = 8;
        while (offset + 12 <= png.length) {
            long length = Integer.toUnsignedLong(ByteBuffer.wrap(png, offset, 4).getInt());
            if (length > Integer.MAX_VALUE || offset + 12L + length > png.length) throw new InvalidImage();
            String type = new String(png, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            if ("acTL".equals(type)) return true;
            offset = Math.toIntExact(offset + 12L + length);
            if ("IEND".equals(type)) return false;
        }
        throw new InvalidImage();
    }

    private static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation == 1) return source;
        if (orientation < 2 || orientation > 8) throw new InvalidImage();
        int width = source.getWidth(), height = source.getHeight();
        boolean swap = orientation >= 5;
        BufferedImage result = new BufferedImage(swap ? height : width, swap ? width : height,
                source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        int[] sourcePixels = source.getRGB(0, 0, width, height, null, 0, width);
        int[] targetPixels = new int[sourcePixels.length];
        int targetWidth = result.getWidth();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int dx, dy;
            switch (orientation) {
                case 2 -> { dx = width - 1 - x; dy = y; }
                case 3 -> { dx = width - 1 - x; dy = height - 1 - y; }
                case 4 -> { dx = x; dy = height - 1 - y; }
                case 5 -> { dx = y; dy = x; }
                case 6 -> { dx = height - 1 - y; dy = x; }
                case 7 -> { dx = height - 1 - y; dy = width - 1 - x; }
                case 8 -> { dx = y; dy = width - 1 - x; }
                default -> throw new InvalidImage();
            }
            targetPixels[dy * targetWidth + dx] = sourcePixels[y * width + x];
        }
        result.setRGB(0, 0, result.getWidth(), result.getHeight(), targetPixels, 0, result.getWidth());
        return result;
    }

    private static int exifOrientation(byte[] jpeg) {
        if (jpeg.length < 4 || (jpeg[0] & 0xff) != 0xff || (jpeg[1] & 0xff) != 0xd8) throw new InvalidImage();
        int offset = 2;
        while (offset + 4 <= jpeg.length) {
            if ((jpeg[offset] & 0xff) != 0xff) throw new InvalidImage();
            int marker = jpeg[offset + 1] & 0xff; offset += 2;
            if (marker == 0xd9 || marker == 0xda) return 1;
            if (marker == 0x00 || marker == 0xff || marker >= 0xd0 && marker <= 0xd7 || marker == 0x01) continue;
            if (offset + 2 > jpeg.length) throw new InvalidImage();
            int length = ((jpeg[offset] & 0xff) << 8) | (jpeg[offset + 1] & 0xff);
            if (length < 2 || offset + length > jpeg.length) throw new InvalidImage();
            if (marker == 0xe1 && length >= 8 && equalsAscii(jpeg, offset + 2, "Exif\0\0")) {
                return parseTiffOrientation(jpeg, offset + 8, length - 8);
            }
            offset += length;
        }
        return 1;
    }
    private static int parseTiffOrientation(byte[] bytes, int start, int length) {
        if (length < 8 || start < 0 || start + length > bytes.length) throw new InvalidImage();
        ByteOrder order;
        if (bytes[start] == 'I' && bytes[start + 1] == 'I') order = ByteOrder.LITTLE_ENDIAN;
        else if (bytes[start] == 'M' && bytes[start + 1] == 'M') order = ByteOrder.BIG_ENDIAN;
        else throw new InvalidImage();
        ByteBuffer buffer = ByteBuffer.wrap(bytes, start, length).slice().order(order);
        if (Short.toUnsignedInt(buffer.getShort(2)) != 42) throw new InvalidImage();
        long ifdOffset = Integer.toUnsignedLong(buffer.getInt(4));
        if (ifdOffset > length - 2) throw new InvalidImage();
        int directory = Math.toIntExact(ifdOffset), count = Short.toUnsignedInt(buffer.getShort(directory));
        if ((long) directory + 2L + count * 12L > length) throw new InvalidImage();
        for (int index = 0; index < count; index++) {
            int entry = directory + 2 + index * 12;
            if (Short.toUnsignedInt(buffer.getShort(entry)) != 0x0112) continue;
            int type = Short.toUnsignedInt(buffer.getShort(entry + 2));
            long values = Integer.toUnsignedLong(buffer.getInt(entry + 4));
            if (type != 3 || values != 1) throw new InvalidImage();
            int orientation = Short.toUnsignedInt(buffer.getShort(entry + 8));
            if (orientation < 1 || orientation > 8) throw new InvalidImage();
            return orientation;
        }
        return 1;
    }
    private static boolean equalsAscii(byte[] value, int offset, String expected) {
        if (offset < 0 || offset + expected.length() > value.length) return false;
        for (int index = 0; index < expected.length(); index++) {
            if ((byte) expected.charAt(index) != value[offset + index]) return false;
        }
        return true;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception ignored) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static byte[] hash(byte[] value) { return sha256().digest(value); }

    private record Grid(int columns, int rows) {
        static Grid forCount(int count) {
            if (count == 1) return new Grid(1, 1);
            if (count == 2) return new Grid(1, 2);
            if (count <= 4) return new Grid(2, 2);
            if (count <= 6) return new Grid(2, 3);
            if (count <= 9) return new Grid(3, 3);
            throw new InvalidImage();
        }
    }
    private record Decoded(BufferedImage image, long sourcePixels) { }
    private static final class InvalidImage extends RuntimeException { }
    private static final class UnsupportedImage extends RuntimeException { }
    private static final class PixelLimit extends RuntimeException { }
    private static final class PdfTooLarge extends RuntimeException { }
    private static final class BoundedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int maximum;
        private BoundedOutput(int maximum) { this.maximum = maximum; }
        @Override public void write(int value) { check(1); bytes.write(value); }
        @Override public void write(byte[] value, int offset, int length) { check(length); bytes.write(value, offset, length); }
        private void check(int length) { if (length < 0 || bytes.size() > maximum - length) throw new PdfTooLarge(); }
        byte[] toByteArray() { return bytes.toByteArray(); }
        @Override public void close() throws IOException { bytes.close(); }
    }
}
