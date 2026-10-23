package cn.iocoder.yudao.server.print.file;
import java.nio.file.*;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;

public class PdfInspectionChecks {
    private static int passed;
    private static void reject(Path file, Path scratch, int limit, String expected) throws Exception {
        try { PdfInspection.inspect(file, scratch, 1024*1024, limit); throw new AssertionError("unexpected acceptance"); }
        catch (PdfInspection.Failure failure) {
            if (!expected.equals(failure.code())) throw new AssertionError("wrong failure: " + failure.code());
            passed++;
        }
    }
    private static void create(Path target, int pages, String password) throws Exception {
        try (PDDocument doc=new PDDocument()) {
            for(int i=0;i<pages;i++)doc.addPage(new PDPage());
            if(password!=null) {
                StandardProtectionPolicy policy=new StandardProtectionPolicy("fixture-owner",password,new AccessPermission());
                policy.setEncryptionKeyLength(128);doc.protect(policy);
            }
            doc.save(target.toFile());
        }
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);Path scratch=Files.createDirectory(root.resolve("scratch"));
        Path valid=root.resolve("three.pdf");create(valid,3,null);
        if(PdfInspection.inspect(valid,scratch,1024*1024,3)!=3)throw new AssertionError("page count");passed++;
        reject(valid,scratch,2,"PDF_PAGE_LIMIT");
        Path invalid=root.resolve("invalid.pdf");Files.writeString(invalid,"plain text with a pdf extension");
        reject(invalid,scratch,3,"PDF_INVALID");
        Path truncated=root.resolve("truncated.pdf");Files.write(truncated,java.util.Arrays.copyOf(Files.readAllBytes(valid),100));
        reject(truncated,scratch,3,"PDF_INVALID");
        Path encrypted=root.resolve("encrypted.pdf");create(encrypted,1,"fixture-user");
        reject(encrypted,scratch,3,"PDF_ENCRYPTED");
        Path openEncrypted=root.resolve("empty-password.pdf");create(openEncrypted,1,"");
        reject(openEncrypted,scratch,3,"PDF_ENCRYPTED");
        Path empty=root.resolve("empty.pdf");create(empty,0,null);reject(empty,scratch,3,"PDF_INVALID");
        try(var entries=Files.list(scratch)){if(entries.findAny().isPresent())throw new AssertionError("scratch leaked");passed++;}
        System.out.println("pdf_parser_checks_passed="+passed);
    }
}
