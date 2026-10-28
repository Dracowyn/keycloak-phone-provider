package cc.coopersoft.keycloak.phone.providers.captcha.tencent;

import cc.coopersoft.keycloak.phone.providers.rest.dto.CaptchaConfigResponse;
import cc.coopersoft.keycloak.phone.providers.rest.util.ResponseBuilder;
import cc.coopersoft.keycloak.phone.providers.spi.CaptchaService;
import cc.coopersoft.keycloak.phone.utils.RegexUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * 腾讯云验证码REST资源
 * 提供获取验证码配置的HTTP端点
 */
public class TencentCaptchaResource {
    private static final Logger log = Logger.getLogger(TencentCaptchaResource.class);

    private final KeycloakSession session;
    private AuthenticationManager.AuthResult auth;

    public TencentCaptchaResource(KeycloakSession session) {
        this.session = session;
        this.auth = new AppAuthManager().authenticateIdentityCookie(session, session.getContext().getRealm());
        if (this.auth == null) {
            this.auth = new AppAuthManager.BearerTokenAuthenticator(session).authenticate();
        }
    }

    /**
     * 设置跨域请求头
     *
     * @param response      响应构建器
     * @param requestMethod 请求方法
     * @param requestHeaders 请求头
     * @param origin        请求来源
     */
    private void setCorsHeader(Response.ResponseBuilder response,
                               final String requestMethod,
                               final String requestHeaders, final String origin) {
        ClientModel client = this.session.getContext().getClient();
        if (client != null) {
            Set<String> allowedOrigins = client.getWebOrigins();
            for (String allowedOrigin : allowedOrigins) {
                // 当前origin符合要求
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
     * @return 验证码配置信息
     */
    private Response handleVerificationCodeRequest(final String requestMethod,
                                                    final String requestHeaders,
                                                    final String origin) {
        try {
            CaptchaService captcha = this.session.getProvider(CaptchaService.class);
            String captchaConfig = captcha.getFrontendKey(this.auth);

            // 解析旧格式的JSON字符串响应
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(captchaConfig);

            CaptchaConfigResponse response = CaptchaConfigResponse.builder()
                    .type("tencent")
                    .captchaAppId(jsonNode.has("captchaAppId") ? jsonNode.get("captchaAppId").asText() : null)
                    .build();

            Response.ResponseBuilder responseBuilder = Response.status(Response.Status.OK)
                    .entity(response)
                    .type(MediaType.APPLICATION_JSON_TYPE);
            
            CacheControl cacheControl = new CacheControl();
            cacheControl.setNoCache(false);
            responseBuilder.cacheControl(cacheControl);

            this.setCorsHeader(responseBuilder, requestMethod, requestHeaders, origin);
            return responseBuilder.build();
        } catch (Exception e) {
            log.error("获取腾讯云验证码配置失败", e);
            return ResponseBuilder.serverError("获取腾讯云验证码配置失败");
        }
    }

    /**
     * 获取腾讯云验证码配置（GET方式）
     * 返回前端需要的CaptchaAppId等信息
     *
     * @param requestMethod  请求方法
     * @param requestHeaders 请求头
     * @param origin         请求来源
     * @return 验证码配置信息
     */
    @GET
    @Path("code")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getVerificationCode(@HeaderParam("Access-Control-Request-Method") final String requestMethod,
                                        @HeaderParam("Access-Control-Request-Headers") final String requestHeaders,
                                        @HeaderParam("Origin") final String origin) {
        return handleVerificationCodeRequest(requestMethod, requestHeaders, origin);
    }

    /**
     * 获取腾讯云验证码配置（POST方式）
     * 返回前端需要的CaptchaAppId等信息
     *
     * @param requestMethod  请求方法
     * @param requestHeaders 请求头
     * @param origin         请求来源
     * @return 验证码配置信息
     */
    @POST
    @Path("code")
    @Produces(MediaType.APPLICATION_JSON)
    public Response postVerificationCode(@HeaderParam("Access-Control-Request-Method") final String requestMethod,
                                         @HeaderParam("Access-Control-Request-Headers") final String requestHeaders,
                                         @HeaderParam("Origin") final String origin) {
        return handleVerificationCodeRequest(requestMethod, requestHeaders, origin);
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
    public Response getVerificationCodeCors(
            @HeaderParam("Access-Control-Request-Method") final String requestMethod,
            @HeaderParam("Access-Control-Request-Headers") final String requestHeaders,
            @HeaderParam("Origin") final String origin) {
        final Response.ResponseBuilder response = Response.ok();
        this.setCorsHeader(response, requestMethod, requestHeaders, origin);
        return response.build();
    }
}
