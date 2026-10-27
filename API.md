# 手机号与短信接口参考

本文面向前端与服务端集成开发者，按当前源码说明接口，对应版本 2.2.1；各版本引入的行为变化单独标注。部署、配置键与认证流程见 [README](README.md)，版本历史见 [CHANGELOG](CHANGELOG.md)。本文由原 `API_MIGRATION_GUIDE.md` 改名整理而来。

## 目录

- [基础约定](#基础约定)
- [端点总表](#端点总表)
- [短信配置](#短信配置)
- [发送短信验证码](#发送短信验证码)
- [查询重发时间](#查询重发时间)
- [绑定与解绑手机号](#绑定与解绑手机号)
- [人机验证配置](#人机验证配置)
- [OIDC 直授换取令牌](#oidc-直授换取令牌)
- [错误码全表](#错误码全表)
- [频控与验证码生命周期](#频控与验证码生命周期)
- [安全行为变更](#安全行为变更)
- [腾讯云验证码容灾票据](#腾讯云验证码容灾票据)
- [前端迁移步骤](#前端迁移步骤)
- [实现边界](#实现边界)

## 基础约定

### 基础路径与内容类型

所有插件资源位于 `<base-url>/realms/<realm>` 下；`<base-url>` 是 Keycloak 对外基础地址，包含部署时配置的相对路径（如有）。表中 `/sms`、`/geetest` 等均相对此路径，OIDC 端点也在同一 realm 下。

| 项目 | 约定 |
| --- | --- |
| 发送、绑定、重发时间查询的 POST 请求 | 接受 `application/json` 或 `application/x-www-form-urlencoded`；JSON 请使用对象，电话号码与验证码使用字符串 |
| GET 请求 | 参数放查询字符串；没有请求体 |
| 成功响应 | `200` 直接返回 JSON 对象；绑定、解绑成功为 `204`，没有响应体 |
| 时间 | `expiresIn`、`resendExpires`、`resendExpire` 均为 Unix epoch **毫秒时间戳**，不是剩余秒数；唯有配置中的 `tokenExpires` 是秒数 |
| 手机号 | 拆分为 `areaCode` 与 `phoneNumber`；区号不带 `+`，号码不重复携带区号；用户属性中保存为 `+区号 空格号码` |
| 验证码 | 请求字段始终是 `smsCode`，不是 `code`；错误响应里的 `code` 是错误码，不是短信验证码 |
| 示例占位 | 将 `<base-url>`、`<realm>`、`<client-id>`、`<client-secret>`、`13800000000`、`{{SMS_CODE}}` 替换为真实值；人机验证参数从对应 SDK 结果取得 |

**旧字段名的兼容范围有限**：发送接口（JSON 与 form）及手机号直授读取 `PhoneNumber(MultivaluedMap)`，接受 `area_code`、`phone_number`，同名驼峰字段非 `null` 时优先使用驼峰字段。`resend-expires` 与 `update-profile` 使用显式参数读取，**只接受 `areaCode`、`phoneNumber`**。不能把此兼容性理解成所有接口均接受下划线字段。

### 认证方式

| 端点 | 认证与人机验证 |
| --- | --- |
| `GET /sms`、各 `resend-expires` | 无需登录 |
| 五个短信发送端点 | 无需登录；按当前选中的人机验证实现校验提交参数，详见[发送参数](#发送参数与人机验证字段) |
| `/sms/update-profile`、`/sms/update-profile/unset` | **只接受 Keycloak Identity Cookie 登录会话，不支持 Bearer Token**；仅携带 `Authorization: Bearer …` 仍会返回 `401 AUTHENTICATION_REQUIRED` |
| `/geetest/code`、`/tencent/code`、`/recaptcha/code` | 构造资源时先尝试 Identity Cookie，再尝试 Bearer；均未要求必须登录才能获取配置 |
| OIDC `/protocol/openid-connect/token` | 按 Keycloak 客户端认证与直授流程处理，不要求 Identity Cookie |

发送时，正确的 `client_id` 与 `client_secret` 可使人机验证的失败结果不再阻止发送。自 2.2.1 起只认当前 realm 中**已启用的机密客户端**（非公共客户端）且 `validateSecret` 成功；部署时应只在可信后端保存和使用机密客户端密钥，绝不可把密钥发给浏览器。**人机验证方法仍先被调用**，这不是“不调用第三方验证服务”的保证，也不会绕过号码、区号、用户、频控或发送商检查。

**已登录会话不会自动跳过人机验证**：三种实现收到 `AuthResult` 后只是取得用户 ID，仍会执行各自的参数和校验逻辑。没有部署任何 `CaptchaService` 时发送资源跳过人机验证；极验缺少 ID 或密钥、腾讯缺少四项必填键之一时也会放行。配置缺失不是安全模式，部署检查见 [README](README.md)。

### 统一错误格式

插件资源中显式构建的错误使用 `ApiError`，`Content-Type: application/json`：

```http
HTTP/1.1 400 Bad Request
Content-Type: application/json

{
  "code": "PHONE_NUMBER_REQUIRED",
  "message": "手机号码不能为空",
  "timestamp": "2026-10-27T10:30:00Z"
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | 字符串 | 稳定的枚举名称，适合前端分支与本地化映射 |
| `message` | 字符串 | 默认中文文案或当前调用处给出的消息；发送商或服务层消息可能是英文，不应据其文本判断错误类型 |
| `details` | 对象，可选 | 当前重发冷却错误可附 `resendExpires`；没有详情时省略 |
| `timestamp` | 字符串 | 服务端 `Instant.now().toString()` 的时间 |

本文列出的是资源类的明确分支，不保证 Keycloak/JAX-RS 在请求尚未进入资源方法时产生的错误、未捕获异常也使用 `ApiError`。**OIDC Token 接口使用 OAuth 错误对象，不属于此格式**，见[直授说明](#oidc-直授换取令牌)。

## 端点总表

| 方法 | 路径 | 用途 | 认证要求 |
| --- | --- | --- | --- |
| GET | `/sms` | 前端短信配置与区号列表 | 无 |
| POST | `/sms/registration-code` | 注册验证码，类型 `REGISTRATION` | 人机验证或可信客户端校验 |
| POST | `/sms/authentication-code` | 短信认证、直授验证码，类型 `OTP` | 同上 |
| POST | `/sms/login-code` | 浏览器手机号登录，类型 `LOGIN` | 同上 |
| POST | `/sms/reset-code` | 重置凭据，类型 `RESET` | 同上 |
| POST | `/sms/verification-code` | 验证或绑定手机号、首次配置短信 MFA，类型 `VERIFY` | 同上 |
| GET、POST | `/sms/registration-code/resend-expires` | 查询注册验证码重发时间 | 无 |
| GET、POST | `/sms/authentication-code/resend-expires` | 查询认证验证码重发时间 | 无 |
| GET、POST | `/sms/login-code/resend-expires` | 查询登录验证码重发时间 | 无 |
| GET、POST | `/sms/reset-code/resend-expires` | 查询重置验证码重发时间 | 无 |
| GET、POST | `/sms/verification-code/resend-expires` | 查询验证验证码重发时间 | 无 |
| POST | `/sms/update-profile` | 校验 `VERIFY` 验证码并绑定手机号 | Identity Cookie，不支持 Bearer |
| POST | `/sms/update-profile/unset` | 解绑手机号并删除短信 OTP 凭据 | Identity Cookie，不支持 Bearer |
| GET、POST、OPTIONS | `/geetest/code` | 极验初始化配置、CORS 预检 | 无强制登录要求 |
| GET、POST、OPTIONS | `/tencent/code` | 腾讯云应用配置、CORS 预检 | 无强制登录要求 |
| GET、OPTIONS | `/recaptcha/code` | reCAPTCHA 配置资源、CORS 预检 | 无强制登录要求；**未实现 POST** |
| POST | `/protocol/openid-connect/token` | 手机号直授换取令牌 | 客户端认证与已绑定直授流程 |

## 短信配置

### GET /sms

没有参数，也不需要认证。

```bash
curl '<base-url>/realms/<realm>/sms'
```

成功为 `200`；下例区号列表仅展示一个条目，实际来自部署的 `areacode.json`：

```json
{
  "tokenExpires": 300,
  "defaultAreaCode": "86",
  "areaLocked": false,
  "allowUnset": true,
  "areaCodeList": [
    {
      "areaCode": 86,
      "countryCode": "CN",
      "name": { "zh": "中国", "en": "China" }
    }
  ]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `tokenExpires` | 整数 | 配置的短信验证码有效期，秒；默认 300 |
| `defaultAreaCode` | 字符串 | 默认区号，默认 `"86"`；不是请求省略区号时的服务端补值 |
| `areaLocked` | 布尔值 | 前端区号锁定配置，默认 `false`；发送端仍检查实际区号是否在列表中 |
| `allowUnset` | 布尔值 | 是否允许解绑，默认 `true`；解绑还需满足已验证邮箱条件 |
| `areaCodeList` | 数组 | 每项包含数字 `areaCode`、字符串 `countryCode`、语言到名称的对象 `name` |

失败：区号文件读取或解析抛出 `IOException` 时，返回 `500 INTERNAL_ERROR`，消息为“获取短信配置失败”。未成功加载区号列表时，发送接口的区号许可检查也无法通过。文件路径和部署方法见 [README](README.md)。

## 发送短信验证码

### 发送参数与人机验证字段

以下参数表适用于五个发送端点，JSON 和 form 的字段名一致。

| 参数 | 是否必填 | 说明 |
| --- | --- | --- |
| `areaCode` | 是 | 1–4 位纯数字区号，且须在已加载的许可列表中；兼容 `area_code` |
| `phoneNumber` | 是 | 非空手机号字符串，不附区号；兼容 `phone_number`；当前资源没有通用的手机号长度或全数字格式校验 |
| `client_id` | 否 | 可信后端可与 `client_secret` 一起提交，详见[认证方式](#认证方式) |
| `client_secret` | 否 | 与客户端匹配的密钥，只能保存在可信后端 |
| 人机验证字段 | 按实现 | 见下表；并非把所有厂商参数一起提交，也不是发送时提交 `smsCode` |

| 服务商 | 随发送请求提交的字段 | 来源与条件 |
| --- | --- | --- |
| 极验 v3 | `geetest_challenge`、`geetest_validate`、`geetest_seccode` | 极验 v3 SDK 二次验证结果；配置 `version=3`（默认） |
| 极验 v4 | `captcha_output`、`gen_time`、`lot_number`、`pass_token` | 极验 v4 SDK 结果；配置 `version=4` |
| 腾讯云 | `ticket`、`randstr` | 腾讯云 SDK 结果；完整后端配置下两项均须存在 |
| reCAPTCHA | `g-recaptcha-response` | reCAPTCHA 前端结果令牌；后端使用配置的 secret 向验证服务提交 |

服务端通过 `session.getProvider(CaptchaService.class)` 取得当前实现，不根据某个请求里出现了哪个厂商字段自动切换实现。人机验证配置键及选择方式见 [README](README.md)。

### 五个端点的用途与用户要求

| 方法与路径 | 验证码类型 | 号码是否必须已属于已验证用户 | 后续消费场景 |
| --- | --- | --- | --- |
| `POST /sms/registration-code` | `REGISTRATION` | 否；发送阶段不负责检查号码是否已注册 | 注册表单动作 |
| `POST /sms/authentication-code` | `OTP` | 通常是；启用 Everybody 执行器时允许新号码 | `Provide verification code`、Everybody 直授、短信 MFA |
| `POST /sms/login-code` | `LOGIN` | 是 | 两种浏览器手机号登录表单 |
| `POST /sms/reset-code` | `RESET` | 是 | 手机号重置凭据流程 |
| `POST /sms/verification-code` | `VERIFY` | 否 | `/sms/update-profile`、`CONFIGURE_SMS_OTP` |

“存在用户”在这里是 `UserUtils.findUserByPhone` 找到相同完整手机号且 `phoneNumberVerified=true` 的用户。`authentication-code` 的例外条件是：realm 顶层认证流程递归存在已启用的 `everybody-phone-authenticator`，其父执行流程也未禁用；发送资源检查的是流程是否存在，不是本次请求的客户端最终会使用哪个直授流程。

只有 `authentication-code`、`login-code`、`reset-code` 会因上述用户查找失败返回 `USER_NOT_FOUND`；`registration-code` 与 `verification-code` 不会。

### 各端点请求示例

以下均展示极验 v3 参数；使用其他实现时，将三项极验字段整体替换为上表对应字段。各端点的成功响应与公共错误分支相同。

注册验证码（JSON）：

```http
POST <base-url>/realms/<realm>/sms/registration-code
Content-Type: application/json

{
  "areaCode": "86",
  "phoneNumber": "13800000000",
  "geetest_challenge": "<SDK返回的challenge>",
  "geetest_validate": "<SDK返回的validate>",
  "geetest_seccode": "<SDK返回的seccode>"
}
```

认证验证码（form，适用于后面的手机号直授）：

```bash
curl -X POST '<base-url>/realms/<realm>/sms/authentication-code' \
  --data-urlencode 'areaCode=86' \
  --data-urlencode 'phoneNumber=13800000000' \
  --data-urlencode 'geetest_challenge=<SDK返回的challenge>' \
  --data-urlencode 'geetest_validate=<SDK返回的validate>' \
  --data-urlencode 'geetest_seccode=<SDK返回的seccode>'
```

浏览器手机号登录验证码（form）：

```http
POST <base-url>/realms/<realm>/sms/login-code
Content-Type: application/x-www-form-urlencoded

areaCode=86&phoneNumber=13800000000&geetest_challenge=<SDK返回的challenge>&geetest_validate=<SDK返回的validate>&geetest_seccode=<SDK返回的seccode>
```

重置凭据验证码（JSON）：

```http
POST <base-url>/realms/<realm>/sms/reset-code
Content-Type: application/json

{
  "areaCode": "86",
  "phoneNumber": "13800000000",
  "geetest_challenge": "<SDK返回的challenge>",
  "geetest_validate": "<SDK返回的validate>",
  "geetest_seccode": "<SDK返回的seccode>"
}
```

绑定或首次配置 MFA 所需验证码（JSON）：

```http
POST <base-url>/realms/<realm>/sms/verification-code
Content-Type: application/json

{
  "areaCode": "86",
  "phoneNumber": "13800000000",
  "geetest_challenge": "<SDK返回的challenge>",
  "geetest_validate": "<SDK返回的validate>",
  "geetest_seccode": "<SDK返回的seccode>"
}
```

form 示例的占位值必须做 URL 编码；使用 `curl --data-urlencode` 可交由客户端处理编码。

### 发送响应与错误

五个端点发送成功均为 `200`，响应不含 `status` 或短信验证码本身：

```json
{
  "expiresIn": 1793097300000,
  "resendExpires": 1793097060000
}
```

| HTTP | 错误码 | 触发条件 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | JSON 解析抛出 `IOException`，例如 JSON 语法错误 |
| 400 | `PHONE_NUMBER_REQUIRED` | 区号或号码为空 |
| 400 | `CAPTCHA_REQUIRED` | 当前人机验证实现返回不通过，且未通过可信客户端密钥检查；缺参数与校验失败均使用此码，不返回 `CAPTCHA_INVALID` |
| 400 | `PHONE_NUMBER_INVALID` | 区号不是 1–4 位纯数字；当前不是通用号码格式校验 |
| 403 | `AREA_NOT_SUPPORTED` | 区号不在许可列表内，或区号列表未成功加载 |
| 404 | `USER_NOT_FOUND` | 仅适用于上表要求已存在用户的三个端点 |
| 429 | `SMS_SEND_LIMIT_EXCEEDED` | 最近一小时的号码、类型记录超过源码阈值；当前边界见[频控](#频控与验证码生命周期) |
| 429 | `RESEND_TOO_SOON` | 重发冷却未结束；可含 `details.resendExpires` |
| 403 | `ILLEGAL_PHONE_NUMBER` | 启用归属地检测且命中黑名单 |
| 500 | `SMS_SEND_FAILED` | 指定短信发送商未部署、发送商返回失败，或发送异常被转为失败结果；`message` 可能来自发送商 |

资源按“号码非空 → 人机验证/可信客户端 → 区号格式 → 区号许可 → 用户存在性 → 发送服务”顺序执行；发送服务先检查小时记录数，再检查冷却。因此同一请求有多项问题时，只会看到先命中的分支。

## 查询重发时间

### GET /sms/{发送端点}/resend-expires

### POST /sms/{发送端点}/resend-expires

`{发送端点}` 必须是表中五个实际子路径之一，不是 `OTP` 等枚举值：

| 实际路径 | 对应类型 |
| --- | --- |
| `/sms/registration-code/resend-expires` | `REGISTRATION` |
| `/sms/authentication-code/resend-expires` | `OTP` |
| `/sms/login-code/resend-expires` | `LOGIN` |
| `/sms/reset-code/resend-expires` | `RESET` |
| `/sms/verification-code/resend-expires` | `VERIFY` |

无需认证或人机验证。三种请求形式均要求 `areaCode`、`phoneNumber` 非空，**不接受 legacy 字段名**，也不在此接口执行发送时的区号格式、区号许可或用户存在性检查。

GET 查询示例：

```bash
curl --get '<base-url>/realms/<realm>/sms/verification-code/resend-expires' \
  --data-urlencode 'areaCode=86' \
  --data-urlencode 'phoneNumber=13800000000'
```

POST JSON 示例：

```http
POST <base-url>/realms/<realm>/sms/verification-code/resend-expires
Content-Type: application/json

{"areaCode":"86","phoneNumber":"13800000000"}
```

POST form 示例：

```bash
curl -X POST '<base-url>/realms/<realm>/sms/verification-code/resend-expires' \
  --data-urlencode 'areaCode=86' \
  --data-urlencode 'phoneNumber=13800000000'
```

存在尚未结束的冷却时返回 `200`：

```json
{"resendExpire":1793097060000}
```

注意这里是单数 `resendExpire`，不是发送响应的 `resendExpires`。此接口查询的是最新发送记录的冷却，而非验证码是否仍可校验：验证码因输错作废后，只要冷却尚未结束仍可查询成功。

| HTTP | 错误码 | 触发条件 |
| --- | --- | --- |
| 400 | `PHONE_NUMBER_REQUIRED` | 区号或号码为空 |
| 400 | `INVALID_REQUEST` | POST JSON 解析失败 |
| 404 | `TOKEN_NOT_FOUND` | 无发送记录、冷却已结束，或服务层无法取得有效冷却；**不返回时间戳 0** |

## 绑定与解绑手机号

### POST /sms/update-profile

必须由携带有效 Keycloak Identity Cookie 的浏览器会话发起。验证码应先从 `/sms/verification-code` 获取；`registration-code`、`authentication-code` 等类型不能替代。

| 参数 | 是否必填 | 说明 |
| --- | --- | --- |
| `areaCode` | 是 | 区号字符串；不接受 `area_code` |
| `phoneNumber` | 是 | 手机号字符串；不接受 `phone_number` |
| `smsCode` | 是 | `VERIFY` 类型验证码；全空白视为缺失，非空值不会在比对前自动去掉空格 |

JSON 请求示例，浏览器自动携带已有会话 Cookie；不是提交 Bearer：

```javascript
const response = await fetch('<base-url>/realms/<realm>/sms/update-profile', {
  method: 'POST',
  credentials: 'include',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    areaCode: '86',
    phoneNumber: '13800000000',
    smsCode: '{{SMS_CODE}}'
  })
});
if (response.status !== 204) {
  const error = await response.json();
  throw new Error(`${error.code}: ${error.message}`);
}
```

相同会话下的 form 请求体为：

```text
areaCode=86&phoneNumber=13800000000&smsCode={{SMS_CODE}}
```

成功：

```http
HTTP/1.1 204 No Content
```

成功会消费当前 realm、该号码的 `VERIFY` 类型验证码记录，写入用户的 `phoneNumber`、`phoneNumberVerified=true`，取消同号码其他账户的验证状态，移除更新手机号必需操作，并更新已有短信 OTP 凭据中的号码数据。此接口不是单纯的“测试验证码是否正确”，不能用作没有副作用的预检查。

| HTTP | 错误码 | 全部明确失败分支 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | JSON 解析失败；发生在会话检查之前 |
| 401 | `AUTHENTICATION_REQUIRED` | Identity Cookie 未认证；只有 Bearer 也属于此情况 |
| 400 | `PHONE_NUMBER_REQUIRED` | 已认证，但区号或号码为空 |
| 400 | `VERIFICATION_CODE_REQUIRED` | `smsCode` 缺失或全空白 |
| 400 | `VERIFICATION_CODE_INVALID` | 有有效 `VERIFY` 记录，但验证码不匹配；本次失败计数递增 |
| 400 | `VERIFICATION_CODE_EXPIRED` | 没有有效 `VERIFY` 流程：未发起、过期、已被消费或此前已因累计错误作废 |

第 5 次错误提交本身仍属于“不匹配”，返回 `VERIFICATION_CODE_INVALID` 并作废当前记录；在没有其他有效记录的通常情况下，之后再提交才返回 `VERIFICATION_CODE_EXPIRED`，即便输入正确值也不能恢复该记录。

### POST /sms/update-profile/unset

接受 `application/json` 或 form；没有业务参数，不需要短信验证码，但必须携带 Identity Cookie。

```javascript
const response = await fetch('<base-url>/realms/<realm>/sms/update-profile/unset', {
  method: 'POST',
  credentials: 'include',
  headers: { 'Content-Type': 'application/json' },
  body: '{}'
});
if (response.status !== 204) {
  const error = await response.json();
  throw new Error(`${error.code}: ${error.message}`);
}
```

成功为 `204 No Content`。删除 `phoneNumber`、`phoneNumberVerified` 两项用户属性，以及该用户全部 `phone-otp` 类型短信 OTP 凭据（凭据 provider ID 是 `sms-otp`，不要与存储类型混淆）。

| HTTP | 错误码 | 按执行顺序列出的失败分支 |
| --- | --- | --- |
| 401 | `AUTHENTICATION_REQUIRED` | 未通过 Identity Cookie 认证；不支持 Bearer |
| 403 | `PHONE_UNSET_NOT_ALLOWED` | `spi-phone-provider-config-allow-unset=false` |
| 400 | `EMAIL_NOT_VERIFIED` | 当前用户邮箱未验证；该分支消息为“取消绑定手机号前需要先验证邮箱” |

该方法不读取请求 JSON 内容，不执行绑定接口的 JSON 解析逻辑；没有单独的“尚未绑定手机号”错误。不要把此接口理解为自动验证邮箱或删除用户。

## 人机验证配置

### 共用认证、方法与 CORS

| 资源 | GET | POST | OPTIONS | 请求体 |
| --- | --- | --- | --- | --- |
| `/geetest/code` | 获取配置 | 与 GET 相同 | 预检 | 不读取业务参数 |
| `/tencent/code` | 获取配置 | 与 GET 相同 | 预检 | 不读取业务参数 |
| `/recaptcha/code` | 获取配置 | **未实现** | 预检 | 不读取业务参数 |

资源先尝试 Identity Cookie，失败后尝试 Bearer；配置获取没有强制登录检查。各资源均向当前默认 `CaptchaService` 获取前端数据，因此路径名字不是显式选择服务实现的参数。请安装并选择与前端集成一致的实现，不要因多个资源路径同时存在就假定可以自动混用厂商。

三类资源的 CORS 逻辑均读取 `session.getContext().getClient()`，在存在客户端上下文时按其 `webOrigins` 以 glob 匹配请求 `Origin`：

| 条件或响应头 | 源码行为 |
| --- | --- |
| 没有客户端上下文，或来源不匹配 | 不添加允许跨域响应头；OPTIONS 仍可返回 200，不能仅凭 HTTP 状态判断浏览器是否允许跨域 |
| `Access-Control-Allow-Origin` | 写入命中的 `webOrigins` 配置值，不是无条件回显请求 Origin |
| `Access-Control-Allow-Methods` | 匹配来源后，若请求有 `Access-Control-Request-Method`，将其原样写入 |
| `Access-Control-Allow-Headers` | 匹配来源后，若请求有 `Access-Control-Request-Headers`，将其原样写入 |
| `Access-Control-Allow-Credentials` | 这些资源没有设置；不能据此承诺跨站 Cookie 请求可用 |

资源没有声明 `client_id` 查询参数来建立上述客户端上下文；仅给 URL 加 `client_id` 不是源码保证的 CORS 设置方式。跨来源集成需核对实际客户端上下文、Origin 配置及浏览器凭据策略。

三种资源均支持如下预检，将路径分别替换为 `/geetest/code`、`/tencent/code`、`/recaptcha/code`：

```http
OPTIONS <base-url>/realms/<realm>/geetest/code
Origin: <前端来源>
Access-Control-Request-Method: GET
Access-Control-Request-Headers: authorization
```

OPTIONS 正常响应为 `200`、无业务响应体；只有上述匹配条件成立才附加 CORS 头。配置 GET/POST 捕获异常时返回 `500 INTERNAL_ERROR`，消息分别为“获取极验验证码配置失败”“获取腾讯云验证码配置失败”“获取reCAPTCHA配置失败”；错误返回路径没有调用该 CORS 辅助方法。

### GET、POST /geetest/code

无需业务参数，GET 和 POST 共享配置处理逻辑：

```http
GET <base-url>/realms/<realm>/geetest/code
```

```http
POST <base-url>/realms/<realm>/geetest/code
```

#### 极验 v3（默认版本）

成功 `200`，原样保留 SDK register 结果中的四个字段，再补统一字段：

```json
{
  "success": 1,
  "gt": "<极验ID>",
  "challenge": "<初始化返回的challenge>",
  "new_captcha": true,
  "type": "geetest",
  "geetestId": "<极验ID>",
  "captchaAppId": "<极验ID>"
}
```

| 字段 | 说明 |
| --- | --- |
| `success` | 1 为 register 成功，0 为后端为该 challenge 登记了宕机模式 |
| `gt`、`challenge`、`new_captcha` | v3 SDK 初始化所需 register 字段 |
| `type` | 固定 `geetest` |
| `geetestId`、`captchaAppId` | 均来自同一个 `gt` |

前端 v3 初始化应使用完整响应，并按 `success !== 1` 设置 `offline: true`，不能丢掉 `challenge` 等字段只留下应用 ID。宕机 challenge 的有效期和消费边界见[安全行为变更](#安全行为变更)。

#### 极验 v4

配置版本为 4 时，成功 `200` 返回以下三字段，不包含 v3 register 的 `success`、`challenge`、`new_captcha`：

```json
{
  "type": "geetest",
  "geetestId": "<极验ID>",
  "captchaAppId": "<极验ID>"
}
```

发送短信时改提交 v4 的四个校验字段，不提交 v3 的三字段。缺少极验 ID 或密钥时，内部前端配置数据没有 `gt`，资源可能只返回 `{"type":"geetest"}`，不能把 HTTP 200 当成配置完整。

### GET、POST /tencent/code

注意路径是 `/tencent/code`，不存在本文旧示例中的 `/tencent-captcha/code`。

```http
GET <base-url>/realms/<realm>/tencent/code
```

```http
POST <base-url>/realms/<realm>/tencent/code
```

成功 `200`：

```json
{"type":"tencent","captchaAppId":"<腾讯云应用ID>"}
```

没有应用 ID 时，当前映射会省略 `captchaAppId`，返回 `{"type":"tencent"}`；不会转发内部的 `success=0` 和消息。前端需检查应用 ID 是否存在，不能只检查状态码。发送短信时提交 `ticket` 和 `randstr`；不使用 `captchaAppId` 充当票据。容灾票据规则见[专节](#腾讯云验证码容灾票据)。

### GET /recaptcha/code

```http
GET <base-url>/realms/<realm>/recaptcha/code
```

当前 `GoogleRecaptchaServiceImpl.getFrontendKey` 恒返回空字符串，配置资源**不提供 `siteKey`**；映射出的配置只包含类型：

```json
{"type":"recaptcha"}
```

这不是可直接初始化 reCAPTCHA 前端的完整配置。后端校验读取 `g-recaptcha-response`，前端站点密钥及组件初始化需在自己的集成中提供；不要假设该接口已完成前后端适配。配置处理抛异常时仍走上述 `500 INTERNAL_ERROR`。该资源支持 OPTIONS，**没有 POST 方法**；框架对不支持方法的响应不属于 `ErrorCode` 业务错误。

## OIDC 直授换取令牌

### POST /protocol/openid-connect/token

此接口属于 Keycloak OIDC 协议，不是 `/sms` 子资源。前置条件：客户端允许直授，realm 或客户端实际绑定的直授流程包含手机号认证执行器；普通手机号直授使用 `Provide phone number` 与 `Provide verification code`，自动开户使用 `Authentication everybody by phone`。配置步骤见 [README](README.md)。

验证码应从 **`/sms/authentication-code`** 获取，类型为 `OTP`，不能拿 `/sms/login-code` 的 `LOGIN` 验证码代替。仅接受协议所需 form 请求，不使用本文短信资源的 JSON 形式。

| 参数 | 是否必填 | 说明 |
| --- | --- | --- |
| `grant_type` | 是 | `password` |
| `client_id` | 是 | `<client-id>` |
| `client_secret` | 机密客户端需要 | `<client-secret>`；公共客户端省略此项 |
| `areaCode` | 是 | 区号；读取号码时兼容 `area_code` |
| `phoneNumber` | 是 | 手机号；读取号码时兼容 `phone_number` |
| `smsCode` | 是 | `OTP` 短信验证码，不能写 `code` |

机密客户端请求示例：

```bash
curl -X POST '<base-url>/realms/<realm>/protocol/openid-connect/token' \
  --data-urlencode 'grant_type=password' \
  --data-urlencode 'client_id=<client-id>' \
  --data-urlencode 'client_secret=<client-secret>' \
  --data-urlencode 'areaCode=86' \
  --data-urlencode 'phoneNumber=13800000000' \
  --data-urlencode 'smsCode={{SMS_CODE}}'
```

成功 `200`，由 Keycloak 返回标准令牌对象；下例仅展示集成测试明确检查的访问令牌字段，其他字段由 Keycloak、客户端与 scope 决定：

```json
{"access_token":"<Keycloak签发的访问令牌>"}
```

手机号、用户或验证码校验不通过时，当前手机号直授认证器返回 `401`：

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/json

{"error":"invalid_grant","error_description":"Invalid user credentials"}
```

**不要按 `ApiError.code` 解析此响应**。客户端认证失败、直授未开启、用户禁用、必需操作未完成等其他协议失败由 Keycloak 产生，使用 OAuth 的 `error` / `error_description`，不列入下方 `ErrorCode` 表。

Everybody 流程在验证码通过后，若找不到已验证号码用户，则以手机号部分创建并启用用户，再写入已验证号码；如该用户名已占用也会拒绝。新账户可能缺少 User Profile 必填项并触发 Keycloak 必需操作，直授无法用浏览器页面完成这些操作；**不能承诺发码成功就一定能换取 token**。验证码在成功校验阶段已经消费，后续 Keycloak 拒绝令牌也不代表旧验证码可重用。

## 错误码全表

以下逐项对应 `ErrorCode` 的 **20 项**，仅适用于插件资源中明确构建的业务错误。表内消息是枚举默认值，具体端点可覆盖消息。

| 错误码 | HTTP | 默认消息 | 当前使用位置或边界 |
| --- | --- | --- | --- |
| `PHONE_NUMBER_REQUIRED` | 400 | 手机号码不能为空 | 发送、重发时间查询、绑定；区号缺失同样触发 |
| `PHONE_NUMBER_INVALID` | 400 | 手机号码格式不正确 | 发送时区号不满足 1–4 位纯数字 |
| `VERIFICATION_CODE_REQUIRED` | 400 | 验证码不能为空 | 绑定时 `smsCode` 缺失或空白 |
| `VERIFICATION_CODE_INVALID` | 400 | 验证码不正确 | 绑定时存在有效流程但验证码不匹配 |
| `VERIFICATION_CODE_EXPIRED` | 400 | 验证码已过期 | 绑定时没有有效 `VERIFY` 流程，包括未发起、过期、消费或作废 |
| `CAPTCHA_REQUIRED` | 400 | 人机验证未完成 | 发送时人机验证不通过且不满足可信客户端检查 |
| `CAPTCHA_INVALID` | 400 | 人机验证失败 | **当前不会返回**：仅枚举声明，资源没有调用 |
| `INVALID_REQUEST` | 400 | 请求格式错误 | 发送、重发查询、绑定的 JSON 解析失败 |
| `AREA_NOT_SUPPORTED` | 403 | 该地区暂不支持 | 发送时区号不在许可列表或列表加载失败 |
| `USER_NOT_FOUND` | 404 | 用户不存在 | 仅认证、登录、重置发送接口；认证发送有 Everybody 例外 |
| `AUTHENTICATION_REQUIRED` | 401 | 需要登录 | 绑定、解绑缺少有效 Identity Cookie |
| `EMAIL_NOT_VERIFIED` | 400 | 邮箱未验证 | 解绑前邮箱未验证 |
| `PHONE_UNSET_NOT_ALLOWED` | 403 | 不允许取消绑定手机号 | 解绑开关关闭 |
| `TOKEN_NOT_FOUND` | 404 | 验证码不存在或已失效 | 重发时间查询无冷却记录或冷却已结束；不等同于验证码校验结果 |
| `RESEND_TOO_SOON` | 429 | 请求过于频繁，请稍后再试 | 发送服务判定仍在冷却；可有 `details.resendExpires` |
| `SMS_SEND_LIMIT_EXCEEDED` | 429 | 短信请求过于频繁，请稍后再试 | 最近一小时记录数触发源码阈值；详见下一节 |
| `SMS_SEND_FAILED` | 500 | 短信发送失败 | 服务商不可用或发送失败，消息可由发送结果覆盖 |
| `LOCATION_CHECK_FAILED` | 500 | 归属地检测失败 | **当前不会返回**：查询异常在归属地实现中记日志并按未命中黑名单处理 |
| `ILLEGAL_PHONE_NUMBER` | 403 | 该手机号归属地不允许使用 | 发送时命中归属地黑名单，状态码是 403，不是 500 |
| `INTERNAL_ERROR` | 500 | 服务器内部错误 | 短信配置读取或人机配置处理异常 |

## 频控与验证码生命周期

### 冷却和小时阈值是两个维度

| 机制 | 范围 | 当前行为 |
| --- | --- | --- |
| 重发冷却 | 当前 realm、同一完整号码、同一验证码类型 | 基于最近发送记录，不只看仍有效的验证码；阿里云与 dummy 当前均设为发送后 60 秒 |
| 小时记录阈值 | 当前 realm、同一完整号码、同一验证码类型 | 查询最近一小时的已持久化发送记录；名义阈值是 3，但当前比较边界存在下述差异 |
| 校验失败作废 | 当前选中的验证码记录 | 累计不匹配达 5 次，记录置为过期；不删除该号码全部发送记录 |
| 成功消费 | 当前 realm、该号码、该类型 | 成功校验路径删除该号码该类型的全部记录，不能继续重放该验证码 |

**不能将现有实现当作“每号码每类型每小时最多 3 次”的硬保证。** `TokenCodeServiceImpl.isAbusing` 在发送前判断的是 `entities.size() > 3`，所以在串行发送、每次冷却已过且期间没有成功消费记录的前提下，已有 3 条记录时第 4 次仍可发送，已有 4 条时第 5 次才被拒绝。成功校验还会清空该号码该类型记录，继而影响冷却与小时计数。并发请求下同样不保证严格配额。

冷却未结束时可返回：

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json

{
  "code": "RESEND_TOO_SOON",
  "message": "请求过于频繁，请稍后再试",
  "details": { "resendExpires": 1793097060000 },
  "timestamp": "2026-10-27T10:30:00Z"
}
```

如果冷却在查询间隙结束、未取得时间，`details` 可以省略。`SMS_SEND_LIMIT_EXCEEDED` 不携带这个可重发时间，不能把它当成“再等 60 秒就必然成功”；小时阈值判断先于冷却判断。

### 验证码选择与失败计数

验证码查询按创建时间降序，从**尚未过期**记录中选一条校验；只对该条记录计失败次数。通常当前只有一条可用验证码，第 5 次错误将它作废后必须重新发送。但源码没有在新发码时立即作废所有旧的未过期记录，若多次发送产生多条仍有效记录，最新一条失效后查询可能选中较早记录；不能据此文档承诺“任何时候只接受最后收到的验证码”。

正常校验通过即消费；注册动作例外是分两阶段：`validate` 只检查和记录失败次数，`success` 再消费，所以注册表单其他字段校验失败时，不要求仅因此重新发短信。验证码类型之间互不替代。

## 安全行为变更

下列是客户端需要配合的行为；历史版本归档见 [CHANGELOG](CHANGELOG.md)。

| 行为 | 当前实现及升级影响 |
| --- | --- |
| 失败计数与防重放 | 验证码使用 `MessageDigest.isEqual` 比对；累计不匹配达 5 次只使当前记录过期，不通过清空发送记录恢复频控额度。普通直授、Everybody 与注册统一使用 `TokenCodeService`；通过后消费，注册按前述两阶段处理 |
| 失败次数持久化 | `PHONE_MESSAGE_TOKEN_CODE.ATTEMPTS` 由 `token-code-attempts` Liquibase changeSet 添加，带列是否存在的前置条件，由主 changelog include；升级应按正常数据库迁移流程执行，不要求手工添加列 |
| 短信 MFA 免验证 Cookie | `SMS_OTP_ANSWERED` 不再接受固定 `true` 或任意非空值，而使用 Keycloak 签名令牌，绑定 realm、用户、凭据 ID 并检查有效期及凭据类型；旧 Cookie 需要重新完成一次短信验证 |
| Cookie 属性与时效 | `HttpOnly; SameSite=Lax`，HTTPS 时附 `Secure`；有效期由认证执行器 `cookie.max.age` 控制，缺失、非法或非正数取 3600 秒。伪造、他人 Cookie 或绑定凭据已删除时不能豁免短信验证；签名是否仍可验证以当前 Keycloak 密钥状态为准，不承诺任意密钥轮换都会立即使全部旧令牌失效 |
| 发送商不再回退 | 配置的发送商未部署时拒绝，发送资源返回 `SMS_SEND_FAILED`；匹配 ID 不区分大小写。默认发送商仍是 dummy，生产环境需显式配置并部署真实实现，避免验证码写入 dummy 日志 |
| 号码验证状态 | `VERIFY_PHONE_OR_EMAIL` 要求号码非空且 `phoneNumberVerified=true` 才认定已验证；绑定号码会取消同号码其他账户的验证状态，不是删除其他账户的号码属性 |
| 极验 v3 宕机隔离 | 只有本服务初始化失败后登记的 challenge 才走本地宕机校验，登记存在 Keycloak 一次性对象存储中，有效期 10 分钟，校验时消费；宕机验证本身只检查三字段非空，不是第三方真实校验，不再使用进程共享的宕机状态 |
| 腾讯验证协议 | 后端 `DescribeCaptchaResult` 固定使用协议值 `CaptchaType=9`；旧配置不再改变该参数，非 9 仅告警；不能用它来选择滑块、点选等前端样式 |

### 2.2.1 首次配置短信 MFA

`CONFIGURE_SMS_OTP` 必需操作使用 `/sms/verification-code` 获取的 `VERIFY` 验证码，表单验证码字段同样是 `smsCode`：

| 当前用户状态 | 处理 |
| --- | --- |
| 没有号码，或号码未验证 | 验证通过后绑定提交的号码、标记已验证、取消同号码其他账户验证，再创建短信 OTP 凭据 |
| 已有已验证号码，提交原号码 | 校验该号码后创建短信 OTP 凭据 |
| 已有已验证号码，提交其他号码 | 显示 `smsOtpPhoneNumberMismatch`，不改号码、不创建凭据；换号须走更新手机号流程 |
| 号码或验证码缺失、校验失败 | 留在 `login-sms-otp-config.ftl`，显示 `verificationCodeDoesNotMatch`，回填 `areaCode`、`phoneNumber` |

主题应补充 `smsOtpPhoneNumberMismatch` 文案，不能继续假设失败时会转到改号页。这是 Keycloak 必需操作表单行为，不是一个新增的 `ApiError` REST 端点。

`SecurityFixesIT` 源码覆盖伪造及他人 Cookie、本人合法 Cookie、Everybody 验证码重放、连续 5 次错误后保留冷却、注册其他字段错误不消费验证码，以及上述首次配置绑定/拒绝换号；这些场景不涵盖所有运行环境，也没有证明“每小时最多 3 次”的硬上限。

## 腾讯云验证码容灾票据

当配套前端 SDK 加载失败时，可能提交 `trerror_` 或 `terror_` 开头的本地占位票据，例如：

```text
trerror_<错误码>_<应用ID>_<时间戳>
```

后端在配置齐全且 `ticket`、`randstr` 均存在后检查前缀，不验证这类字符串是否真由 SDK 生成，也不会把命中前缀的票据提交腾讯云接口。

| `keycloak.conf` 配置 | 处理与风险 |
| --- | --- |
| `spi-captcha-service-tencent-allow-disaster-ticket=true`（默认） | 直接放行人机验证；攻击者也能自行构造对应前缀，因此不等同于真实校验成功 |
| `spi-captcha-service-tencent-allow-disaster-ticket=false` | 拒绝容灾票据；未通过可信客户端检查时发送接口返回 `CAPTCHA_REQUIRED` |

安全敏感部署应根据可用性与防刷要求显式决定该开关，而不是依赖默认放行。此开关不同于腾讯 API 调用异常的 `spi-captcha-service-tencent-fallback-on-error`：后者默认 `false`，只处理捕获到的腾讯 SDK 异常。更早的配置不完整分支会直接放行，设置容灾开关为 `false` 也不能弥补缺少必填配置的问题。配齐必填键、可信后端保管客户端密钥及短信侧频控都应独立检查，完整配置表见 [README](README.md)。

## 前端迁移步骤

### v1.x → v2 请求与响应对照

以下按仓库 `v1.2.1` 历史资源与当前资源对照，不将旧版本“业务失败常返回 200”扩写成所有框架异常也永远返回 200。

| 内容 | v1.x 旧格式 | v2 当前格式 |
| --- | --- | --- |
| 业务成功/失败 | 多数资源用 `200` 加 `status=1/0/-1/-2` | 按 HTTP 状态处理，不依赖 `status` |
| 发码成功时间 | `expires_in`、`resend_expires` | `expiresIn`、`resendExpires`，仍是 epoch 毫秒 |
| 重发查询时间 | `resend_expire` | `resendExpire`，注意与发送响应不同 |
| 业务错误字段 | `error`、`errormsg` | `code`、`message`、可选 `details`、`timestamp` |
| 绑定、解绑成功 | 检查旧业务状态 | `204`，没有 JSON，不执行 `response.json()` |
| 人机配置结果 | 旧调用链可能把 JSON 当字符串处理 | 配置对象；v3 必须保留完整 register 四字段，v4、腾讯按各自结构处理 |
| 腾讯配置路径 | 错误文档曾写 `/tencent-captcha/code` | `/tencent/code` |
| 手机号字段 | 有些接口兼容下划线字段 | 全部新代码统一 `areaCode`、`phoneNumber` |
| 短信验证码字段 | 错误文档或示例曾写 `code` | 所有新代码统一 `smsCode`，包含绑定、直授及主题表单 |
| 更新手机号认证 | 错误文档曾示例 Bearer | 使用 Keycloak Identity Cookie，Bearer 不能代替 |
| OIDC 错误处理 | 不能混同短信业务状态 | 继续按 OAuth `error`、`error_description` 处理 |

### 更新请求封装

将旧的 `data.status === 1` 分支替换为 HTTP 状态判断，并显式处理 `204`；短信资源与 OIDC 的错误格式分开。以下是插件 JSON 资源的最小封装，不改变错误码语义：

```javascript
async function requestPluginJson(url, options = {}) {
  const response = await fetch(url, options);
  if (response.status === 204) return undefined;
  const data = await response.json();
  if (!response.ok) {
    const error = new Error(data.message || '请求失败');
    error.code = data.code;
    error.httpStatus = response.status;
    error.details = data.details;
    throw error;
  }
  return data;
}

await requestPluginJson('<base-url>/realms/<realm>/sms/update-profile', {
  method: 'POST',
  credentials: 'include',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    areaCode: '86',
    phoneNumber: '13800000000',
    smsCode: '{{SMS_CODE}}'
  })
});
```

### 错误处理迁移清单

| 旧提示或场景 | 当前判断 |
| --- | --- |
| `phoneNumberCannotBeEmpty` | `PHONE_NUMBER_REQUIRED` |
| `smsCodeCannotBeEmpty` | `VERIFICATION_CODE_REQUIRED` |
| `captchaNotCompleted` | `CAPTCHA_REQUIRED`；不等待从未启用的 `CAPTCHA_INVALID` |
| `areaNotSupported` | `AREA_NOT_SUPPORTED` |
| `userNotExists` | `USER_NOT_FOUND`，核对是否发送了正确类型 |
| `needAuth` | `AUTHENTICATION_REQUIRED`，恢复 Identity Cookie 会话，不能只补 Bearer |
| `needVerifiedEmail` | `EMAIL_NOT_VERIFIED` |
| `unsetPhoneNumberNotAllowed` | `PHONE_UNSET_NOT_ALLOWED` |
| `illegalPhoneNumber` | `ILLEGAL_PHONE_NUMBER`，HTTP 403 |
| `rateTime` | `RESEND_TOO_SOON`，读取可选 `details.resendExpires` |
| 校验不匹配 | `VERIFICATION_CODE_INVALID`，与过期分开提示 |
| 无有效验证码 | 绑定为 `VERIFICATION_CODE_EXPIRED`；重发时间查询为 `TOKEN_NOT_FOUND`；OIDC 为 OAuth 错误 |
| 每小时记录超阈值 | `SMS_SEND_LIMIT_EXCEEDED`，不能当作单次冷却到期处理 |

### 联调检查清单

| 检查范围 | 需要覆盖的分支 |
| --- | --- |
| 五类发码 | 正确类型、缺号码/区号、区号格式、许可地区、三类需已注册用户的端点、Everybody 新号码 |
| 人机验证 | 实际选中实现的字段、缺参数、校验失败、配置缺失放行风险、可信后端密钥路径；登录会话本身不豁免 |
| 绑定与解绑 | Identity Cookie 与仅 Bearer 的差异、`smsCode`、204 空体、绑定错误/过期、禁止解绑、邮箱未验证、凭据删除 |
| 生命周期 | 五次不匹配后作废、成功后不可重放、注册其他字段失败后仍可提交 |
| 冷却 | 429 的可选详情、GET/POST JSON/form 三种查询、冷却结束后 404 |
| 人机配置与跨域 | v3 完整响应、v4 响应、腾讯路径、reCAPTCHA 不提供 siteKey、实际 CORS 头而非仅检查 OPTIONS 200 |
| OIDC | 客户端是否允许直授、绑定的执行器、`OTP` 类型、机密客户端认证、OAuth 错误格式、自动开户后的必需操作 |

v1.x → v2 是响应格式的破坏性变更，前后端应配套迁移。本文不提供未经验证的跨版本回滚建议，尤其不保证旧版本能读取升级后的数据库或接受新安全令牌；版本信息以 [CHANGELOG](CHANGELOG.md) 为准，配套主题来源见 [README](README.md)。

## 实现边界

以下是集成时必须知道的实现边界，不应靠前端兼容逻辑掩盖：

| 项目 | 当前实现 | 集成建议 |
| --- | --- | --- |
| 小时配额 | 发送前记录数 `> 3` 才拒绝；成功消费清空记录 | 不要把它当作严格的“每小时最多 3 次”，需要硬限制时在网关层另做 |
| 登录会话与 captcha | Cookie 只提供身份，三种实现仍校验人机验证 | 已登录用户发码同样要提交人机验证结果 |
| reCAPTCHA 方法 | 仅 GET/OPTIONS，无 POST；不返回 siteKey | 前端站点密钥和初始化由部署方提供；不将未实现 POST 列作可用 |
| CORS | 依赖当前客户端上下文和 `webOrigins`；资源不设置允许携带凭据头 | 在真实代理、来源与客户端配置下确认浏览器请求，不承诺跨站 Cookie 必然成功 |
| 多条验证码 | 校验时只取最新的未过期记录，旧短信里的验证码不再被接受；输错 5 次会作废该号码该用途下所有未过期验证码 | 前端提示用户以最新收到的短信为准 |

可定位的契约依据：核心资源 `providers/rest/SmsResource.java`、`TokenCodeResource.java`、`VerificationCodeResource.java`，响应对象 `providers/rest/dto/` 与 `constants/ErrorCode.java`；号码解析 `utils/PhoneNumber.java`；生命周期 `providers/spi/impl/TokenCodeServiceImpl.java`、`PhoneMessageServiceImpl.java`；三个人机验证模块的资源与 `*CaptchaServiceImpl`；直授认证器与 `SecurityFixesIT`。本文所有路径均以当前 realm 为界，部署与管理员步骤回到 [README](README.md)，历史回到 [CHANGELOG](CHANGELOG.md)。
