package cc.coopersoft.keycloak.phone.utils;

import jakarta.ws.rs.core.MultivaluedMap;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 请求里传入的号码的统一校验与规范化入口。所有从请求读入号码的地方（发送、重发查询、绑定、注册、
 * 直授、手机号登录、短信 MFA 首次配置、重置凭据）都先经过这里，再拿结果去查库、落库、调短信服务商。
 * <p>
 * 不合格的一律拒绝，不做去掉中间空格、去掉号码里重复的区号前缀这类猜测性修正：
 * {@code 086}、{@code +86} 这类写法规范化成同一个区号，避免变体各算一个频控键绕过冷却和小时上限；
 * 逗号分隔的多个号码、换行等非数字字符直接拒绝，不会被原样交给短信服务商群发或拼进日志。
 * <p>
 * 只用于请求输入。从用户属性、凭据读出的已存号码仍走 {@link PhoneNumber} 原有构造器：
 * 已存数据不合规时不能让 MFA、登录直接失败。
 * <p>
 * 规范化后区号至多 {@value #MAX_AREA_CODE_DIGITS} 位、号码至多 {@value #MAX_E164_DIGITS} 位，
 * 不会超过验证码表 {@code AREA_CODE VARCHAR(4)}、{@code PHONE_NUMBER VARCHAR(20)} 的列宽，
 * 不会出现短信已发出、落库时才因超长失败的情况。
 */
public final class PhoneNumberInput {

    /**
     * E.164 规定区号加号码最多 15 位。
     */
    static final int MAX_E164_DIGITS = 15;

    /**
     * 去掉前导 0 后区号的最大位数（E.164 国家码 1～3 位）。
     */
    static final int MAX_AREA_CODE_DIGITS = 3;

    /**
     * 号码的最小位数。
     */
    static final int MIN_NUMBER_DIGITS = 5;

    private static final String CHINA_AREA_CODE = "86";
    private static final String AREA_CODE_PLUS = "+";
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern LEADING_ZEROS = Pattern.compile("^0+");
    private static final Pattern CHINA_MOBILE_NUMBER = Pattern.compile("1[0-9]{10}");

    private PhoneNumberInput() {
    }

    /**
     * 校验并规范化请求里的区号和号码。
     * <p>
     * 规则：两者先去掉首尾空白；区号允许一个前导 {@code +}，其余必须全是数字，去掉前导 0 后为 1～3 位且不为 0；
     * 号码必须全是数字，5～15 位；区号加号码不超过 15 位；区号为 86 时号码必须是 1 开头的 11 位。
     *
     * @param areaCode    请求里的区号
     * @param phoneNumber 请求里的号码
     * @return 规范化后的号码（区号不带 {@code +} 和前导 0，号码为纯数字）；缺失或不合格时为空
     */
    public static Optional<PhoneNumber> normalize(String areaCode, String phoneNumber) {
        String area = normalizeAreaCode(areaCode);
        String number = phoneNumber == null ? null : phoneNumber.strip();
        if (area == null || number == null || !DIGITS.matcher(number).matches()
                || number.length() < MIN_NUMBER_DIGITS || area.length() + number.length() > MAX_E164_DIGITS) {
            return Optional.empty();
        }
        if (CHINA_AREA_CODE.equals(area) && !CHINA_MOBILE_NUMBER.matcher(number).matches()) {
            return Optional.empty();
        }
        return Optional.of(new PhoneNumber(area, number));
    }

    /**
     * 校验并规范化按 {@link PhoneNumber#PhoneNumber(MultivaluedMap)} 从表单读出的号码。
     *
     * @param raw 原样读出、未经校验的号码
     * @return 规范化后的号码；缺失或不合格时为空
     */
    public static Optional<PhoneNumber> normalize(PhoneNumber raw) {
        return normalize(raw.getAreaCode(), raw.getPhoneNumber());
    }

    /**
     * 从表单读出号码（兼容旧字段名）并校验、规范化。
     *
     * @param formData 请求表单
     * @return 规范化后的号码；缺失或不合格时为空
     */
    public static Optional<PhoneNumber> fromForm(MultivaluedMap<String, String> formData) {
        return normalize(new PhoneNumber(formData));
    }

    /**
     * 表单号码：缺失或不合格时返回空号码（{@link PhoneNumber#isEmpty()} 为真），供原本只判断缺失的认证器沿用同一分支拒绝。
     *
     * @param formData 请求表单
     * @return 规范化后的号码，或空号码
     */
    public static PhoneNumber fromFormOrEmpty(MultivaluedMap<String, String> formData) {
        return fromForm(formData).orElseGet(() -> new PhoneNumber(null, null));
    }

    private static String normalizeAreaCode(String areaCode) {
        if (areaCode == null) {
            return null;
        }
        String area = areaCode.strip();
        if (area.startsWith(AREA_CODE_PLUS)) {
            area = area.substring(AREA_CODE_PLUS.length());
        }
        if (!DIGITS.matcher(area).matches()) {
            return null;
        }
        area = LEADING_ZEROS.matcher(area).replaceFirst("");
        return area.isEmpty() || area.length() > MAX_AREA_CODE_DIGITS ? null : area;
    }
}
