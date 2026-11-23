package cc.coopersoft.keycloak.phone.utils;

/**
 * 号码写进日志、异常消息时的脱敏形式：只保留号码末尾 {@value #TAIL_LENGTH} 位。
 */
public final class PhoneNumberMask {

    /**
     * 保留的号码末尾位数。
     */
    public static final int TAIL_LENGTH = 4;

    private PhoneNumberMask() {
    }

    /**
     * 号码末尾 {@value #TAIL_LENGTH} 位；号码不足这么长时原样返回，号码缺失时返回 {@code "null"}。
     * 非数字字符替换成 {@code _}：已存号码不一定经过输入校验，不能让其中的换行等字符伪造日志行。
     *
     * @param phoneNumber 号码
     * @return 号码尾号
     */
    public static String tail(PhoneNumber phoneNumber) {
        String number = phoneNumber == null ? null : phoneNumber.getPhoneNumber();
        if (number == null) {
            return "null";
        }
        String tail = number.length() <= TAIL_LENGTH ? number : number.substring(number.length() - TAIL_LENGTH);
        return tail.replaceAll("[^0-9]", "_");
    }
}
