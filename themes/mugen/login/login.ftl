<#import "template.ftl" as layout>
<@layout.registrationLayout displayInfo=social.displayInfo displayWide=(realm.password && social.providers??); section>
    <#if section = "header">
        ${msg("doLogIn")}
    <#elseif section = "form">
    <div id="kc-form" <#if realm.password && social.providers??>class="${properties.kcContentWrapperClass!}"</#if>>
      <div id="kc-form-wrapper" <#if realm.password && social.providers??>class="${properties.kcFormSocialAccountContentClass!} ${properties.kcFormSocialAccountClass!}"</#if>>
        <#if realm.password>
            <form id="kc-form-login" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post">
                <ul class="nav nav-pills nav-justified" role="tablist">
                    <li role="presentation" class="active">
                        <a href="#kc-login-password" aria-controls="home" role="tab" data-toggle="tab">账号密码登录</a>
                    </li>
                    <li role="presentation">
                        <a href="#kc-login-phone" aria-controls="home" role="tab" data-toggle="tab">短信验证码登录</a>
                    </li>
                </ul>
                <div class="tab-content">
                    <div id="kc-login-password" role="tabpanel" class="tab-pane active fade in">
                        <div class="${properties.kcFormGroupClass!}">
                            <label for="username" class="${properties.kcLabelClass!}"><#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if></label>

                            <#if usernameEditDisabled??>
                                <input tabindex="1" id="username" class="${properties.kcInputClass!}" name="username" value="${(login.username!'')}" type="text" disabled />
                            <#else>
                                <input tabindex="1" id="username" class="${properties.kcInputClass!}" name="username" value="${(login.username!'')}"  type="text" autofocus autocomplete="off" />
                            </#if>
                        </div>

                        <div class="${properties.kcFormGroupClass!}">
                            <label for="password" class="${properties.kcLabelClass!}">${msg("password")}</label>
                            <input tabindex="2" id="password" class="${properties.kcInputClass!}" name="password" type="password" autocomplete="off" />
                        </div>
                    </div>
                    <div id="kc-login-phone" role="tabpanel" class="tab-pane fade">
                        <div class="${properties.kcFormGroupClass!}">
                            <label for="phoneNumberInput" class="${properties.kcLabelClass!}">${msg("phoneNumber")}</label>
                            <input tabindex="1" id="phoneNumberInput" class="${properties.kcInputClass!}" name="phoneNumber" type="tel" />
                        </div>

                        <div class="${properties.kcFormGroupClass!}">
                            <label for="loginCode" class="${properties.kcLabelClass!}">${msg("smsVerificationCode")}</label>
                            <div class="input-group">
                                <input type="text" maxlength="6" class="${properties.kcInputClass!} nospin" id="verificationCodeInput" name="loginCode"/>
                                <div class="input-group-btn">
                                    <button class="${properties.kcInputClass!} ${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonLargeClass!}" id="btnSendVerificationCode" type="button">
                                        发送验证码
                                    </button>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>

                <div class="${properties.kcFormGroupClass!} ${properties.kcFormSettingClass!}">
                    <div id="kc-form-options">
                        <#if realm.rememberMe && !usernameEditDisabled??>
                            <div class="checkbox">
                                <label>
                                    <#if login.rememberMe??>
                                        <input tabindex="3" id="rememberMe" name="rememberMe" type="checkbox" checked> ${msg("rememberMe")}
                                    <#else>
                                        <input tabindex="3" id="rememberMe" name="rememberMe" type="checkbox"> ${msg("rememberMe")}
                                    </#if>
                                </label>
                            </div>
                        </#if>
                    </div>
                    <div class="${properties.kcFormOptionsWrapperClass!}">
                        <#if realm.resetPasswordAllowed>
                            <span><a tabindex="5" href="${url.loginResetCredentialsUrl}">${msg("doForgotPassword")}</a></span>
                        </#if>
                    </div>

                </div>

                <div id="kc-form-buttons" class="${properties.kcFormGroupClass!}">
                    <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
                    <input tabindex="4" class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}" name="login" id="kc-login" type="submit" value="${msg("doLogIn")}"/>
                </div>
            </form>
        </#if>
        </div>
        <#if realm.password && social.providers??>
            <div id="kc-social-providers" class="${properties.kcFormSocialAccountContentClass!} ${properties.kcFormSocialAccountClass!}">
                <ul class="${properties.kcFormSocialAccountListClass!} <#if social.providers?size gt 4>${properties.kcFormSocialAccountDoubleListClass!}</#if>">
                    <#list social.providers as p>
                        <li class="${properties.kcFormSocialAccountListLinkClass!}"><a href="${p.loginUrl}" id="zocial-${p.alias}" class="zocial ${p.providerId}"> <span>${p.displayName}</span></a></li>
                    </#list>
                </ul>
            </div>
        </#if>
      </div>
      
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
      Message.makeGlobal();

      window.SmsSenderForm = new SmsSenderForm("login", "#phoneNumberInput", "#verificationCodeInput", "#btnSendVerificationCode");
      </script>
    <#elseif section = "info" >
        <#if realm.password && realm.registrationAllowed && !registrationDisabled??>
            <div id="kc-registration">
                <span>${msg("noAccount")} <a tabindex="6" href="${url.registrationUrl}">${msg("doRegister")}</a></span>
            </div>
        </#if>
    </#if>

</@layout.registrationLayout>
