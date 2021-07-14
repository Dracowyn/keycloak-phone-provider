<#import "template.ftl" as layout>
    <@layout.registrationLayout; section>
        <#if section="header">
            ${msg("registerTitle")}
            <#elseif section="form">
                <form id="kc-register-form" class="${properties.kcFormClass!}" action="${url.registrationAction}"
                    method="post">
                    <ul class="nav nav-pills nav-justified" role="tablist">
                        <li role="presentation" class="active">
                            <a href="#kc-register-email" aria-controls="home" role="tab" data-toggle="tab">邮箱注册</a>
                        </li>
                        <li role="presentation">
                            <a href="#kc-register-phone" aria-controls="home" role="tab" data-toggle="tab">手机号注册</a>
                        </li>
                    </ul>
                    <div class="tab-content">
                        <div id="kc-register-email" role="tabpanel" class="tab-pane active fade in">
                            <div class="${properties.kcFormGroupClass!} ${messagesPerField.printIfExists('email',properties.kcFormGroupErrorClass!)}">
                                <div class="${properties.kcLabelWrapperClass!}">
                                    <label for="email" class="${properties.kcLabelClass!}">${msg("email")}</label>
                                </div>
                                <div class="${properties.kcInputWrapperClass!}">
                                    <input type="text" id="email" class="${properties.kcInputClass!}" name="email"
                                        value="${(register.formData.email!'')}" autocomplete="email" />
                                </div>
                            </div>
                        </div>
                        <div id="kc-register-phone" role="tabpanel" class="tab-pane fade">
                            <div class="${properties.kcFormGroupClass!} ${messagesPerField.printIfExists('phoneNumber',properties.kcFormGroupErrorClass!)}">
                                <div class="${properties.kcLabelWrapperClass!}">
                                    <label for="phoneNumberInput" class="${properties.kcLabelClass!}">${msg("phoneNumber")}</label>
                                </div>
                                <div class="${properties.kcInputWrapperClass!}">
                                    <input tabindex="1" id="phoneNumberInput" class="${properties.kcInputClass!}"
                                        name="phoneNumber" type="tel" />
                                </div>
                            </div>

                            <div class="${properties.kcFormGroupClass!} ${messagesPerField.printIfExists('registerCode',properties.kcFormGroupErrorClass!)}">
                                <div class="${properties.kcLabelWrapperClass!}">
                                    <label for="registerCode" class="${properties.kcLabelClass!}">${msg("smsVerificationCode")}</label>
                                </div>
                                <div class="${properties.kcInputWrapperClass!}">
                                    <div class="input-group">
                                        <input type="text" maxlength="6" class="${properties.kcInputClass!} nospin"
                                            id="verificationCodeInput" name="registerCode"/>
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

                    <#if !realm.registrationEmailAsUsername>
                        <div
                            class="${properties.kcFormGroupClass!} ${messagesPerField.printIfExists('username',properties.kcFormGroupErrorClass!)}">
                            <div class="${properties.kcLabelWrapperClass!}">
                                <label for="username" class="${properties.kcLabelClass!}">${msg("username")}</label>
                            </div>
                            <div class="${properties.kcInputWrapperClass!}">
                                <input type="text" id="username" class="${properties.kcInputClass!}" name="username"
                                    value="${(register.formData.username!'')}" autocomplete="username" />
                            </div>
                        </div>
                    </#if>

                    <#if passwordRequired??>
                        <div
                            class="${properties.kcFormGroupClass!} ${messagesPerField.printIfExists('password',properties.kcFormGroupErrorClass!)}">
                            <div class="${properties.kcLabelWrapperClass!}">
                                <label for="password" class="${properties.kcLabelClass!}">${msg("password")}</label>
                            </div>
                            <div class="${properties.kcInputWrapperClass!}">
                                <input type="password" id="password" class="${properties.kcInputClass!}" name="password"
                                    autocomplete="new-password" />
                            </div>
                        </div>

                        <div
                            class="${properties.kcFormGroupClass!} ${messagesPerField.printIfExists('password-confirm',properties.kcFormGroupErrorClass!)}">
                            <div class="${properties.kcLabelWrapperClass!}">
                                <label for="password-confirm"
                                    class="${properties.kcLabelClass!}">${msg("passwordConfirm")}</label>
                            </div>
                            <div class="${properties.kcInputWrapperClass!}">
                                <input type="password" id="password-confirm" class="${properties.kcInputClass!}"
                                    name="password-confirm" />
                            </div>
                        </div>
                    </#if>

                    <div
                        class="${properties.kcFormGroupClass!} ${messagesPerField.printIfExists('firstName',properties.kcFormGroupErrorClass!)}">
                        <div class="${properties.kcLabelWrapperClass!}">
                            <label for="firstName" class="${properties.kcLabelClass!}">${msg("firstName")}</label>
                        </div>
                        <div class="${properties.kcInputWrapperClass!}">
                            <input type="text" id="firstName" class="${properties.kcInputClass!}" name="firstName"
                                value="${(register.formData.firstName!'')}" />
                        </div>
                    </div>

                    <#if recaptchaRequired??>
                        <div class="form-group">
                            <div class="${properties.kcInputWrapperClass!}">
                                <div class="g-recaptcha" data-size="compact" data-sitekey="${recaptchaSiteKey}"></div>
                            </div>
                        </div>
                    </#if>

                    <div class="${properties.kcFormGroupClass!}">
                        <div id="kc-form-options" class="${properties.kcFormOptionsClass!}">
                            <div class="${properties.kcFormOptionsWrapperClass!}">
                                <span><a href="${url.loginUrl}">${kcSanitize(msg("backToLogin"))?no_esc}</a></span>
                            </div>
                        </div>

                        <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                            <input
                                class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                                type="submit" value="${msg("doRegister")}" />
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
                Message.makeGlobal();

                window.SmsSenderForm = new SmsSenderForm("registration", "#phoneNumberInput", "#verificationCodeInput", "#btnSendVerificationCode");
                </script>
        </#if>
        </@layout.registrationLayout>