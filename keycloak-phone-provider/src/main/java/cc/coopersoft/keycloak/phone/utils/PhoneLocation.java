package cc.coopersoft.keycloak.phone.utils;

import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 手机号归属地查询
 *
 * @author Dracowyn
 * @since 2023-11-02 15:33
 */
public class PhoneLocation {
    private static final Logger logger = Logger.getLogger(PhoneLocation.class);
    private static final String API_HOST = "https://ec8a.api.huachen.cn";
    private static final String API_PATH = "/mobile";

    /**
     * 手机号归属地查询
     *
     * @param locationEnable 是否启用号码归属地检测
     * @param appcode        号码归属地Appcode
     * @param phoneNumber    手机号
     * @param blackList      号码归属地黑名单
     * @param session        Keycloak会话对象
     * @return 是否在黑名单中
     */
    public boolean verification(boolean locationEnable, String appcode, PhoneNumber phoneNumber, String blackList, KeycloakSession session) {
        if (!locationEnable || appcode == null) {
            return false;
        }

        String userPhoneNumber = phoneNumber.getPhoneNumber();
        String ip = session.getContext().getConnection().getRemoteAddr();

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = createRequest(appcode, userPhoneNumber);

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> responseMap = new JsonUtils().decode(response.body());

            String isp = extractIsp(responseMap);
            logger.info("Phone number: " + userPhoneNumber + " ISP: " + isp + " IP address: " + ip + " location data: " + responseMap);

            return isBlacklisted(isp, blackList);
        } catch (Exception e) {
            logger.error("Error during phone number verification", e);
            return false;
        }
    }

    /**
     * 创建请求
     *
     * @param appcode     号码归属地Appcode
     * @param phoneNumber 手机号
     * @return 请求对象
     */
    private HttpRequest createRequest(String appcode, String phoneNumber) {
        String uri = API_HOST + API_PATH + "?mobile=" + phoneNumber;
        return HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Authorization", "APPCODE " + appcode)
                .GET()
                .build();
    }

    /**
     * 提取运营商
     *
     * @param responseMap 响应数据
     * @return 运营商
     */
    private String extractIsp(Map<String, Object> responseMap) {
        return (String) ((Map<?, ?>) responseMap.get("data")).get("isp");
    }

    /**
     * 是否在黑名单中
     *
     * @param isp       运营商
     * @param blackList 黑名单
     * @return 是否在黑名单中
     */
    private boolean isBlacklisted(String isp, String blackList) {
        boolean isBlack = Stream.of(blackList.split(",")).anyMatch(item -> isp.trim().contains(item.trim()));
        if (isBlack) {
            logger.info("Phone number is blacklisted: " + isp);
        }
        return isBlack;
    }
}
