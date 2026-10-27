<#-- 集成测试专用的最小短信 MFA 配置页：只需要表单提交地址、错误信息与号码/验证码字段 -->
<!DOCTYPE html>
<html>
<body>
<#if message?has_content><p id="kc-error">${message.summary}</p></#if>
<form id="kc-sms-otp-config-form" action="${url.loginAction}" method="post">
    <input type="text" name="areaCode" value="${areaCode!''}"/>
    <input type="text" name="phoneNumber" value="${phoneNumber!''}"/>
    <input type="text" name="smsCode"/>
    <input type="submit" value="submit"/>
</form>
</body>
</html>
