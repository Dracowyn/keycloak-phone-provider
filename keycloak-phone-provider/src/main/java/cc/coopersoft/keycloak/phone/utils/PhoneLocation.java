package cc.coopersoft.keycloak.phone.utils;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 手机号归属地查询
 *
 * @author Dracowyn
 * @since 2023-11-02 15:33
 */
public class PhoneLocation {
    private static final Logger logger = Logger.getLogger(PhoneLocation.class);

    /**
     * 验证手机号是否在黑名单中
     *
     * @param appcode     秘钥
     * @param phoneNumber 手机号
     * @param blackList   黑名单
     * @return true:在黑名单中，false:不在黑名单中
     */
    public boolean verification(String appcode, PhoneNumber phoneNumber, String blackList) {
        if (appcode != null) {
            // 定义请求的主机和路径
            String host = "https://ec8a.api.huachen.cn";
            String path = "/mobile";

            // 设置请求方法为GET
            String method = "GET";

            // 设置请求头
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "APPCODE " + appcode);

            // 设置查询参数
            Map<String, String> query = new HashMap<>();
            query.put("mobile", phoneNumber.getPhoneNumber());

            try {
                // 发送GET请求并获取响应
                HttpResponse response = HttpUtils.doGet(host, path, method, headers, query);

                // 转换响应
                String responseContent = IOUtils.toString(response.getEntity().getContent(), StandardCharsets.UTF_8);

                // 转换为Map
                Map<String, Object> responseMap = new JsonUtils().decode(responseContent);

                // 获取data中的isp
                String isp = (String) ((Map<?, ?>) responseMap.get("data")).get("isp");

                // 打印响应信息
                logger.info("Phone number:" + phoneNumber.getPhoneNumber() + " ISP:" + isp + " location data:" + responseMap);

                // 判断data中的isp是否在黑名单中
                return blackList.contains(isp);
            } catch (Exception e) {
                logger.error(e);
                return false;
            }
        } else {
            return false;
        }
    }
}
