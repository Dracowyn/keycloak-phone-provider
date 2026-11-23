package cc.coopersoft.keycloak.phone.providers.spi;

import lombok.Getter;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.provider.Provider;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Getter
public class ConfigService implements Provider {
    private static final Logger logger = Logger.getLogger(ConfigService.class);

    /**
     * 未配置 {@code allowedWriteClients} 时允许调用号码写接口的签发客户端：Keycloak 自带的账户中心
     */
    public static final String DEFAULT_WRITE_CLIENT = "account-console";

    /**
     * 短信服务商
     */
    public final String senderService;
    /**
     * Token过期时间
     */
    public final int tokenExpires;
    /**
     * 默认区号
     */
    public final int defaultAreaCode;
    /**
     * 区号代码配置文件
     */
    public final String areaCodeConfig;
    /**
     * 是否锁定区号代码选择
     */
    public final boolean areaLocked;
    /**
     * 是否允许未设置区域代码
     */
    public final boolean allowUnset;
    /**
     * 是否启用归属地验证
     */
    public final boolean locationEnable;
    /**
     * 归属地验证的APPCODE
     */
    public final String locationAppcode;
    /**
     * 归属地验证黑名单列表
     */
    public final String locationBlackList;
    /**
     * 允许调用号码绑定 / 解绑接口的签发客户端（令牌 azp）白名单，不可变；为空时拒绝所有写请求
     */
    public final Set<String> allowedWriteClients;

    public ConfigService(Config.Scope config) {
        this.senderService = config.get("senderService", "dummy");
        this.tokenExpires = config.getInt("tokenExpires", 300);
        this.defaultAreaCode = config.getInt("defaultAreacode", 86);
        this.areaCodeConfig = config.get("areacodeConfig", "./areacode.json");
        this.areaLocked = config.getBoolean("areaLocked", false);
        this.allowUnset = config.getBoolean("allowUnset", true);
        this.locationEnable = config.getBoolean("locationVerify", false);
        this.locationAppcode = config.get("locationAppcode", null);
        this.locationBlackList = config.get("locationBlackList", null);
        this.allowedWriteClients = parseAllowedWriteClients(config.get("allowedWriteClients"));
    }

    /**
     * 解析签发客户端白名单：逗号分隔，逐项去首尾空白，丢弃空项；不支持通配符。
     * <p>
     * 未配置（{@code null}）时只允许 {@value #DEFAULT_WRITE_CLIENT}。显式配置了值但解析后为空时返回空集合，
     * 号码写接口随之拒绝所有请求（fail-closed），不回退到默认值。
     *
     * @param raw 配置原值，未配置时为 {@code null}
     * @return 不可变的 clientId 集合
     */
    static Set<String> parseAllowedWriteClients(String raw) {
        if (raw == null) {
            return Set.of(DEFAULT_WRITE_CLIENT);
        }
        Set<String> clients = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(clientId -> !clientId.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (clients.isEmpty()) {
            logger.error("spi-phone-provider-config-allowed-write-clients 已配置但没有有效的 clientId，"
                    + "号码绑定 / 解绑接口将拒绝所有请求");
        }
        return clients;
    }

    @Override
    public void close() {

    }
}
