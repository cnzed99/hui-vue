package cn.iocoder.yudao.server.print.library.category;
import com.fasterxml.jackson.databind.ObjectMapper;
public class PrintLibraryCategoryChecks {
    public static void main(String[] args)throws Exception{
        ObjectMapper mapper=new ObjectMapper();String id="c632bb5e-a67a-4b99-9195-66e3da8e33fb";
        var create=mapper.createObjectNode().put("name","分类😀").put("requestId",id);
        if(!PrintLibraryCategoryCommand.parse(create,true).requestId().equals(id))throw new AssertionError("create");
        var update=mapper.createObjectNode().put("name","分类").put("expectedVersion","9007199254740993");
        if(PrintLibraryCategoryCommand.parse(update,false).expectedVersion()!=9007199254740993L)throw new AssertionError("version");
        reject(()->PrintLibraryCategoryCommand.parse(create.deepCopy().put("tenantId",1),true));
        reject(()->PrintLibraryCategoryCommand.parse(create.deepCopy().put("name","  "),true));
        reject(()->PrintLibraryCategoryCommand.parse(create.deepCopy().put("name","分".repeat(101)),true));
        reject(()->PrintLibraryCategoryCommand.parse(update.deepCopy().put("expectedVersion","9223372036854775807"),false));
        System.out.println("P15_COMMAND_CHECKS_PASS checks=6 DB_HTTP_NOT_RUN");
    }
    private static void reject(Runnable test){try{test.run();throw new AssertionError("accepted invalid input");}catch(PrintLibraryCategoryException expected){}}
}
