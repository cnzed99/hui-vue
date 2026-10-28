package cn.iocoder.yudao.framework.apilog.core.filter;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ApiAccessLogFilterPasswordSanitizationTest {

    @Test
    void removesPasswordChangeSecretsFromQueryAndNestedJsonWhileKeepingOrdinaryFields() throws Exception {
        Map<String, String> query = new HashMap<>();
        query.put("oldPassword", "query-old-sentinel");
        query.put("newPassword", "query-new-sentinel");
        query.put("page", "2");
        Method sanitizeMap = ApiAccessLogFilter.class.getDeclaredMethod("sanitizeMap", Map.class, String[].class);
        sanitizeMap.setAccessible(true);
        String sanitizedQuery = (String) sanitizeMap.invoke(null, query, null);
        assertEquals(JsonUtils.parseTree("{\"page\":\"2\"}"), JsonUtils.parseTree(sanitizedQuery));
        assertFalse(sanitizedQuery.contains("sentinel"));

        String body = "{\"oldPassword\":\"root-old-sentinel\",\"newPassword\":\"root-new-sentinel\","
                + "\"nickname\":\"ordinary\",\"nested\":{\"oldPassword\":\"nested-old-sentinel\","
                + "\"newPassword\":\"nested-new-sentinel\",\"enabled\":true},"
                + "\"items\":[{\"oldPassword\":\"array-old-sentinel\",\"newPassword\":\"array-new-sentinel\","
                + "\"count\":3},[ {\"newPassword\":\"deep-array-sentinel\",\"note\":\"kept\"} ]]}";
        Method sanitizeJson = ApiAccessLogFilter.class.getDeclaredMethod("sanitizeJson", String.class, String[].class);
        sanitizeJson.setAccessible(true);
        String sanitizedBody = (String) sanitizeJson.invoke(null, body, null);
        assertFalse(sanitizedBody.contains("sentinel"));
        assertEquals(JsonUtils.parseTree("{\"nickname\":\"ordinary\",\"nested\":{\"enabled\":true},"
                + "\"items\":[{\"count\":3},[{\"note\":\"kept\"}]]}"), JsonUtils.parseTree(sanitizedBody));
    }
}
