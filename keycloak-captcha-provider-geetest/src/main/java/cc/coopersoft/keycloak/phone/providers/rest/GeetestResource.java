package cc.coopersoft.keycloak.phone.providers.rest;

import cc.coopersoft.keycloak.phone.providers.rest.dto.CaptchaConfigResponse;
import cc.coopersoft.keycloak.phone.providers.rest.util.ResponseBuilder;
import cc.coopersoft.keycloak.phone.providers.spi.CaptchaService;
import cc.coopersoft.keycloak.phone.utils.RegexUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager;

import java.util.Set;

/**
 * 极验验证码资源
 * 提供极验验证码配置接口
 *
 * @author cooper
 * @since 2020/10/30
 */
public class GeetestResource {
    private static final Logger log = Logger.getLogger(GeetestResource.class);

    private final KeycloakSession session;
    private AuthenticationManager.AuthResult auth;

    public GeetestResource(KeycloakSession session) {
        this.session = session;
        this.auth = new AppAuthManager().authenticateIdentityCookie(session, session.getContext().getRealm());
        if (this.auth == null) {
            this.auth = new AppAuthManager.BearerTokenAuthenticator(session).authenticate();
        }
    }

    private void setCrosHeader(Response.ResponseBuilder response,
                               final String requestMethod,
                               final String requestHeaders, final String origin) {
        ClientModel client = this.session.getContext().getClient();
        if (client != null) {
            Set<String> allowedOrigins = client.getWebOrigins();
            for (String allowedOrigin : allowedOrigins) {
                //当前origin符合要求
                if (RegexUtils.matchGlob(origin, allowedOrigin)) {
                    if (requestHeaders != null) {
                        response.header("Access-Control-Allow-Headers", requestHeaders);
                    }
                    if (requestMethod != null) {
                        response.header("Access-Control-Allow-Methods", requestMethod);
                    }
                    response.header("Access-Control-Allow-Origin", allowedOrigin);
                    break;
                }
            }
        }
    }

    /**
     * 处理验证码配置请求的通用方法
     *
     * @param requestMethod  请求方法
     * @param requestHeaders 请求头
     * @param origin         请求来源
     * @return 验证码配置响应
     */
    private Response handleVerificationCodesRequest(final String requestMethod,
                                                    final String requestHeaders,
                                                    final String origin) {
        try {
            CaptchaService captcha = this.session.getProvider(CaptchaService.class);
            String geetestCode = captcha.getFrontendKey(this.auth);

            // 解析旧格式的JSON字符串响应
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(geetestCode);

            Object response = mapFrontendConfig(jsonNode);

            Response.ResponseBuilder responseBuilder = Response.status(Response.Status.OK)
                    .entity(response)
                    .type(MediaType.APPLICATION_JSON_TYPE);
            
            CacheControl cacheControl = new CacheControl();
            cacheControl.setNoCache(false);
            responseBuilder.cacheControl(cacheControl);

            this.setCrosHeader(responseBuilder, requestMethod, requestHeaders, origin);
            return responseBuilder.build();
        } catch (Exception e) {
            log.error("获取极验验证码配置失败", e);
            return ResponseBuilder.serverError("获取极验验证码配置失败");
        }
    }

    static Object mapFrontendConfig(JsonNode jsonNode) {
        String gt = jsonNode.has("gt") ? jsonNode.get("gt").asText() : null;
        if (jsonNode.has("challenge") && jsonNode.isObject()) {
            // v3 初始化必须保留 success、gt、challenge、new_captcha 四个字段，同时保留统一配置字段供现有前端识别
            ObjectNode response = ((ObjectNode) jsonNode).deepCopy();
            response.put("type", "geetest");
            response.put("geetestId", gt);
            response.put("captchaAppId", gt);
            return response;
        }
        return CaptchaConfigResponse.builder()
                .type("geetest")
                .geetestId(gt)
                .captchaAppId(gt)
                .build();
    }

    /**
     * 获取极验验证码配置（GET方式）
     *
     * @param requestMethod  请求方法
     * @param requestHeaders 请求头
     * @param origin         请求来源
     * @return 验证码配置
     */
    @GET
    @Path("code")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getVerificationCodes(@HeaderParam("Access-Control-Request-Method") final String requestMethod,
                                         @HeaderParam("Access-Control-Request-Headers") final String requestHeaders,
                                         @HeaderParam("Origin") final String origin) {
        return handleVerificationCodesRequest(requestMethod, requestHeaders, origin);
    }

    /**
     * 获取极验验证码配置（POST方式）
     *
     * @param requestMethod  请求方法
     * @param requestHeaders 请求头
     * @param origin         请求来源
     * @return 验证码配置
     */
    @POST
    @Path("code")
    @Produces(MediaType.APPLICATION_JSON)
    public Response postVerificationCodes(@HeaderParam("Access-Control-Request-Method") final String requestMethod,
                                          @HeaderParam("Access-Control-Request-Headers") final String requestHeaders,
                                          @HeaderParam("Origin") final String origin) {
        return handleVerificationCodesRequest(requestMethod, requestHeaders, origin);
    }

    /**
     * 处理OPTIONS预检请求
     *
     * @param requestMethod  请求方法
     * @param requestHeaders 请求头
     * @param origin         请求来源
     * @return 预检响应
     */
    @OPTIONS
    @Path("code")
    public Response getVerificationCodesCors(
            @HeaderParam("Access-Control-Request-Method") final String requestMethod,
            @HeaderParam("Access-Control-Request-Headers") final String requestHeaders,
            @HeaderParam("Origin") final String origin) {
        final Response.ResponseBuilder response = Response.ok();
        this.setCrosHeader(response, requestMethod, requestHeaders, origin);
        return response.build();
    }
}
