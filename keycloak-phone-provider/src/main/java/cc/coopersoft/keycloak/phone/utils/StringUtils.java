package cc.coopersoft.keycloak.phone.utils;

/**
 * 字符串工具方法。
 */
public final class StringUtils {

    private StringUtils() {
    }

    /**
     * 判断字符串是否为空白：{@code null}、空串、纯空白都算空白。
     * 语义与 Keycloak 26.8 的 {@code org.keycloak.services.validation.Validation.isBlank} 一致。
     *
     * @param value 待判断字符串
     * @return 是否为空白
     */
    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
