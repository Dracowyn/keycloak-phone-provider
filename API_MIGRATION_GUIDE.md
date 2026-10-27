# API接口格式迁移指南

## 版本说明

从 v1.2.x 升级到 v2.0.0，本次更新为**破坏性更新**，统一了所有API接口格式。

## 核心变更

### 1. HTTP状态码使用

**旧版本 (v1.2.x)**:
- 所有请求都返回 `200 OK`
- 使用 `status` 字段标识成功/失败 (1/0/-1/-2)

**新版本 (v2.0.0)**:
- 使用标准HTTP状态码
  - `200 OK` - 成功并返回数据
  - `204 No Content` - 成功但无返回内容
  - `400 Bad Request` - 客户端请求错误
  - `401 Unauthorized` - 未认证
  - `403 Forbidden` - 权限不足
  - `404 Not Found` - 资源不存在
  - `500 Internal Server Error` - 服务器错误

### 2. 成功响应格式

#### 发送短信验证码

**旧格式**:
```json
HTTP/1.1 200 OK

{
  "status": 1,
  "expires_in": 1737025200000,
  "resend_expires": 1737024360000
}
```

**新格式**:
```json
HTTP/1.1 200 OK

{
  "expiresIn": 1737025200000,
  "resendExpires": 1737024360000
}
```

**变更点**:
- ❌ 移除 `status` 字段
- ✅ 字段名改为驼峰命名 (`expires_in` → `expiresIn`)

#### 获取短信配置

**旧格式**:
```json
{
  "tokenExpires": 300,
  "defaultAreaCode": "86",
  "areaLocked": false,
  "allowUnset": true,
  "areaCodeList": [...]
}
```

**新格式**:
```json
{
  "tokenExpires": 300,
  "defaultAreaCode": "86",
  "areaLocked": false,
  "allowUnset": true,
  "areaCodeList": [...]
}
```

**变更点**:
- ✅ 格式保持不变（已是标准格式）

#### 验证码验证

**旧格式**:
```json
HTTP/1.1 200 OK

{
  "status": 1
}
```

**新格式**:
```http
HTTP/1.1 204 No Content
```

**变更点**:
- ❌ 不再返回JSON内容
- ✅ 使用 `204 No Content` 状态码

### 3. 错误响应格式

#### 旧格式错误响应

```json
HTTP/1.1 200 OK

{
  "status": 0,
  "error": "Must inform a cellphone number.",
  "errormsg": "phoneNumberCannotBeEmpty"
}
```

**问题**:
- HTTP状态码总是200，无法区分成功/失败
- `error` 和 `errormsg` 字段冗余
- 数值状态码含义不清晰

#### 新格式错误响应

```json
HTTP/1.1 400 Bad Request

{
  "code": "PHONE_NUMBER_REQUIRED",
  "message": "手机号码不能为空",
  "timestamp": "2026-01-15T10:30:00Z"
}
```

**改进**:
- ✅ 使用标准HTTP状态码
- ✅ 统一的错误码枚举（便于国际化）
- ✅ 清晰的错误消息
- ✅ 包含时间戳

### 4. 验证码配置响应

#### 极验配置

**旧格式** (字符串):
```json
"{\"success\":1,\"gt\":\"xxx\"}"
```

**新格式** (对象):
```json
{
  "type": "geetest",
  "geetestId": "xxx",
  "captchaAppId": "xxx"
}
```

**说明**: `CaptchaConfigResponse` 不再保留 `success` 兼容字段（无论极验还是腾讯云配置响应均没有该字段）；`geetestId` 与 `captchaAppId` 当前均取自极验后端返回的同一个 `gt` 值。上例为极验 4.0（`version=4`）的响应。

**极验 3.0**（默认 `version=3`）：前端 `initGeetest` 需要初始化数据，因此在上述三个字段之外，额外原样返回极验 register 结果中的 `success`、`gt`、`challenge`、`new_captcha`：
```json
{
  "success": 1,
  "gt": "xxx",
  "challenge": "0123456789abcdef0123456789abcdef",
  "new_captcha": true,
  "type": "geetest",
  "geetestId": "xxx",
  "captchaAppId": "xxx"
}
```
`success=0` 表示极验服务不可用、后端已进入宕机模式，前端应以 `offline: true` 初始化。

#### 腾讯云配置

**旧格式** (字符串):
```json
"{\"success\":1,\"captchaAppId\":\"xxx\"}"
```

**新格式** (对象):
```json
{
  "type": "tencent",
  "captchaAppId": "xxx"
}
```

## 安全行为变更

### 验证码防爆破（累计失败作废）

同一手机号、同一验证码类型下，当前有效的验证码一旦累计校验失败达到 **5 次**（`TokenCodeServiceImpl.MAX_VERIFICATION_ATTEMPTS`），该验证码会被立即置为过期（作废），用户必须重新发送验证码才能继续校验，不能再对旧验证码继续尝试。

- 失败次数保存在数据库新增的 `ATTEMPTS` 列（表 `PHONE_MESSAGE_TOKEN_CODE`）。
- 该列由 Liquibase changeSet `token-code-attempts`（`token-code-changelog-attempts.xml`，已被主 changelog `token-code-changelog.xml` include）自动迁移新增，changeSet 带 `columnExists` 前置条件，随插件启动自动执行，**无需人工手动改库**。
- 对最终用户可见的表现：连续输错验证码 5 次后，即使第 6 次输入的是正确的验证码也会失败，接口会返回 `VERIFICATION_CODE_EXPIRED`（`update-profile` 场景）或校验直接返回不通过（其他 `validateCode` 调用方场景），用户需要重新获取验证码。
- 作废只针对当前这条验证码，**不会清空发送记录**：重发冷却（`RESEND_TOO_SOON`）与每小时发送上限（`SMS_SEND_LIMIT_EXCEEDED`）照常生效。此前作废时会删除该号码的全部记录，攻击者每输错 5 次就能换来一轮新的发送额度。

### 常量时间比较与验证码一次性消费

验证码比对由 `TokenCodeServiceImpl#codesMatch` 使用 `MessageDigest.isEqual` 进行常量时间比较，避免 `String#equals` 因逐字符提前返回而产生的时序侧信道，防止攻击者通过响应时间差异逐位猜解验证码。

所有校验入口（含 `everybody-phone-authenticator` 直授流程与 `registration-phone` / `registration-phone-or-email` 注册表单）统一走 `TokenCodeService`：失败计入错误次数，通过后验证码即被消费，同一验证码不能重复使用。注册表单在 `validate` 阶段只校验不消费，`success` 阶段才消费，因此表单其他字段校验失败时用户无需重新获取验证码。

### 短信 OTP 免验证 Cookie（`SMS_OTP_ANSWERED`）

`sms-otp-authenticator` 校验通过后写入的 `SMS_OTP_ANSWERED` Cookie 由固定值 `true` 改为 Keycloak 签名令牌，绑定 realm、用户与短信 OTP 凭据，有效期取认证器配置 `cookie.max.age`（缺省或非法时为 3600 秒）。Cookie 带 `HttpOnly; SameSite=Lax`，HTTPS 下另带 `Secure`。

- 旧版本写入的 `SMS_OTP_ANSWERED=true` Cookie 升级后不再被接受，用户下次登录需重新完成一次短信验证。
- 伪造的 Cookie、他人的 Cookie、凭据被删除后的 Cookie、realm 密钥轮换前签发的 Cookie 均会被忽略。

### 短信服务商不再静默回退

`spi-phone-provider-config-sender-service` 指定的服务商未部署时，此前会静默改用任意一个已部署的实现（可能落到只打日志的 `dummy`）。现在会拒绝发送并记录错误日志，发送接口返回失败。配置值匹配不区分大小写（`Aliyun` 与 `aliyun` 等价）。未配置时默认值仍为 `dummy`，生产环境务必显式配置。

### 号码验证状态判定

- 必需操作 `VERIFY_PHONE_OR_EMAIL` 改为以 `phoneNumberVerified=true` 判断手机号已验证，不再只看 `phoneNumber` 属性是否存在。号码被其他账户重新验证后，原账户会被置为未验证并重新要求验证。
- 删除了恒为 `true` 的 `UserUtils.isDuplicatePhoneAllowed()` 占位开关，行为不变：注册时拒绝已被占用的号码，验证号码时取消同号码其他账户的验证状态，保证同一号码至多一个已验证账户。

### 极验 3.0 宕机模式

极验 3.0 的宕机（failback）状态改为按 challenge 登记在 Keycloak 一次性对象存储中（10 分钟有效、校验即消费），只有本服务在初始化失败时本地生成的 challenge 才走宕机校验。此前该状态是进程级共享变量，一个用户初始化失败会让其他用户的极验校验被放行。

## 腾讯云验证码容灾票据

配套前端在腾讯云验证码 JS SDK 加载失败时，会在本地生成一个无法通过腾讯云校验的"容灾票据"占位 `ticket`，形如：

```
trerror_{errorCode}_{appId}_{timestamp}
```

腾讯云官方示例中还存在 `terror_` 前缀的等价形式。这类票据并非腾讯云签发，天然无法通过 `DescribeCaptchaResult` 接口的真实校验。

`TencentCaptchaServiceImpl#verify` 会在调用腾讯云接口前先识别 `ticket` 是否以 `trerror_` 或 `terror_` 开头；识别到后**不会**再向腾讯云发起请求（否则必然验证失败，容灾机制形同虚设），而是按 SPI 配置项 `allowDisasterTicket` 决定处理策略：

- **`true`（默认值）**: 直接放行人机验证，与腾讯云官方的前端容灾建议一致，避免因验证码前端资源加载失败导致用户完全无法登录/注册/找回密码。
- **`false`**: 严格模式，一律拒绝，视为人机验证未通过。

**安全权衡**: `allowDisasterTicket=true` 时，攻击者可以自行构造符合 `trerror_`/`terror_` 前缀规则的字符串直接绕过人机验证，因此该配置本质上是"可用性 vs 安全性"的取舍。安全敏感的部署（例如已确认前端 SDK 资源稳定可用，或需要严格防刷）建议显式配置为 `false`；同时无论该配置取何值，都应结合短信侧的频控（`SMS_SEND_LIMIT_EXCEEDED`、验证码 5 次失败作废等）作为纵深防御，不要仅依赖人机验证这一层。

**配置方法**: `CaptchaService` 对应的 SPI 名称为 `captchaService`（`CaptchaServiceSpi#getName()`），腾讯云实现的 provider id 为 `tencent`（`TencentCaptchaServiceProviderFactory#getId()`）。按 Keycloak SPI 配置命名规则（`spi-<spi名称的kebab-case>-<provider id>-<配置项的kebab-case>`），完整键名为：

- `keycloak.conf` 中：
  ```properties
  spi-captcha-service-tencent-allow-disaster-ticket=false
  ```
- 命令行参数形式：
  ```
  --spi-captcha-service-tencent-allow-disaster-ticket=false
  ```

不配置时默认为 `true`。

## 前端迁移步骤

### 步骤1: 更新请求拦截器

```javascript
// 旧代码
axios.interceptors.response.use(response => {
  const data = response.data;
  if (data.status === 1) {
    return data; // 成功
  } else {
    throw new Error(data.error || data.errormsg);
  }
});

// 新代码
axios.interceptors.response.use(
  response => {
    // 2xx 状态码直接返回数据
    return response.data;
  },
  error => {
    // 非2xx状态码处理错误
    const errorData = error.response?.data;
    const message = errorData?.message || '请求失败';
    throw new Error(message);
  }
);
```

### 步骤2: 更新API调用

#### 发送验证码

```javascript
// 旧代码
async function sendSmsCode(phoneNumber) {
  const response = await axios.post('/sms/verification-code', { phoneNumber });
  if (response.status === 1) {
    return {
      expiresIn: response.expires_in,
      resendExpires: response.resend_expires
    };
  }
  throw new Error(response.error);
}

// 新代码
async function sendSmsCode(phoneNumber) {
  try {
    const response = await axios.post('/sms/verification-code', { phoneNumber });
    // 直接返回，字段名已是驼峰
    return response;
  } catch (error) {
    // 统一错误处理
    throw error;
  }
}
```

#### 验证验证码

```javascript
// 旧代码
async function verifyCode(phoneNumber, code) {
  const response = await axios.post('/sms/update-profile', { phoneNumber, code });
  return response.status === 1;
}

// 新代码
async function verifyCode(phoneNumber, code) {
  try {
    await axios.post('/sms/update-profile', { phoneNumber, code });
    // 204响应表示成功
    return true;
  } catch (error) {
    return false;
  }
}
```

### 步骤3: 更新错误处理

```javascript
// 旧代码
function getErrorMessage(response) {
  const errorMap = {
    'phoneNumberCannotBeEmpty': '手机号码不能为空',
    'captchaNotCompleted': '请完成人机验证',
    'areaNotSupported': '该地区暂不支持',
    'userNotExists': '用户不存在'
  };
  return errorMap[response.errormsg] || response.error;
}

// 新代码
function getErrorMessage(error) {
  const errorData = error.response?.data;
  // 直接使用服务器返回的message
  return errorData?.message || '请求失败';
  
  // 如果需要自定义消息，可以根据code映射
  const errorMap = {
    'PHONE_NUMBER_REQUIRED': '手机号码不能为空',
    'CAPTCHA_REQUIRED': '请完成人机验证',
    'AREA_NOT_SUPPORTED': '该地区暂不支持',
    'USER_NOT_FOUND': '用户不存在'
  };
  return errorMap[errorData?.code] || errorData?.message;
}
```

### 步骤4: 更新状态码判断

```javascript
// 旧代码
if (response.status === 1) {
  // 成功
} else if (response.status === 0) {
  // 一般错误
} else if (response.status === -1) {
  // 验证失败
} else if (response.status === -2) {
  // 区域限制
}

// 新代码
try {
  const response = await apiCall();
  // 2xx 状态码表示成功
  handleSuccess(response);
} catch (error) {
  const status = error.response?.status;
  const code = error.response?.data?.code;
  
  if (status === 400) {
    // 客户端错误
    handleBadRequest(code);
  } else if (status === 401) {
    // 未认证
    handleUnauthorized();
  } else if (status === 403) {
    // 权限不足
    handleForbidden(code);
  } else if (status === 404) {
    // 资源不存在
    handleNotFound();
  } else {
    // 服务器错误
    handleServerError();
  }
}
```

## 错误码映射表

| 旧errormsg | 新code | HTTP状态码 |
|-----------|--------|-----------|
| phoneNumberCannotBeEmpty | PHONE_NUMBER_REQUIRED | 400 |
| （无，新增的区号格式校验） | PHONE_NUMBER_INVALID | 400 |
| smsCodeCannotBeEmpty | VERIFICATION_CODE_REQUIRED | 400 |
| （无，原验证码错误未细分） | VERIFICATION_CODE_INVALID | 400 |
| （无，原验证码错误未细分） | VERIFICATION_CODE_EXPIRED | 400 |
| captchaNotCompleted | CAPTCHA_REQUIRED | 400 |
| areaNotSupported | AREA_NOT_SUPPORTED | 403 |
| userNotExists | USER_NOT_FOUND | 404 |
| needAuth | AUTHENTICATION_REQUIRED | 401 |
| needVerifiedEmail | EMAIL_NOT_VERIFIED | 400 |
| unsetPhoneNumberNotAllowed | PHONE_UNSET_NOT_ALLOWED | 403 |
| illegalPhoneNumber | ILLEGAL_PHONE_NUMBER | 403 |
| rateTime | RESEND_TOO_SOON | 429 |
| （无，新增的一小时发送频控） | SMS_SEND_LIMIT_EXCEEDED | 429 |
| （无，新增的JSON请求体解析校验） | INVALID_REQUEST | 400 |
| serverError | INTERNAL_ERROR | 500 |

> `ILLEGAL_PHONE_NUMBER` 状态码维持 403 不变（对应归属地黑名单场景，参见 `TokenCodeResource#sendTokenCode` 对 `illegalPhoneNumber` 错误码的处理）；`PHONE_NUMBER_INVALID` 为新增校验，针对区号非 1-4 位纯数字等格式错误（`TokenCodeResource` 会在解析区号后立即校验）。

### RESEND_TOO_SOON 响应示例（含 resendExpires）

重发冷却期内再次请求发送验证码时，响应会在 `details.resendExpires` 中返回可重新发送的时间（epoch 毫秒）：

```json
HTTP/1.1 429 Too Many Requests

{
  "code": "RESEND_TOO_SOON",
  "message": "请求过于频繁，请稍后再试",
  "details": {
    "resendExpires": 1737024360000
  },
  "timestamp": "2026-01-15T10:30:00Z"
}
```

若当前没有可用的重发截止时间（理论上不应发生），则不带 `details` 字段，仅返回 `code`/`message`/`timestamp`。

### SMS_SEND_LIMIT_EXCEEDED 与 RESEND_TOO_SOON 的区别

两者都是 429，但触发条件不同，前端应分别处理：

- **RESEND_TOO_SOON**: 同一手机号、同一验证码类型存在未过期的验证码时，其重发冷却时间未到（例如发送后 60 秒内不能重复发送同一验证码）。
- **SMS_SEND_LIMIT_EXCEEDED**: 同一手机号、同一验证码类型在最近 1 小时内的发送次数超过系统限制（用于防止短信轰炸/滥用），与单条验证码的重发冷却是两个独立的限流维度。该场景响应体不带 `details.resendExpires`。

### update-profile 验证码错误语义拆分

`POST /realms/{realmName}/sms/update-profile`（对应 `VerificationCodeResource#setUserPhoneNumber`）将验证码校验失败拆分为两类语义更明确的错误：

- **验证码不匹配**：存在有效的验证码流程，但提交的验证码与服务端保存的不一致 → `400 VERIFICATION_CODE_INVALID`。
- **无有效验证码流程**：验证码已过期、从未发起过，或因累计校验失败次数达到上限已被作废 → `400 VERIFICATION_CODE_EXPIRED`。

两者虽然都是 400，但前端应根据 `code` 区分提示文案（例如前者提示“验证码不正确”，后者提示“验证码已失效，请重新获取”）。

## 测试检查清单

升级后请测试以下场景：

- [ ] 发送验证码成功
- [ ] 发送验证码失败（手机号为空）
- [ ] 发送验证码失败（人机验证未完成）
- [ ] 发送验证码失败（区域不支持）
- [ ] 验证验证码成功
- [ ] 验证验证码失败（验证码错误）
- [ ] 验证验证码失败（未登录）
- [ ] 获取短信配置
- [ ] 获取验证码配置（极验/腾讯云/reCAPTCHA）
- [ ] 查询重发限制时间
- [ ] 取消绑定手机号
- [ ] 验证码连续输错5次后作废，须重新发送（第6次即便输入正确验证码也应失败）
- [ ] 重发冷却期内再次发送返回429（RESEND_TOO_SOON），且响应体含details.resendExpires
- [ ] 请求体不是合法JSON时返回400（INVALID_REQUEST）
- [ ] 所有错误场景的UI展示

## 兼容性说明

- **不兼容**: 此版本与v1.2.x完全不兼容，必须同时升级前后端
- **建议**: 使用灰度发布，逐步切换用户到新版本
- **回滚**: 如遇问题，可回滚到v1.2.x版本

## 技术支持

如有问题，请参考：
- 项目仓库: https://github.com/cooperlyt/keycloak-phone-provider
- Issue追踪: 在GitHub上提交Issue
- 前端示例: https://gitee.com/Dracowyn/keycloak-phone-provider-frontend
