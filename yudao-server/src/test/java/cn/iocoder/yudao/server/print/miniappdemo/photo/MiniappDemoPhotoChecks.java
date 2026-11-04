package cn.iocoder.yudao.server.print.miniappdemo.photo;
import cn.iocoder.yudao.server.print.miniappdemo.file.*;
import cn.iocoder.yudao.server.print.miniappdemo.quote.*;
import com.fasterxml.jackson.databind.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.cos.COSNumber;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
public final class MiniappDemoPhotoChecks {
    private static int checks;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);Files.createDirectories(root);
        check(!MiniappDemoSourceRules.pages("DOCUMENT",null,21),"document21");
        for(int pages:new int[]{20,21,30})check(MiniappDemoSourceRules.pages("PHOTO","PHOTO_6IN",pages),"photo pages");
        check(!MiniappDemoSourceRules.pages("PHOTO","PHOTO_6IN",31),"photo31");
        check(!MiniappDemoSourceRules.pages("PHOTO","PHOTO_6IN_COLLAGE",2),"collage2");
        check(!MiniappDemoSourceRules.pages("ID_PHOTO","ONE_INCH",2),"id2");
        check(!MiniappDemoSourceRules.valid("PHOTO",null),"null variant");
        for(String p:new String[]{"0","-1","1e2"," 1.00","1.001","999.01","01.00","1000"}){
            try{MiniappDemoPhotoPriceService.normalizePrice(p);throw new AssertionError("price accepted "+p);}catch(MiniappDemoPhotoException good){checks++;}
        }
        check("1.00".equals(MiniappDemoPhotoPriceService.normalizePrice("1")),"price canonical");
        var mapper=new ObjectMapper();
        var snapshot=new MiniappDemoQuoteSnapshot(2,"DEMO","CNY","PER_FILE_ALL_COPIES","aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","a".repeat(64),"PHOTO_6IN","COLOR","bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb","MINIAPP_DEMO_PHOTO_6IN","1",30,"SIMPLEX","99","30","2970","1.00","1.00",null,"1.00","2970.00",false,null,1000,2000,"PHOTO","PHOTO_6IN","PHOTO_SHEET");
        check("2970.00".equals(MiniappDemoQuoteVerifier.verify(snapshot).totalAmount()),"photo calculation");
        for(String[] mutation:new String[][]{{"pricePerSheet","1.0"},{"totalAmount","2970"},{"side","DUPLEX"},{"sourceKind","DOCUMENT"},{"sourceVariant","PHOTO_6IN_COLLAGE"},{"pricingMode","PAPER"},{"paper","A4"},{"sourceVariant","UNKNOWN"},{"priceProfileVersion","0"}}){
            var json=mapper.valueToTree(snapshot);((com.fasterxml.jackson.databind.node.ObjectNode)json).put(mutation[0],mutation[1]);
            try{MiniappDemoQuoteVerifier.verify(mapper.treeToValue(json,MiniappDemoQuoteSnapshot.class));throw new AssertionError("tamper accepted "+mutation[0]);}catch(IllegalArgumentException good){checks++;}
        }
        var legacy=new MiniappDemoQuoteSnapshot(1,"DEMO","CNY","PER_FILE_ALL_COPIES",snapshot.fileId(),snapshot.fileSha256(),"A4","BLACK_WHITE",snapshot.priceProfileId(),"legacy","1",1,"SIMPLEX","1","1","1","1.0","1.0",null,"1.0","1.0",false,null,1000,2000);
        check("1.0".equals(MiniappDemoQuoteVerifier.verify(legacy).totalAmount()),"legacy decimal preserved");
        check(!mapper.valueToTree(legacy).has("sourceKind"),"legacy wire shape unchanged");
        check(MiniappDemoQuoteVerifier.matchesSource(legacy,"DOCUMENT",null),"legacy source");
        check(!MiniappDemoQuoteVerifier.matchesSource(legacy,"ID_PHOTO","ONE_INCH"),"legacy cannot photo");
        Path horizontal=image(root,"landscape",600,200,false),vertical=image(root,"portrait",200,600,false),wide=image(root,"wide",2000,10,false);
        for(String variant:new String[]{"PHOTO_3IN","PHOTO_4IN","PHOTO_5IN","PHOTO_6IN"}){
            for(Path input:new Path[]{horizontal,vertical,wide}){
                Path out=root.resolve(variant+input.getFileName()+".pdf");check(render(out,variant,Collections.singletonList(input))==0,"render "+variant);
                try(var pdf=Loader.loadPDF(out.toFile())){
                    check(pdf.getNumberOfPages()==1,"single page");boolean portrait=input.equals(vertical);
                    double pw=pdf.getPage(0).getMediaBox().getWidth()*25.4/72,ph=pdf.getPage(0).getMediaBox().getHeight()*25.4/72;
                    check(Math.abs(pw-(portrait?102:152))<.01&&Math.abs(ph-(portrait?152:102))<.01,"physical page");
                    var tokens=new PDFStreamParser(pdf.getPage(0)).parse();
                    for(int i=6;i<tokens.size();i++)if(tokens.get(i) instanceof Operator op && op.getName().equals("cm")){
                        double w=((COSNumber)tokens.get(i-6)).floatValue(),h=((COSNumber)tokens.get(i-3)).floatValue();
                        int iw=ImageIO.read(input.toFile()).getWidth(),ih=ImageIO.read(input.toFile()).getHeight();
                        check(Math.abs(w/h-(double)iw/ih)<.02,"contain ratio");
                    }
                }
                Path replay=root.resolve(out.getFileName()+"-replay.pdf");check(render(replay,variant,ListOf(input))==0,"render replay");check(Arrays.equals(Files.readAllBytes(out),Files.readAllBytes(replay)),"deterministic PDF");
            }
        }
        for(int count:new int[]{1,2,3,4,5,6,7,8,9}){
            Path out=root.resolve("collage-"+count+".pdf");check(render(out,"PHOTO_6IN_COLLAGE",Collections.nCopies(count,horizontal))==0,"collage render");try(var pdf=Loader.loadPDF(out.toFile())){check(pdf.getNumberOfPages()==1,"collage one sheet");}
        }
        for(int count:new int[]{20,21,30}){
            Path out=root.resolve("pages-"+count+".pdf");check(render(out,"PHOTO_6IN",Collections.nCopies(count,horizontal))==0,"pages render");try(var pdf=Loader.loadPDF(out.toFile())){check(pdf.getNumberOfPages()==count,"all pages retained");}
        }
        check(render(root.resolve("31.pdf"),"PHOTO_6IN",Collections.nCopies(31,horizontal))!=0,"31 rejected");
        check(render(root.resolve("10collage.pdf"),"PHOTO_6IN_COLLAGE",Collections.nCopies(10,horizontal))!=0,"10 collage rejected");
        Path noise=image(root,"noise",1000,1000,true);check(render(root.resolve("large-output.pdf"),"PHOTO_6IN",Collections.nCopies(30,noise))==23,"2MiB output rejected wholly");
        Path huge=image(root,"long-edge",8193,1,false);check(render(root.resolve("long-edge.pdf"),"PHOTO_6IN",ListOf(huge))==22,"long edge rejected");
        System.out.println("PHOTO_CONTRACT_RENDERER_PASS checks="+checks+" childHeap=256m no-production-access");
    }
    private static java.util.List<Path> ListOf(Path path){return java.util.List.of(path);}
    private static Path image(Path root,String name,int width,int height,boolean noise)throws Exception{
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();g.setColor(Color.RED);g.fillRect(0,0,width,height);g.dispose();
        if(noise){Random random=new Random(2);for(int y=0;y<height;y++)for(int x=0;x<width;x++)image.setRGB(x,y,random.nextInt(0x1000000));}
        Path path=root.resolve(name+".jpg");ImageIO.write(image,"JPEG",path.toFile());image.flush();return path;
    }
    private static int render(Path output,String variant,java.util.List<Path> inputs)throws Exception{
        Files.deleteIfExists(output);var command=new ArrayList<String>(java.util.List.of(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Xmx256m","-XX:MaxMetaspaceSize=96m","-XX:+UseSerialGC","-Djava.awt.headless=true","-cp",System.getProperty("java.class.path"),MiniappDemoPhotoRendererMain.class.getName(),output.toString(),variant,"a".repeat(64),Integer.toString(inputs.size())));inputs.forEach(p->command.add(p.toString()));
        Process child=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.resolveSibling(output.getFileName()+".log").toFile()).start();
        if(!child.waitFor(30,TimeUnit.SECONDS)){child.destroyForcibly();child.waitFor();throw new AssertionError("renderer timeout");}return child.exitValue();
    }
}
