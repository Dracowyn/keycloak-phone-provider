class Message {
    constructor(messageList) {
        this.messages = messageList;
    }

    text(msgId, ...args) {
        if (msgId in this.messages) {
            return this.replaceVariables(this.messages[msgId], ...args);
        } else {
            return '{' + msgId + '}';
        }
    }

    replaceVariables(template, ...params) {
        let result = template;
        let paramId;
        result = result.replace(/(?<!\\)\$\{(\w+)\}/g, (...matches) => {
            paramId = parseInt(matches[1]) - 1;
            console.log(paramId);
            if (params[paramId]) {
                return params[paramId];
            } else {
                return '';
            }
        });

        return result;
    }

    static makeGlobal() {
        window.msg = new Message(window.MESSAGES);
    }
}

class Captcha {
    constructor() {
        this._captchaObj = null;
        this._shouldCallCaptcha = false;
        this._onSuccess = null;
        this._onError = null;

        fetch('/realms/' + REALM + '/geetest/code').then((res) => {
            if (res.ok) {
                return res.json();
            } else {
                throw msg.text('captcha-code-api-error');
            }
        }).then((data) => {
            initGeetest({
                gt: data.gt,
                challenge: data.challenge,
                offline: !data.success,
                new_captcha: true,
                product: 'bind',
            }, (obj) => {
                this._captchaObj = obj;
                if (this._shouldCallCaptcha) { //加载完成后立刻调用
                    this._captchaObj.verify();
                    this._captchaObj.onSuccess(this.buildOnSuccess(this._onSuccess));
                    this._shouldCallCaptcha = false;
                    this._onSuccess = null;
                    this._onError = null;
                }
            })
        });
    }

    verify() {
        return new Promise((resolve, reject) => {
            if (!this._captchaObj) {
                this._shouldCallCaptcha = true;
                this._onSuccess = resolve;
                this._onError = reject;
            } else { //已经加载成功的情况，直接调用
                this._captchaObj.verify();
                this._captchaObj.onSuccess(this.buildOnSuccess(resolve));
                this._captchaObj.onError((err) => {
                    alert(msg.text('captcha-load-error'));
                    reject('error', err);
                });

                this._captchaObj.onClose(() => {
                    reject('close');
                });
            }
        });
    }

    buildOnSuccess(callback) {
        return (function() {
            var result = this._captchaObj.getValidate();
            callback(result);
            this._captchaObj.reset();
        }).bind(this);
    }
}