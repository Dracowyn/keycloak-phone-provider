package cc.coopersoft.keycloak.phone.providers.captcha.tencent;

import cc.coopersoft.keycloak.phone.providers.spi.CaptchaService;
import com.tencentcloudapi.captcha.v20190722.CaptchaClient;
import com.tencentcloudapi.captcha.v20190722.models.DescribeCaptchaResultRequest;
import com.tencentcloudapi.captcha.v20190722.models.DescribeCaptchaResultResponse;
import com.tencentcloudapi.common.Credential;
import com.tencentcloudapi.common.exception.TencentCloudSDKException;
import com.tencentcloudapi.common.profile.ClientProfile;
import com.tencentcloudapi.common.profile.HttpProfile;
import lombok.Setter;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.services.managers.AuthenticationManager;

import jakarta.ws.rs.core.MultivaluedMap;
import java.util.List;
import java.util.Optional;

/**
 * 腾讯云验证码服务实现
 * 支持腾讯云验证码的验证和前端密钥获取
 *
 * <p>容灾票据（disaster ticket）说明：配套前端在腾讯验证码 JS SDK 加载失败时，
 * 会在本地生成形如 {@code trerror_{errorCode}_{appId}_{timestamp}} 的容灾票据
 * （前缀 {@code trerror_}；腾讯官方示例中也存在 {@code terror_} 前缀），
 * 这类票据不是腾讯云签发的真实票据，无法也不应提交给 DescribeCaptchaResult 接口校验。
 * 配置项 {@code allowDisasterTicket}（默认 true）控制识别到容灾票据后的处理策略：
 * <ul>
 *     <li>true（默认）：与腾讯官方容灾实践一致，直接放行，避免因验证码前端资源不可用而导致用户完全无法登录；
 *     但这也意味着攻击者可以自行构造符合前缀规则的“容灾票据”绕过人机验证，需结合短信侧的每号码频控等风控措施评估风险。</li>
 *     <li>false：严格模式，容灾票据一律拒绝；安全敏感的部署建议显式配置为 false。</li>
 * </ul>
 */
public class TencentCaptchaServiceImpl implements CaptchaService {
    private static final Logger log = Logger.getLogger(TencentCaptchaServiceImpl.class);

    // 默认值
    private static final String DEFAULT_USER_ID = "guest";
    private static final String UNKNOWN_USER = "unknown";
    private static final String DEFAULT_ENDPOINT = "captcha.tencentcloudapi.com";

    // 表单参数名
    private static final String PARAM_TICKET = "ticket";
    private static final String PARAM_RANDSTR = "randstr";

    // 前端容灾票据前缀：JS SDK 加载失败时前端本地生成的兜底票据，无法通过腾讯云接口校验，需在此拦截识别
    private static final String DISASTER_TICKET_PREFIX_TR = "trerror_";
    private static final String DISASTER_TICKET_PREFIX_T = "terror_";

    /**
     * 必填配置键：缺任一都无法向腾讯云校验票据，此时人机验证一律不通过。
     */
    private static final List<String> REQUIRED_CONFIG_KEYS =
            List.of("secretId", "secretKey", "captchaAppId", "appSecretKey");

    /**
     * 容灾票据写进日志时保留的最大字符数：票据由请求方任意提交，只留前缀够排查即可。
     */
    private static final int LOGGED_TICKET_MAX_LENGTH = 16;

    private final KeycloakSession session;
    
    @Setter
    private Config.Scope config;

    public TencentCaptchaServiceImpl(KeycloakSession session) {
        this.session = session;
    }

    /**
     * 根据认证结果获取用户ID
     *
     * @param user 认证结果
     * @return 用户ID，如果为空则返回默认用户ID
     */
    private String getUserIdByAuthResult(AuthenticationManager.AuthResult user) {
        return user != null ? user.user().getId() : DEFAULT_USER_ID;
    }

    /**
     * 列出未配置（或为空白）的必填配置键。只返回键名，调用方据此打日志时不会带出任何密钥值。
     *
     * @param config 本 provider 的配置
     * @return 缺失的键名，配置齐全时为空列表
     */
    static List<String> missingConfigKeys(Config.Scope config) {
        return REQUIRED_CONFIG_KEYS.stream()
                .filter(key -> {
                    String value = config.get(key);
                    return value == null || value.isBlank();
                })
                .toList();
    }

    @Override
    public boolean verify(final MultivaluedMap<String, String> formParams, AuthenticationManager.AuthResult user) {
        return verify(formParams, getUserIdByAuthResult(user));
    }

    @Override
    public boolean verify(final MultivaluedMap<String, String> formParams, String user) {
        // 确保用户ID不为空
        user = Optional.ofNullable(user).orElse(UNKNOWN_USER);

        // 配置不全时无法校验，按不通过处理（部署了就必须生效，不能静默放行）
        List<String> missingKeys = missingConfigKeys(config);
        if (!missingKeys.isEmpty()) {
            log.errorf("腾讯云验证码配置不完整，缺少配置键 %s，人机验证不通过", missingKeys);
            return false;
        }

        // 获取腾讯云配置
        String secretId = config.get("secretId");
        String secretKey = config.get("secretKey");
        String captchaAppId = config.get("captchaAppId");
        String appSecretKey = config.get("appSecretKey");

        // 从表单参数获取票据和随机字符串
        String ticket = formParams.getFirst(PARAM_TICKET);
        String randstr = formParams.getFirst(PARAM_RANDSTR);

        if (ticket == null || randstr == null) {
            log.warn("表单提交中缺少必要的腾讯云验证码参数（ticket或randstr）");
            return false;
        }

        // 获取用户IP地址
        String userIp = session.getContext().getConnection().getRemoteAddr();

        // 识别前端容灾票据（见类注释）：此类票据并非腾讯云签发，不能提交给 DescribeCaptchaResult 接口校验，
        // 否则必然验证失败，容灾机制形同虚设。识别到后按 allowDisasterTicket 配置决定放行或拒绝。
        if (ticket.startsWith(DISASTER_TICKET_PREFIX_TR) || ticket.startsWith(DISASTER_TICKET_PREFIX_T)) {
            Boolean allowDisasterTicket = config.getBoolean("allowDisasterTicket", true);
            if (allowDisasterTicket) {
                log.warnf("检测到腾讯云验证码前端容灾票据，已按 allowDisasterTicket=true 放行: user=%s, ip=%s, ticket=%s",
                        user, userIp, ticketForLog(ticket));
                return true;
            } else {
                log.warnf("检测到腾讯云验证码前端容灾票据，已按 allowDisasterTicket=false 拒绝: user=%s, ip=%s, ticket=%s",
                        user, userIp, ticketForLog(ticket));
                return false;
            }
        }

        try {
            // 创建腾讯云API凭证
            Credential cred = new Credential(secretId, secretKey);
            
            // 配置HTTP选项
            HttpProfile httpProfile = new HttpProfile();
            httpProfile.setEndpoint(Optional.ofNullable(config.get("endpoint"))
                    .orElse(DEFAULT_ENDPOINT));
            
            ClientProfile clientProfile = new ClientProfile();
            clientProfile.setHttpProfile(httpProfile);

            // 创建验证码客户端
            CaptchaClient client = new CaptchaClient(cred, "", clientProfile);

            // 构建请求
            DescribeCaptchaResultRequest request = new DescribeCaptchaResultRequest();
            // CaptchaType是协议必填固定值9，不代表滑块、文字点选等验证码类型。
            request.setCaptchaType(9L);
            String captchaTypeStr = config.get("captchaType");
            if (captchaTypeStr != null && !"9".equals(captchaTypeStr)) {
                log.warn("captchaType旧配置不再控制请求参数，DescribeCaptchaResult固定使用CaptchaType=9");
            }
            request.setTicket(ticket);
            request.setRandstr(randstr);
            request.setUserIp(userIp);
            request.setCaptchaAppId(Long.parseLong(captchaAppId));
            request.setAppSecretKey(appSecretKey);

            // 发送请求并获取响应
            DescribeCaptchaResultResponse response = client.DescribeCaptchaResult(request);

            // 判断验证结果
            // CaptchaCode为1表示验证成功
            Long captchaCode = response.getCaptchaCode();
            boolean success = captchaCode != null && captchaCode == 1L;

            if (!success) {
                log.warnf("腾讯云验证码验证失败: CaptchaCode=%d, CaptchaMsg=%s", 
                        captchaCode, response.getCaptchaMsg());
            } else {
                log.debugf("腾讯云验证码验证成功: user=%s, ip=%s", user, userIp);
            }

            return success;

        } catch (TencentCloudSDKException e) {
            log.error("调用腾讯云验证码API时出错: " + e.getMessage(), e);
            
            // 根据配置决定在API错误时是否通过验证
            boolean fallbackOnError = Optional.ofNullable(config.getBoolean("fallbackOnError"))
                    .orElse(false);
            
            if (fallbackOnError) {
                log.warn("由于API错误且配置了fallbackOnError=true，验证通过");
            }
            
            return fallbackOnError;
        } catch (NumberFormatException e) {
            log.error("配置参数格式错误: " + e.getMessage(), e);
            return false;
        }
    }

    @Override
    public String getFrontendKey(AuthenticationManager.AuthResult user) {
        return getFrontendKey(getUserIdByAuthResult(user));
    }

    @Override
    public String getFrontendKey(String user) {
        // 确保用户ID不为空
        user = Optional.ofNullable(user).orElse(UNKNOWN_USER);

        // 与 verify 用同一套必填检查：空白也算缺失；缺任一键都无法校验票据，给前端下发配置也没有意义
        List<String> missingKeys = missingConfigKeys(config);
        if (!missingKeys.isEmpty()) {
            log.errorf("腾讯云验证码配置不完整，缺少配置键 %s，无法提供前端配置", missingKeys);
            return "{\"success\":0,\"message\":\"腾讯云验证码应用ID未配置\"}";
        }

        // 获取腾讯云配置
        String captchaAppId = config.get("captchaAppId");

        // 返回前端需要的配置信息
        // 前端会使用这个AppId初始化验证码组件
        return String.format("{\"success\":1,\"captchaAppId\":\"%s\"}", captchaAppId);
    }

    /**
     * 容灾票据的日志形式：截断到前 {@link #LOGGED_TICKET_MAX_LENGTH} 个字符，换行等控制字符替换成 {@code _}，
     * 防止请求方借票据伪造日志行。
     */
    private static String ticketForLog(String ticket) {
        String head = ticket.length() > LOGGED_TICKET_MAX_LENGTH ? ticket.substring(0, LOGGED_TICKET_MAX_LENGTH) : ticket;
        StringBuilder sanitized = new StringBuilder(head.length());
        for (char c : head.toCharArray()) {
            boolean lineBreakLike = Character.isISOControl(c) || c == '\u2028' || c == '\u2029';
            sanitized.append(lineBreakLike ? '_' : c);
        }
        return sanitized.toString();
    }

    @Override
    public void close() {
        // 无需清理资源
    }
}
