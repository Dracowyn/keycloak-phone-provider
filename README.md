# Keycloak Phone Provider

+ 支持短信验证码
+ 用短信验证码注册
+ 通过短信验证码验证


通过这个插件，**你可以使用发送手机短信验证码的方式来执行认证策略**。
支持多个短信服务商，并采用的模块化部署。

目前此插件能的事：
+ 检查一个电话号码的所有权（表格和HTTP API）
+ 使用短信作为2FA方法的第二个因素（浏览器流程）
+ 通过短信验证码重置密码 (测试)
+ 通过短信验证码认证（HTTP API）
+ 每个人都通过验证码验证，自动创建用户(HTTP API)
+ 使用手机号注册
+ 只允许用手机号注册（用户名是电话号码）
+ 注册添加用户属性与redirect_uri参数

这个插件将使用两个用户属性：phoneNumberVerified（bool）和phoneNumber（str）
许多用户可以有相同的电话号码，但只有一个人在验证过程结束时获得phoneNumberVerified = true
这适应了预付费号码的使用情况，如果不活动的时间太长，这些号码会被回收。

## 客户端:

此为原作者的安卓手机客户端项目 [KeycloakClient](https://github.com/cooper-lyt/KeycloakClient) 

## 兼容性

最初是使用16.1.0版本的Keycloak作为基线开发的，未测试其他非默认的用户存储，如Kerberos或LDAP。

## 使用方法

**安装教程:**
 
  1. 将本项目内容下载到本地，执行mvn install进行安装，之后分别进入
     keycloak-phone-provider、keycloak-sms-provider-dummy、以及所需的短信接口例如阿里云
     执行mvn package编译打包，将module.xml以及后缀是-with-dependencies.jar
     重命名删去-with-dependencies，并将原来不带-with-dependencies的删除；
    之后将所得到的两个文件放入keycloak/modules/项目名（一个文件夹就是一个项目）/main 。
  2. 打开你的standalone.xml，在keycloak系统的模块声明中包括基本模块和至少一个短信服务提供商。
     添加属性以覆盖所选服务提供商的默认值和验证码的到期时间。
  3. 启动Keycloak。

i. 添加模块
```xml
<subsystem xmlns="urn:jboss:domain:keycloak-server:1.1">
    <web-context>auth</web-context>
    <providers>
        <provider>classpath:${jboss.home.dir}/providers/*</provider>
        <provider>module:keycloak-phone-provider</provider>
        <provider>module:keycloak-sms-provider-dummy</provider>
        <provider>module:keycloak-sms-provider-短信服务商</provider>
    </providers>
...
```
ii. 设置短信服务商以及验证码发送时间间隔。
```xml
<spi name="phoneMessageService">
    <provider name="default" enabled="true">
        <properties>
            <!-- 短信服务商使用小写，比如说aliyun -->
            <property name="service" value="短信服务商"/>
            <property name="tokenExpiresIn" value="60"/>
        </properties>
    </provider>
</spi>
```
iii. 设置短信模板ID、短信签名、accessKeyID、accessSecret
```xml
<spi name="messageSenderService">
    <!-- 短信服务商使用小写，比如说aliyun -->
    <provider name="短信服务商" enabled="true">
        <properties>
            <!-- 短信模板 -->
            <property name="DEFAULT_TEMPLATE" value="短信模板ID"/>
            <!-- 短信签名 -->
            <property name="DEFAULT_SIGNNAME" value="短信签名"/>
            <property name="accessKeyId" value="accessKeyId"/>
            <property name="accessSecret" value="accessSecret"/>
        </properties>
    </provider>
</spi>
```
iiii. 设置极验id和key
```xml
<spi name="captchaService">
    <provider name="geetest" enabled="true">
        <properties>
            <!-- 如果id为空则为宕机模式 -->
            <property name="id" value="id"/>
            <property name="key" value="key"/>
        </properties>
    </provider>
</spi>
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
```http://<addr>/auth/realms/<realm name>/protocol/openid-connect/registrations?client_id=<client id>&response_type=code&scope=openid%20email&redirect_uri=<redirect_uri>```


**About the API endpoints:** 

You'll get 2 extra endpoints that are useful to do the verification from a custom application.

  + ```GET /auth/realms/{realmName}/sms/verification-code?phoneNumber=+5534990001234``` (To request a number verification. No auth required.)
  + ```POST /auth/realms/{realmName}/sms/verification-code?phoneNumber=+5534990001234&code=123456``` (To verify the process. User must be authenticated.)

You'll get 2 extra endpoints that are useful to do the access token from a custom application.
  + ```GET /auth/realms/{realmName}/sms/authentication-code?phoneNumber=+5534990001234``` (To request a number verification. No auth required.)
  + ```POST /auth/realms/shuashua/protocol/openid-connect/token```
    ```Content-Type: application/x-www-form-urlencoded```
    ```grant_type=password&phone_number=$PHONE_NUMBER&code=$VERIFICATION_CODE&client_id=$CLIENT_ID&client_secret=CLIENT_SECRECT```


And then use Verification Code authentication flow with the code to obtain an access code.


## Thanks
Some code written is based on existing ones in these two projects: [keycloak-sms-provider](https://github.com/mths0x5f/keycloak-sms-provider)
and [keycloak-phone-authenticator](https://github.com/FX-HAO/keycloak-phone-authenticator). Certainly I would have many problems
coding all those providers blindly. Thank you!