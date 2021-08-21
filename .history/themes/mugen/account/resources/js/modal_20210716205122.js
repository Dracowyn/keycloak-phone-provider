class PhoneNumberModalWidget {
    constructor(modalSelector, textPhoneNumberSelector) {
        this.textPhoneNumber = jQuery(textPhoneNumberSelector);
        this.modal = jQuery(modalSelector);

        this.modalBody = this.modal.find('.modal-body');
        this.inputPhoneNumber = this.modal.find('#phoneNumberInput');
        this.groupCode = this.modal.find('#groupVerificationCodeForm');
        this.inputCode = this.modal.find('#verificationCodeInput');
        this.btnSend = this.modal.find('#btnSendVerificationCode');
        this.btnSubmit = this.modal.find('#savePhoneNumber');

        this.groupCodeVisible = false;
        this.countdown = null;
        this.resendTimeout = 120;

        this.bindEvents();
        this.initCaptcha();
    }

    bindEvents() {
        var _this = this;
        this.inputPhoneNumber.on('input', function() {
            if (this.value.length > 0 && !_this.groupCodeVisible) {
                _this.showGroupCode();
            } else if (this.value.length == 0 && _this.groupCodeVisible) {
                _this.hideGroupCode();
            }
        });

        this.btnSend.on('click', this.onBtnSendClick.bind(this));
        this.btnSubmit.on('click', this.onBtnSubmitClick.bind(this));
    }

    initCaptcha() {
        this.captcha = new Captcha();
    }

    showGroupCode() {
        this.groupCodeVisible = true;
        this.groupCode.slideDown(250);
    }

    hideGroupCode() {
        this.groupCodeVisible = false;
        this.groupCode.slideUp(250);
    }

    startCountdown() {
        let currentTimeout = this.resendTimeout;
        let resendMsg = msg.text('resend-verification-code');
        let secondMsg = msg.text('second');
        this.countdown = setInterval(() => {
            currentTimeout -= 1;
            if (currentTimeout <= 0) {
                this.stopCountdown();
                return;
            }
            this.btnSend.text(resendMsg + ' (' + currentTimeout.toString() + secondMsg + ')');
        }, 1000);
    }

    stopCountdown() {
        if (this.countdown !== null) {
            clearInterval(this.countdown);
            this.countdown = null;
            this.btnSend.text(msg.text('resend-verification-code'));
            this.btnSend.prop('disabled', false);
        }
    }

    onBtnSendClick() {
        this.captcha.verify().then((data) => {
            this.btnSend.prop('disabled', true);
            this.btnSend.text(msg.text('sending'));

            data.phone_number = this.inputPhoneNumber.val();

            fetch('/auth/realms/' + REALM + '/sms/verification-code', {
                method: 'POST',
                body: JSON.stringify(data),
                headers: { 'Content-Type': 'application/json' },
            }).then((res) => {
                if (res.ok) {
                    return res.json();
                } else {
                    throw new Error(msg.text('send-verification-error'));
                }
            }).then((res) => {
                if (res.status == 1) {
                    this.startCountdown();
                } else if (res.status == 0) {
                    throw new Error(res.error);
                }
            }).catch((err) => {
                alert(err.message);
                this.btnSend.prop('disabled', false);
                this.btnSend.text(msg.text('send-verification-code'));
            });
        });
    }

    onBtnSubmitClick() {
        this.btnSubmit.prop('disabled', true);
        this.btnSubmit.text(msg.text('saving'));

        var phoneNum = this.inputPhoneNumber.val();
        if (phoneNum.length > 0) {
            var data = {
                phone_number: phoneNum,
                code: this.inputCode.val(),
            }
            fetch('/auth/realms/' + REALM + '/sms/update-profile', {
                method: 'POST',
                body: JSON.stringify(data),
                headers: { 'Content-Type': 'application/json' },
            }).then((res) => {
                if (res.ok) {
                    return res.json();
                } else {
                    throw new Error(msg.text('save-phone-number-error'));
                }
            }).then((res) => {
                if (res && res.status == 1) {
                    this.textPhoneNumber.text(phoneNum);
                    this.btnSubmit.prop('disabled', false);
                    this.btnSubmit.text(msg.text('save'));
                    this.modal.modal('hide');
                } else if (res && res.status == 0) {
                    throw new Error(res.error);
                } else {
                    throw new Error(res);
                }
            }).catch((err) => {
                alert(err.message);
                this.btnSubmit.prop('disabled', false);
                this.btnSubmit.text(msg.text('save'));
            });
        } else {
            //取消绑定
            fetch('/auth/realms/' + REALM + '/sms/update-profile/unset', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
            }).then((res) => {
                if (res.ok) {
                    return res.json();
                } else {
                    throw new Error(msg.text('save-phone-number-error'));
                }
            }).then((res) => {
                if (res && res.status == 1) {
                    this.textPhoneNumber.text(msg.text('no-phone-number'));
                    this.btnSubmit.prop('disabled', false);
                    this.btnSubmit.text(msg.text('save'));
                    this.modal.modal('hide');
                } else if (res && res.status == 0) {
                    throw new Error(res.error);
                } else {
                    throw new Error(res);
                }
            }).catch((err) => {
                alert(err.message);
                this.btnSubmit.prop('disabled', false);
                this.btnSubmit.text(msg.text('save'));
            });
        }
    }
}