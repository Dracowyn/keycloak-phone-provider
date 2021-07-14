<#import "template.ftl" as layout>
<@layout.mainLayout active='account' bodyClass='user'; section>

    <div class="row">
        <div class="col-md-10">
            <h2>${msg("editAccountHtmlTitle")}</h2>
        </div>
        <div class="col-md-2 subtitle">
            <span class="subtitle"><span class="required">*</span> ${msg("requiredFields")}</span>
        </div>
    </div>

    <form action="${url.accountUrl}" class="form-horizontal" method="post">

        <input type="hidden" id="stateChecker" name="stateChecker" value="${stateChecker}">

        <#if !realm.registrationEmailAsUsername>
            <div class="form-group ${messagesPerField.printIfExists('username','has-error')}">
                <div class="col-sm-2 col-md-2">
                    <label for="username" class="control-label">${msg("username")}</label> <#if realm.editUsernameAllowed><span class="required">*</span></#if>
                </div>

                <div class="col-sm-10 col-md-10">
                    <input type="text" class="form-control" id="username" name="username" <#if !realm.editUsernameAllowed>disabled="disabled"</#if> value="${(account.username!'')}"/>
                </div>
            </div>
        </#if>

        <div class="form-group ${messagesPerField.printIfExists('email','has-error')}">
            <div class="col-sm-2 col-md-2">
            <label for="email" class="control-label">${msg("email")}</label> <span class="required">*</span>
            </div>

            <div class="col-sm-10 col-md-10">
                <input type="text" class="form-control" id="email" name="email" autofocus value="${(account.email!'')}"/>
            </div>
        </div>

        <div class="form-group ${messagesPerField.printIfExists('firstName','has-error')}">
            <div class="col-sm-2 col-md-2">
                <label for="firstName" class="control-label">${msg("fullName")}</label>
            </div>

            <div class="col-sm-10 col-md-10">
                <input type="text" class="form-control" id="firstName" name="firstName" value="${(account.firstName!'')}"/>
            </div>
        </div>

        <div class="form-group">
            <div class="col-sm-2 col-md-2">
                <label class="control-label">手机号</label>
            </div>

            <div class="col-sm-6 col-md-8">
                <p class="form-control-static" id="phoneNumber">
                    ${(account.attributes.phoneNumber!'未绑定')}
                </p>
            </div>
            <div class="col-sm-4 col-md-2">
                <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!}" type="button" data-toggle="modal" data-target="#phoneNumberModal">更改</button>
            </div>
        </div>

        <div class="form-group">
            <div id="kc-form-buttons" class="col-md-offset-2 col-md-10 submit">
                <div class="">
                    <#if url.referrerURI??><a href="${url.referrerURI}">${kcSanitize(msg("backToApplication")?no_esc)}</a></#if>
                    <button type="submit" class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonLargeClass!}" name="submitAction" value="Save">${msg("doSave")}</button>
                    <button type="submit" class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonLargeClass!}" name="submitAction" value="Cancel">${msg("doCancel")}</button>
                </div>
            </div>
        </div>
    </form>

    <div class="modal fade" id="phoneNumberModal" tabindex="-1" role="dialog" data-backdrop="static" data-keyboard="false">
        <div class="modal-dialog" role="document">
            <div class="modal-content">
                <div class="modal-header">
                    <button type="button" class="close" aria-label="Close" data-dismiss="modal"><span aria-hidden="true">&times;</span></button>
                    <h4 class="modal-title">更改手机号绑定</h4>
                </div>
                <div class="modal-body">
                    <form class="form-horizontal">
                        <div class="form-group form-group-lg">
                            <div class="col-sm-2 col-md-2">
                                <label class="control-label">手机号</label>
                            </div>
                            <div class="col-sm-10 col-md-10">
                                <input type="tel" maxlength="11" class="form-control" id="phoneNumberInput" name="phoneNumber" value="${(account.attributes.phoneNumber!)}"/>
                            </div>
                        </div>
                        <div class="form-group form-group-lg" id="groupVerificationCodeForm">
                            <div class="col-sm-2 col-md-2">
                                <label class="control-label">验证码</label>
                            </div>
                            <div class="col-sm-10 col-md-10">
                                <div class="input-group">
                                    <input type="text" maxlength="6" class="form-control nospin" id="verificationCodeInput" name="verificationCode"/>
                                    <div class="input-group-btn">
                                        <button class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonLargeClass!}" id="btnSendVerificationCode" type="button">
                                            发送验证码
                                        </button>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </form>
                </div>
                <div class="modal-footer">
                    <button type="button" class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonLargeClass!}" id="savePhoneNumber">保存</button>
                </div>
            </div><!-- /.modal-content -->
        </div><!-- /.modal-dialog -->
    </div><!-- /.modal -->
    <script>
    window.MESSAGES = {
        "error": "错误",
        "captcha-load-error": "无法加载验证码，请刷新重试",
        "captcha-code-api-error": "无法访问Api",
        "sending": "正在发送...",
        "send-verification-code": "发送验证码",
        "resend-verification-code": "重新发送",
        "save": "保存",
        "saving": "正在保存...",
        "save-phone-number-error": "保存手机号设置出错",
        "second": "秒",
        "no-phone-number": "未绑定",
        "send-verification-error": "发送短信验证码出错",
    };
    window.REALM = '${realm.name}';

    Message.makeGlobal();

    window.phoneModal = new PhoneNumberModalWidget('#phoneNumberModal', '#phoneNumber');
    </script>
</@layout.mainLayout>
