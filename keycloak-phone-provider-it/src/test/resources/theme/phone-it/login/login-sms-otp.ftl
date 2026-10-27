<#-- 集成测试专用的最小短信 OTP 页面：provider jar 不自带该模板，测试只需要表单提交地址与 smsCode 字段 -->
<!DOCTYPE html>
<html>
<body>
<form id="kc-sms-otp-form" action="${url.loginAction}" method="post">
    <input type="text" name="smsCode"/>
    <input type="submit" value="submit"/>
</form>
</body>
</html>
