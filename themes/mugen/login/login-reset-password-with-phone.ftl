<#import "template.ftl" as layout>
<@layout.registrationLayout displayInfo=true; section>
    <#if section = "header">
        ${msg("emailForgotTitle")}
    <#elseif section = "form">
        <form id="kc-reset-password-form" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post">
            <ul class="nav nav-pills nav-justified" role="tablist">
                <li role="presentation" class="active">
                    <a href="#kc-reset-password-email" aria-controls="home" role="tab" data-toggle="tab">通过电子邮件验证</a>
                </li>
                <li role="presentation">
                    <a href="#kc-reset-password-phone" aria-controls="home" role="tab" data-toggle="tab">通过短信验证</a>
                </li>
            </ul>
            <div class="tab-content">
                <div id="kc-reset-password-email" role="tabpanel" class="tab-pane active fade in">
                    <div class="${properties.kcFormGroupClass!}">
                        <div class="${properties.kcLabelWrapperClass!}">
                            <label for="username" class="${properties.kcLabelClass!}"><#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if></label>
                        </div>
                        <div class="${properties.kcInputWrapperClass!}">
                            <#if auth?has_content && auth.showUsername()>
                                <input type="text" id="username" name="username" class="${properties.kcInputClass!}" autofocus value="${auth.attemptedUsername}"/>
                            <#else>
                                <input type="text" id="username" name="username" class="${properties.kcInputClass!}" autofocus/>
                            </#if>
                        </div>
                    </div>
                    <div class="kc-info">
                        ${msg("emailInstruction")}
                    </div>
                </div>
                <div id="kc-reset-password-phone" role="tabpanel" class="tab-pane fade">
                    <div class="${properties.kcFormGroupClass!}">
                        <div class="${properties.kcLabelWrapperClass!}">
                            <label for="phoneNumberInput" class="${properties.kcLabelClass!}">${msg("phoneNumber")}</label>
                        </div>
                        <div class="${properties.kcInputWrapperClass!}">
                            <input tabindex="1" id="phoneNumberInput" class="${properties.kcInputClass!}" name="phoneNumber" type="tel" />
                        </div>
                    </div>

                    <div class="${properties.kcFormGroupClass!}">
                        <div class="${properties.kcLabelWrapperClass!}">
                            <label for="code" class="${properties.kcLabelClass!}">${msg("smsVerificationCode")}</label>
                        </div>
                        <div class="${properties.kcInputWrapperClass!}">
                            <div class="input-group">
                                <input type="text" maxlength="6" class="${properties.kcInputClass!} nospin" id="verificationCodeInput" name="code"/>
                                <div class="input-group-btn">
                                    <button class="${properties.kcInputClass!} ${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonLargeClass!}" id="btnSendVerificationCode" type="button">
                                        发送验证码
                                    </button>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
            <div class="${properties.kcFormGroupClass!} ${properties.kcFormSettingClass!}">
                <div id="kc-form-options" class="${properties.kcFormOptionsClass!}">
                    <div class="${properties.kcFormOptionsWrapperClass!}">
                        <span><a href="${url.loginUrl}">${kcSanitize(msg("backToLogin"))?no_esc}</a></span>
                    </div>
                </div>

                <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                    <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}" type="submit" value="${msg("doSubmit")}"/>
                </div>
            </div>
        </form>
        <script src="${url.resourcesPath}/js/sms-sender-form.js"></script>
        <script>
        window.MESSAGES = {
            "error": "错误",
            "captcha-load-error": "无法加载验证码，请刷新重试",
            "captcha-code-api-error": "无法访问Api",
            "sending": "正在发送...",
            "send-verification-code": "发送验证码",
            "resend-verification-code": "重新发送",
            "second": "秒",
            "send-verification-error": "发送短信验证码出错",
        };
        window.REALM = '${realm.name}';

        window.SmsSenderForm = new SmsSenderForm("reset", "#phoneNumberInput", "#verificationCodeInput", "#btnSendVerificationCode");

        Message.makeGlobal();
        </script>
    <#elseif section = "info" >
    
    </#if>
</@layout.registrationLayout>
