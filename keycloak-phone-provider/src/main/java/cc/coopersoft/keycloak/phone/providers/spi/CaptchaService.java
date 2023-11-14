package cc.coopersoft.keycloak.phone.providers.spi;

import org.keycloak.provider.Provider;
import org.keycloak.services.managers.AuthenticationManager;

import jakarta.ws.rs.core.MultivaluedMap;

public interface CaptchaService extends Provider {
    /**
     * 发送验证码
     * @param formParams 表单参数
     * @param user 用户
     * @return 是否发送成功
     */
    boolean verify(final MultivaluedMap<String, String> formParams, String user);

    /**
     * 验证验证码
     * @param formParams 表单参数
     * @param user 用户
     * @return 是否验证成功
     */
    boolean verify(final MultivaluedMap<String, String> formParams, AuthenticationManager.AuthResult user);

    /**
     * 获取验证码
     * @param user 用户
     * @return
     */
    String getFrontendKey(String user);

    /**
     * 获取验证码
     * @param user 用户
     * @return
     */
    String getFrontendKey(AuthenticationManager.AuthResult user);
}
