# Keycloak (Quarkus 23.x.x) Phone Provider

此项目原作者并不是我，项目源地址：https://github.com/cooperlyt/keycloak-phone-provider

我们团队是在：https://github.com/cooperlyt/keycloak-phone-provider/tree/10.0.2

也就是使用Keycloak的11.0.3版本作为基线开发的版本基础上为了做定制化需求做了二开，加入了人机验证geetest，国际区号选择功能并使其兼容了Keycloak 23版本。

本插件运行环境要求：
+ Keycloak 23.x.x
+ Java 17

## 项目说明
前端项目地址：**通用前端还没做完，晚些时候会补上**

以下除了**安装教程**以外的翻译并不准确。

本项目的开源前端以及文档的重置等我有心思了再补（x

## 主要功能
~~+ 支持多种短信服务商~~(这个没做，其它服务商可以参考keycloak-sms-provider-dummy的代码去实现)
+ 支持国际区号选择
+ 支持人机验证
+ 支持短信验证码重置密码
+ 支持短信验证码认证
+ 支持自动创建用户
+ 支持手机号注册
+ 支持只允许用手机号注册
+ 支持注册添加用户属性与redirect_uri参数
+ 支持归属地黑名单检测
+ 支持自定义短信模板
+ 支持自定义短信签名

## 客户端:

此为原作者的安卓手机客户端项目 [KeycloakClient](https://github.com/cooper-lyt/KeycloakClient) 

## 使用方法

**安装教程:**

i. 添加模块
```
编译后将target/providers目录中的文件拷贝到Keycloak根目录下的providers即可
当然除了target/providers/keycloak-captcha-provider-recaptcha.jar（因为这个我没做适配）
```
在keycloak根目录下conf/keycloak.conf添加以下信息
ii. 设置短信服务商。
```
# 短信发送服务商
spi-phone-provider-config-sender-service=Aliyun
# 验证码有效期
spi-phone-provider-config-token-expires=300
# 默认区号
spi-phone-provider-config-default-areacode=86
# 区号配置信息
spi-phone-provider-config-areacode-config=${kc.home.dir:}/conf/areacode.json
# 锁定区号
spi-phone-provider-config-area-locked=false
```
iii. 设置短信模板ID、短信签名、accessKeyID、accessSecret
```
# 短信验证码模板
spi-message-sender-service-aliyun-DEFAULT_TEMPLATE=
# 短信签名
spi-message-sender-service-aliyun-DEFAULT_SIGNNAME=
# 阿里云ID与Key
spi-message-sender-service-aliyun-access-key-id=
spi-message-sender-service-aliyun-access-secret=
```
iiii. 设置极验id和key
```
# 极验ID和key
spi-captcha-service-geetest-id=
spi-captcha-service-geetest-key=
```

iiii. 设置号码归属地黑名单检测
```
# 是否开启号码归属地黑名单检测
spi-phone-provider-config-location-verify=true
# 号码归属地检测APPCODE
# 用的是阿里云的手机号码归属地查询服务
# 购买地址：https://market.aliyun.com/products/57126001/cmapi022206.html
spi-phone-provider-config-location-appcode=
# 号码归属地黑名单（中文务必使用unicode编码）英文逗号分隔
spi-phone-provider-config-location-black-list=\u865a\u62df
```


**OTP by Phone**

  in Authentication page, copy the browser flow and add a subflow to the forms, then adding `OTP Over SMS` as a
  new execution. Don't forget to bind this flow copy as the de facto browser flow.
  Finally, register the required actions `Update Phone Number` and `Configure OTP over SMS` in the Required Actions tab.


**Only use phone login or get Access token use endpoints:**

Under Authentication > Flows:
 + Copy the 'Direct Grant' flow to 'Direct grant with phone' flow
 + Click on 'Actions > Add execution' on the 'Provide Phone Number' line
 + Click on 'Actions > Add execution' on the 'Provide Verification Code' line
 + Delete or disable other
 + Set both of 'Provide Phone Number' and 'Provide Verification Code' to 'REQUIRED'

Under 'Clients > $YOUR_CLIENT > Authentication Flow Overrides' or 'Authentication > Bindings' 
Set Direct Grant Flow to 'Direct grant with phone' 

**Everybody phone number( if not exists create user by phone number) get Access token use endpoints:**

Under Authentication > Flows:
 + Copy the 'Direct Grant' flow to 'Direct grant everybody with phone' flow
 + Click on 'Actions > Add execution' on the 'Authentication Everybody By Phone' line
 + Delete or disable other
 + Set 'Authentication Everybody By Phone' to 'REQUIRED'

Under 'Clients > $YOUR_CLIENT > Authentication Flow Overrides' or 'Authentication > Bindings' 
Set Direct Grant Flow to 'Direct grant everybody with phone' 

**Reset credential**
 Testing , coming soon!
 
**Phone one key longin**
  Testing , coming soon!

**Phone registration support**

Under Authentication > Flows:
 + Create flows from registration:
    Copy the 'Registration' flow to 'Registration fast by phone' flow.
 
 + (Optional) Phone number used as username for new user:  
    Delete or disable 'Registration User Creation'.
    Click on 'Registration Fast By Phone Registration Form > Actions > Add execution' on the 'Registration Phone As Username Creation' line.
    Move this item to first.
    
 +  Add phone number to profile
    Click on 'Registration Fast By Phone Registration Form > Actions > Add execution' on the 'Phone Validation' line

 + (Optional)Hidden all other field phone except :   
    Click on 'Registration Fast By Phone Registration Form > Actions > Add execution' on the 'Registration Least' line

 + (Optional)Read query parameter add to user attribute:
        Click on 'Registration Fast By Phone Registration Form > Actions > Add execution' on the 'Query Parameter Reader' line
        Click on 'Registration Fast By Phone Registration Form > Actions > configure' add accept param name in to 

 + (Optional)Hidden password field:
    Delete or disable 'Password Validation'.
    
 Set All add item as Required.

Under Authentication > Bindings
Set Registration Flow to 'Registration fast by phone' 

Under Realm Settings > Themes
Set Login Theme as 'phone'

## Authentication settings
### Browser With Phone
![](https://i.imgur.com/5UTcWXN.png)

### Registration With Phone
![](https://i.imgur.com/vQT4gSm.png)

### Reset Credentials With Phone
![](https://i.imgur.com/R7cul0l.png)

test:
```http://<addr>/realms/<realm name>/protocol/openid-connect/registrations?client_id=<client id>&response_type=code&scope=openid%20email&redirect_uri=<redirect_uri>```


**About the API endpoints:** 

You'll get 2 extra endpoints that are useful to do the verification from a custom application.

  + ```GET /realms/{realmName}/sms/verification-code?phoneNumber=+5534990001234``` (To request a number verification. No auth required.)
  + ```POST /realms/{realmName}/sms/verification-code?phoneNumber=+5534990001234&code=123456``` (To verify the process. User must be authenticated.)

You'll get 2 extra endpoints that are useful to do the access token from a custom application.
  + ```GET /realms/{realmName}/sms/authentication-code?phoneNumber=+5534990001234``` (To request a number verification. No auth required.)
  + ```POST /realms/shuashua/protocol/openid-connect/token```
    ```Content-Type: application/x-www-form-urlencoded```
    ```grant_type=password&phone_number=$PHONE_NUMBER&code=$VERIFICATION_CODE&client_id=$CLIENT_ID&client_secret=CLIENT_SECRECT```


And then use Verification Code authentication flow with the code to obtain an access code.


## Thanks
Some code written is based on existing ones in these two projects: [keycloak-sms-provider](https://github.com/mths0x5f/keycloak-sms-provider)
and [keycloak-phone-authenticator](https://github.com/FX-HAO/keycloak-phone-authenticator). Certainly I would have many problems
coding all those providers blindly. Thank you!