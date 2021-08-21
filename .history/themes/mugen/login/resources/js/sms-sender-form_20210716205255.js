class SmsSenderForm {
    constructor(type, phoneNumberSelector, codeSelector, sendSelector) {
        this.type = type;

        this.inputPhoneNumber = jQuery(phoneNumberSelector);
        this.inputCode = jQuery(codeSelector);
        this.btnSend = jQuery(sendSelector);

        this.countdown = null;
        this.resendTimeout = 120;

        this.bindEvents();
        this.initCaptcha();
    }

    bindEvents() {
        var _this = this;

        this.btnSend.on('click', this.onBtnSendClick.bind(this));
    }

    initCaptcha() {
        this.captcha = new Captcha();
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

            fetch('/realms/' + REALM + '/sms/' + this.type + '-code', {
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
}