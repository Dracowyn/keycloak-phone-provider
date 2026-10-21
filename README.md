# Keycloak (Quarkus 26.x.x) Phone Provider

此项目原作者并不是我，项目源地址：https://github.com/cooperlyt/keycloak-phone-provider

我们团队是在：https://github.com/cooperlyt/keycloak-phone-provider/tree/10.0.2

也就是使用Keycloak的11.0.3版本作为基线开发的版本基础上为了做定制化需求做了二开，加入了人机验证geetest、腾讯云验证码，国际区号选择功能，并将基线升级至
Keycloak 26 版本。

本插件运行环境要求：

+ Keycloak 26.x.x（当前基线 26.6.4）
+ Java 17 或 21（编译目标为 Java 17，推荐运行在 21）

## 项目前端

前端项目地址：https://gitee.com/Dracowyn/keycloak-phone-provider-frontend

## 主要功能

~~+ 支持多种短信服务商~~(这个没做，其它服务商可以参考keycloak-sms-provider-dummy的代码去实现)

+ 支持国际区号选择
+ 支持人机验证(极验)
+ 支持短信验证码重置密码
+ 支持短信验证码认证
+ 支持自动创建用户
+ 支持手机号注册
+ 支持只允许用手机号登录
+ 支持注册时添加用户属性与redirect_uri参数
+ 支持归属地黑名单检测
+ 支持自定义短信模板
+ 支持自定义短信签名

## 客户端

此为原作者的安卓手机客户端项目 [KeycloakClient](https://github.com/cooper-lyt/KeycloakClient)

## 使用方法

### 安装教程

#### 1. 添加模块

```bash
# 编译后将target/providers目录中的文件拷贝到Keycloak根目录下的providers即可
# 注意：除了target/providers/keycloak-captcha-provider-recaptcha.jar（因为这个没做适配）
cp target/providers/*.jar $KEYCLOAK_HOME/providers/
```

#### 2. 配置keycloak.conf

在keycloak根目录下conf/keycloak.conf添加以下信息：

```properties
# 短信发送服务商
spi-phone-provider-config-sender-service=Aliyun
# 验证码有效期（秒）
spi-phone-provider-config-token-expires=300
# 默认区号
spi-phone-provider-config-default-areacode=86
# 区号配置信息
spi-phone-provider-config-areacode-config=${kc.home.dir:}/conf/areacode.json
# 锁定区号
spi-phone-provider-config-area-locked=false
# 自助修改/解绑手机号 REST 接口需明确启用，默认关闭
spi-phone-provider-config-profile-update-enabled=false
```

#### 3. 设置短信模板ID、短信签名、accessKeyID、accessSecret

```properties
# 短信验证码模板
spi-message-sender-service-aliyun-DEFAULT_TEMPLATE=你的短信模板ID
# 短信签名
spi-message-sender-service-aliyun-DEFAULT_SIGNNAME=你的短信签名
# 阿里云ID与Key
spi-message-sender-service-aliyun-access-key-id=你的AccessKeyId
spi-message-sender-service-aliyun-access-secret=你的AccessKeySecret
```

#### 4. 设置极验ID和key（不推荐使用）

```properties
# 极验ID和key
spi-captcha-service-geetest-id=你的极验ID
spi-captcha-service-geetest-key=你的极验Key
```

#### 5. 设置号码归属地黑名单检测（可选）

```properties
# 是否开启号码归属地黑名单检测
spi-phone-provider-config-location-verify=true
# 号码归属地检测APPCODE
# 用的是阿里云的手机号码归属地查询服务
# 购买地址：https://market.aliyun.com/products/57126001/cmapi022206.html
spi-phone-provider-config-location-appcode=你的APPCODE
# 号码归属地黑名单（中文务必使用unicode编码）英文逗号分隔
spi-phone-provider-config-location-black-list=\u865a\u62df
```

#### 6. 腾讯云验证码容灾票据配置（可选）

腾讯云验证码前端 SDK 加载失败时会在本地生成无法通过腾讯云校验的"容灾票据"（`trerror_`/`terror_`前缀），后端识别到后按下面的开关决定放行还是拒绝，默认放行以保证可用性：

```properties
# 是否放行前端生成的容灾票据（默认true放行；安全敏感场景建议显式设为false）
spi-captcha-service-tencent-allow-disaster-ticket=false
```

> 该开关是"可用性 vs 安全性"的取舍：设为`true`（默认）时，攻击者可自行构造符合前缀规则的字符串绕过人机验证，需结合短信侧频控（见[API_MIGRATION_GUIDE.md](./API_MIGRATION_GUIDE.md)中的安全行为变更）综合评估风险。

### 配置认证流程

#### 手机短信OTP认证

1. 在管理控制台进入 **Authentication(身份验证)** 页面
2. 复制 **Browser** 流程并重命名为 **Browser with Phone**
3. 在菜单中添加执行器，选择 `OTP Over SMS` 作为新的执行器
4. 将此流程副本绑定为默认的浏览器流程
5. 在 **Required Actions(必需的操作)** 标签页开启 `Update Phone Number` 和 `Configure OTP over SMS` 操作

#### 仅使用手机号登录或通过端点获取访问令牌

在 **Authentication > Flows** 下：

+ 复制 'Direct Grant' 流程为 'Direct grant with phone' 流程
+ 在 'Provide Phone Number' 行点击 'Actions > Add execution'
+ 在 'Provide Verification Code' 行点击 'Actions > Add execution'
+ 删除或禁用其他项目
+ 将 'Provide Phone Number' 和 'Provide Verification Code' 都设置为 'REQUIRED'

在 **Clients > $YOUR_CLIENT > Authentication Flow Overrides** 或 **Authentication > Bindings** 下：
将 Direct Grant Flow 设置为 'Direct grant with phone'

#### 任何手机号码认证（如果用户不存在则通过手机号创建用户）

在 **Authentication > Flows** 下：

+ 复制 'Direct Grant' 流程为 'Direct grant everybody with phone' 流程
+ 在 'Authentication Everybody By Phone' 行点击 'Actions > Add execution'
+ 删除或禁用其他项目
+ 将 'Authentication Everybody By Phone' 设置为 'REQUIRED'

在 **Clients > $YOUR_CLIENT > Authentication Flow Overrides** 或 **Authentication > Bindings** 下：
将 Direct Grant Flow 设置为 'Direct grant everybody with phone'

#### 重置凭据

待编写

#### 手机号注册支持

在 **Authentication > Flows** 下：

+ 从注册创建流程：
  复制 'Registration' 流程为 'Registration fast by phone' 流程

+ （可选）将手机号用作新用户的用户名：
  删除或禁用 'Registration User Creation'
  在 'Registration Fast By Phone Registration Form > Actions > Add execution' 中添加 'Registration Phone As Username
  Creation'
  将此项移动到第一位

+ 将手机号添加到配置文件：
  在 'Registration Fast By Phone Registration Form > Actions > Add execution' 中添加 'Phone Validation'

+ （可选）隐藏除手机号外的所有其他字段：
  在 'Registration Fast By Phone Registration Form > Actions > Add execution' 添加 'Registration Least'

+ （可选）读取查询参数并添加到用户属性：
  在 'Registration Fast By Phone Registration Form > Actions > Add execution' 中添加 'Query Parameter Reader'
  在 'Registration Fast By Phone Registration Form > Actions > configure' 中配置接受的参数名

+ （可选）隐藏密码字段：
  删除或禁用 'Password Validation'

将所有添加的项目设置为必需(Required)。

在 **Authentication > Bindings** 下：
将 Registration Flow 设置为 'Registration fast by phone'

在 **Realm Settings > Themes** 下：
将 Login Theme 设置为 'phone'

## 认证设置

### 浏览器手机认证

![](https://i.imgur.com/5UTcWXN.png)

### 手机号注册

![](https://i.imgur.com/vQT4gSm.png)

### 手机重置凭据

![](https://i.imgur.com/R7cul0l.png)

### 测试注册链接

```
http://<地址>/realms/<域名>/protocol/openid-connect/registrations?client_id=<客户端ID>&response_type=code&scope=openid%20email&redirect_uri=<重定向URI>
```

## API端点说明

> **重要提示**: 从v2.0.0版本开始，所有API接口格式已统一，采用标准RESTful风格。错误响应使用标准HTTP状态码，不再使用统一的200状态码。

### 短信配置接口

#### 获取短信配置

```http
GET /realms/{realmName}/sms
```

**响应示例**:

```json
{
  "tokenExpires": 300,
  "defaultAreaCode": "86",
  "areaLocked": false,
  "allowUnset": true,
  "areaCodeList": [...]
}
```

### 验证码发送接口

#### 发送验证码（注册）

```http
POST /realms/{realmName}/sms/registration-code
Content-Type: application/json

{
  "areaCode": "86",
  "phoneNumber": "13800138000",
  "geetest_challenge": "...",
  "geetest_validate": "...",
  "geetest_seccode": "..."
}
```

**成功响应** (200 OK):

```json
{
  "expiresIn": 1737025200000,
  "resendExpires": 1737024360000
}
```

**错误响应** (400 Bad Request):

```json
{
  "code": "PHONE_NUMBER_REQUIRED",
  "message": "手机号码不能为空",
  "timestamp": "2026-01-15T10:30:00Z"
}
```

#### 发送验证码（登录/认证）

```http
POST /realms/{realmName}/sms/authentication-code
POST /realms/{realmName}/sms/login-code
POST /realms/{realmName}/sms/reset-code
POST /realms/{realmName}/sms/verification-code
```

参数格式与注册验证码相同。

#### 查询重发限制时间

```http
GET /realms/{realmName}/sms/verification-code/resend-expires?areaCode=86&phoneNumber=13800138000
```

**响应示例** (200 OK):

```json
{
  "resendExpire": 1737024360000
}
```

### 验证码验证接口

#### 验证并绑定手机号

```http
POST /realms/{realmName}/sms/update-profile
Content-Type: application/json
Authorization: Bearer {access_token}

{
  "areaCode": "86",
  "phoneNumber": "13800138000",
  "code": "123456"
}
```

**成功响应**: 204 No Content

**错误响应** (401 Unauthorized):

```json
{
  "code": "AUTHENTICATION_REQUIRED",
  "message": "需要登录",
  "timestamp": "2026-01-15T10:30:00Z"
}
```

#### 取消绑定手机号

```http
POST /realms/{realmName}/sms/update-profile/unset
Authorization: Bearer {access_token}
```

**成功响应**: 204 No Content

### 人机验证配置接口

#### 获取极验配置

```http
GET /realms/{realmName}/geetest/code
```

**响应示例** (200 OK):

```json
{
  "type": "geetest",
  "geetestId": "your_geetest_id",
  "captchaAppId": "your_geetest_id"
}
```

> 响应中不含 `success` 字段（`CaptchaConfigResponse` 未保留该历史兼容字段）。

#### 获取腾讯云验证码配置

```http
GET /realms/{realmName}/tencent-captcha/code
```

**响应示例** (200 OK):

```json
{
  "type": "tencent",
  "captchaAppId": "your_captcha_app_id"
}
```

### OAuth2 Token接口

使用手机号和验证码获取访问令牌：

```http
POST /realms/{realmName}/protocol/openid-connect/token
Content-Type: application/x-www-form-urlencoded

grant_type=password&phone_number=$PHONE_NUMBER&code=$VERIFICATION_CODE&client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET
```

### 错误码说明

| 错误码                        | HTTP状态码 | 说明           |
|----------------------------|---------|--------------|
| PHONE_NUMBER_REQUIRED      | 400     | 手机号码不能为空     |
| PHONE_NUMBER_INVALID       | 400     | 手机号码格式不正确    |
| VERIFICATION_CODE_REQUIRED | 400     | 验证码不能为空      |
| VERIFICATION_CODE_INVALID  | 400     | 验证码不正确       |
| VERIFICATION_CODE_EXPIRED  | 400     | 验证码已过期       |
| CAPTCHA_REQUIRED           | 400     | 人机验证未完成      |
| CAPTCHA_INVALID            | 400     | 人机验证失败       |
| AREA_NOT_SUPPORTED         | 403     | 该地区暂不支持      |
| USER_NOT_FOUND             | 404     | 用户不存在        |
| AUTHENTICATION_REQUIRED    | 401     | 需要登录         |
| EMAIL_NOT_VERIFIED         | 400     | 邮箱未验证        |
| PHONE_UNSET_NOT_ALLOWED    | 403     | 不允许取消绑定手机号   |
| RESEND_TOO_SOON            | 429     | 重发冷却中，未到可重发时间（响应体`details.resendExpires`携带可重发的epoch毫秒时间戳） |
| SMS_SEND_LIMIT_EXCEEDED    | 429     | 同一手机号一小时内发送次数超限（与上面的重发冷却是两种独立限流） |
| INVALID_REQUEST            | 400     | JSON请求体解析失败   |
| SMS_SEND_FAILED            | 500     | 短信发送失败       |
| LOCATION_CHECK_FAILED      | 500     | 归属地检测失败      |
| ILLEGAL_PHONE_NUMBER       | 403     | 该手机号归属地不允许使用 |
| INTERNAL_ERROR             | 500     | 服务器内部错误      |

> `VERIFICATION_CODE_INVALID`（验证码不匹配）与 `VERIFICATION_CODE_EXPIRED`（验证码过期/未发起/因连续输错5次被作废）在"验证并绑定手机号"接口中是两种不同语义的错误；完整的错误码变更说明、`resendExpires`响应示例与验证码防爆破、腾讯云验证码容灾票据等安全行为变更详见 [API_MIGRATION_GUIDE.md](./API_MIGRATION_GUIDE.md)。

### 版本更新说明

**Keycloak 26 升级（当前）**:

- ✅ 基线从 Keycloak 25.x 升级到 26.6.4
- ✅ 清理 26.x 弃用 API：`AuthResult.getUser()` → `user()`、`JsonNode.fields()` → `properties()`
- ✅ 持久层由 `java.util.Date` 迁移到 `java.time.LocalDateTime`，移除弃用的 `@Temporal`
- ✅ 移除 `CaptchaConfigResponse` 的历史兼容字段，统一返回 `captchaAppId`
- 🔒 修复验证码明文被写入 info 日志与 Keycloak 事件详情的安全问题
- 🔒 验证码新增防爆破机制：同一验证码连续校验失败达5次即自动作废，须重新发送（数据库自动迁移新增`ATTEMPTS`列，随插件启动执行，无需人工介入）
- 🔒 验证码比对改为常量时间比较（`MessageDigest.isEqual`），避免时序侧信道
- 🛡️ 腾讯云验证码新增前端"容灾票据"识别与 `allowDisasterTicket` 开关，详见上文配置说明与 [API_MIGRATION_GUIDE.md](./API_MIGRATION_GUIDE.md)
- 🐞 修复凭据数据（credentialData）手工拼接 JSON 导致漏写 `areaCode`、结构错误的问题
- 🐞 修复腾讯云 `captchaType` 被硬编码 `9` 覆盖、配置项不生效的问题
- 🧵 修复区号缓存、极验服务状态、`JsonUtils` 的线程安全问题
- ⬆️ 升级依赖：BouncyCastle、阿里云 SDK、org.json、Lombok 等
- 🪶 移除 okhttp 依赖（改用 JDK 原生解析 `redirect_uri` 查询参数），核心 jar 由约 3.1MB 缩减至约 156KB

> ⚠️ 说明：以上改动已通过编译验证；User Profile 声明式属性、Liquibase changelog 等**运行时**行为建议在目标环境实测后再上生产。

**v2.0.0**:

- ✅ 统一所有API接口格式，采用RESTful风格
- ✅ 使用标准HTTP状态码表示响应状态
- ✅ 统一错误响应格式
- ✅ 成功响应直接返回数据对象，不再使用status字段
- ✅ 加入腾讯云验证码支持
- ⚠️ **破坏性更新**: 前端需要同步修改API调用代码

**v1.2.x (旧版本)**:

- 所有响应均返回200状态码
- 使用`status`字段表示成功/失败（1/0/-1/-2）
- 同时包含`error`和`errormsg`两个字段

## 感谢

部分代码基于这两个项目中的现有代码编写：[keycloak-sms-provider](https://github.com/mths0x5f/keycloak-sms-provider)
和 [keycloak-phone-authenticator](https://github.com/FX-HAO/keycloak-phone-authenticator)。如果盲写编写所有这些提供程序，我肯定会遇到很多问题。谢谢！


### Sending quota and self-service phone changes

The /realms/{realm}/sms/update-profile REST subresource is disabled by default,
including its update and unset operations. Enable profileUpdateEnabled=true
(spi-phone-provider-config-profile-update-enabled=true) only if the deployment
intentionally uses this module to manage phone ownership. This setting does not
enable or disable authentication flows. Integrations with their own account-linking
policy should leave this subresource disabled and separately keep unused login,
registration and MFA executions out of every reachable flow.

Before calling an SMS provider, the module reserves one of the existing four
send attempts per rolling hour for that realm, phone number and token purpose.
The check and reservation commit in a separate short database transaction. The
provider call holds no quota/realm lock. A consumed OTP, failed verification or
rolled-back request cannot refund an SMS that may already have been sent. Failed
or unknown delivery retains the reservation and blocks retry for the remaining
hour; successful delivery uses the provider cooldown with a minimum of 60 seconds.
A new request must send before it acquires this module's verification-token lock.

The migration adds PHONE_MESSAGE_SEND, which stores only send metadata, never
OTP values. It copies existing token metadata but cannot reconstruct sends that
older versions already deleted on consumption. Do not run old and new sending
implementations concurrently during an upgrade. Disable the sending entry points
before rollback; do not delete quota or token tables as a rollback procedure.
Metadata older than an hour is pruned for the active realm during reservation.
No deployment or real-message verification is implied by isolated tests.
