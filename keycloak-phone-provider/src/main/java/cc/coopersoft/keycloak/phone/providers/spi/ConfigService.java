package cc.coopersoft.keycloak.phone.providers.spi;

import lombok.Getter;
import org.keycloak.Config;
import org.keycloak.provider.Provider;

@Getter
public class ConfigService implements Provider {
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

    public ConfigService(Config.Scope config) {
        this.senderService = config.get("senderService", "dummy");
        this.tokenExpires = config.getInt("tokenExpires", 300);
        this.defaultAreaCode = config.getInt("defaultAreacode", 86);
        this.areaCodeConfig = config.get("areacodeConfig", "./areacode.json");
        this.areaLocked = config.getBoolean("areaLocked", false);
        this.allowUnset = config.getBoolean("allowUnset", true);
        this.locationEnable = config.getBoolean("locationVerify", true);
        this.locationAppcode = config.get("locationAppcode", "");
        this.locationBlackList = config.get("locationBlackList", "");
    }

    @Override
    public void close() {

    }
}
