package cn.iocoder.yudao.server.print.directory;
import java.util.List;
/** Read-only metadata DTOs, independent of quote pricing, uploaded files and device commands. */
public final class PrintDirectoryModel {
    private PrintDirectoryModel(){}
    public enum Kind { MY, PLATFORM, LIBRARY, TERMINAL }
    public record Page(List<?> list,long total,int page,int pageSize,List<String> categories,String dataBasis){}
    public record Product(String id,String code,String name,String categoryPath,String unitPrice,String currency,String priceDescription,String catalogKind){}
    public record LibraryEntry(String id,String libraryStoreName,String fileName,String categoryPath,String visibility,String auditStatus,String price,String currency,Integer pages,String printCount,String viewCount,String favoriteCount,String downloadCount,String uploadedAt){}
    public record Terminal(String id,String name,String hostName,String operatingSystem,String clientVersion,String ipAddress,Boolean mobileSelfService,Boolean enabled,String updatedAt,String onlineStatus){}
}
