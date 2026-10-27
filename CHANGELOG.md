# 变更记录

部署与认证流程配置见 [README.md](./README.md)，接口契约、安全行为与前端迁移步骤见 [API.md](./API.md)。本文只记录版本变化，不替代部署或接口参考。

当前版本为 **2.2.1**，Keycloak 基线为 **26.8.1**。早期版本不补写未经确认的发布日期。

## 2.2.1 — 2026-10-27

| 类别 | 变更 |
|---|---|
| 首次配置短信 MFA | 必需操作 `CONFIGURE_SMS_OTP` 支持没有号码或号码尚未验证的用户绑定新号码：验证码通过后写入号码、标记已验证，再创建短信 OTP 凭据；同号码其他账户的验证状态会被取消。验证码通过 `/sms/verification-code` 获取。 |
| 已验证号码保护 | 已有已验证号码的用户只能使用原号码开启短信 MFA；提交其他号码返回 `smsOtpPhoneNumberMismatch`，不会修改号码或创建凭据。更换号码应走更新手机号流程，不能借首次配置 MFA 完成。 |
| 失败页面 | 缺少号码或验证码、验证码校验失败、号码不匹配时，仍渲染配置 MFA 页 `login-sms-otp-config.ftl`，不再错误渲染改号页 `login-update-phone-number.ftl`；回填 `areaCode`、`phoneNumber`。前端主题需提供 `smsOtpPhoneNumberMismatch` 文案。 |
| 验证码作废 | 输错 5 次时作废该号码该用途下所有未过期验证码，避免更早发出的验证码在最新一条作废后重新生效（2.2.0 引入的问题）；发送记录仍保留用于频控。 |
| 人机验证豁免 | 凭 `client_id` + `client_secret` 跳过人机验证只认已启用的机密客户端，禁用客户端与公共客户端不再适用。 |
| 回归测试 | `SecurityFixesIT` 新增首次绑定新号码与已有已验证号码不可更换的集成测试用例。 |
| 文档 | 重写 README 部署、配置与认证流程说明；将原迁移指南改名并扩充为 API.md 完整接口参考；新增本 CHANGELOG，集中维护历史与升级注意事项。 |

## 2.2.0

| 类别 | 变更 |
|---|---|
| 短信 MFA Cookie | 修复伪造 `SMS_OTP_ANSWERED` Cookie 可绕过短信第二因子的问题。Cookie 改为 Keycloak 签名令牌，绑定 realm、用户和短信 OTP 凭据。旧 Cookie 升级后失效，用户需重新完成一次短信验证。 |
| 验证码一次性消费 | 修复 Everybody 直授流程和手机号注册表单的验证码可重放、不累计失败次数的问题，各验证入口统一使用验证码服务。注册表单在校验阶段只检查，在成功阶段才消费，避免其他字段错误导致验证码提前失效。 |
| 频控 | 修复连续输错 5 次后删除发送记录、从而绕过重发冷却与小时配额的问题；现在只将当前验证码置为过期，保留用于频控的发送记录。 |
| 极验 v3 宕机状态 | 将进程级共享状态改为按 challenge 登记在 Keycloak 一次性对象存储中，仅本服务初始化失败时生成的 challenge 可进入宕机校验，避免一个用户初始化失败导致其他用户被放行。 |
| 号码验证状态 | `VERIFY_PHONE_OR_EMAIL` 根据非空号码及 `phoneNumberVerified=true` 判断号码已验证，不再仅判断号码属性存在。 |
| 短信服务商 | 显式配置的服务商未部署时拒绝发送，不再静默回退到其他实现或 `dummy`；服务商 ID 匹配仍不区分大小写。 |
| 腾讯云验证码协议 | `DescribeCaptchaResult` 固定发送协议必填的 `CaptchaType=9`；它不是滑块等验证码类型的枚举。旧 `spi-captcha-service-tencent-captcha-type` 配置不再控制请求，非 `9` 值只记录警告，纠正 2.1.0 的错误修改。 |
| 极验配置接口 | 极验 v3 初始化响应恢复前端所需的 `success`、`gt`、`challenge`、`new_captcha`，并保留统一配置字段；v4 响应结构不变。 |
| 阿里云短信 | 只有响应 `Code=OK` 才视为发送成功，其他业务错误和无效响应返回失败；发送国际号码时保留区号。 |
| Everybody 自动开户 | realm 中启用 `everybody-phone-authenticator` 且父流程未禁用时，`/sms/authentication-code` 允许未注册号码获取验证码，支持后续自动开户。 |
| 代码整理 | 删除未使用的 `PhoneNumber(JsonNode)` 构造器；验证码实体的 `equals`、`hashCode` 支持空 ID。 |
| 基线与依赖 | Keycloak 升级到 26.8.1，BouncyCastle 对齐到 1.86；升级腾讯云 SDK、Lombok、org.json 等依赖。阿里云模块继续由 Keycloak 提供 BouncyCastle，不在插件中重复捆绑。 |
| 回归测试 | 新增基于 Testcontainers 的 `SecurityFixesIT`，覆盖伪造及跨用户使用短信 OTP Cookie、Everybody 验证码重放、验证码作废后重发冷却仍生效，以及注册表单仅在成功时消费验证码等场景。 |

## 2.1.0

| 类别 | 变更 |
|---|---|
| Keycloak 基线 | 完成从 Keycloak 25.x 到 26.x 的升级，**2.1.0 发布标签的基线为 26.7.0**；26.6.4 只是升级过程中的中间版本，不是 2.1.0 的发布基线。 |
| 26.x 兼容与时间类型 | 延续并完善 26.x 兼容调整：使用 `AuthResult.user()`、`JsonNode.properties()`；持久层使用 `LocalDateTime`，移除 `@Temporal`，修复迁移后的时间戳及重发查询错误。部分调整已随 2.0.0 进入代码，不是全部首次引入于 2.1.0。 |
| 人机验证配置响应 | 清理 `CaptchaConfigResponse` 的历史 `success` 字段，统一使用 `captchaAppId` 等配置字段；极验 v3 初始化字段的兼容性随后在 2.2.0 恢复。 |
| 验证码泄露 | 移除业务发送及验证路径中将验证码明文写入 info 日志、Keycloak 事件详情的行为；测试专用 `dummy` 仍会输出验证码，不应部署到生产。 |
| 验证码防爆破 | 同一验证码累计校验失败达到 5 次即作废，必须重新发送。表 `PHONE_MESSAGE_TOKEN_CODE` 新增 `ATTEMPTS` 列，由 Liquibase 变更集 `token-code-attempts` 随插件启动迁移，无需手工加列。作废时误删发送历史的问题在 2.2.0 修复。 |
| 常量时间比较 | 验证码通过 `MessageDigest.isEqual` 比较，替代逐字符提前返回的字符串比较。 |
| REST 错误与频控 | 区分重发冷却 `RESEND_TOO_SOON`、小时发送超限 `SMS_SEND_LIMIT_EXCEEDED` 和归属地拦截 `ILLEGAL_PHONE_NUMBER`；重发冷却响应可携带 `details.resendExpires`。绑定号码时区分验证码不匹配与验证码流程失效。 |
| 腾讯云容灾票据 | 识别前端 `trerror_`、`terror_` 前缀容灾票据，增加 `spi-captcha-service-tencent-allow-disaster-ticket` 开关，默认放行；安全取舍详见 API.md。 |
| 凭据结构 | 使用凭据模型序列化 `credentialData`，修复手工拼接 JSON 导致遗漏 `areaCode` 或结构错误的问题。 |
| 腾讯云历史更正 | ~~修复腾讯云 `captchaType` 被硬编码 `9` 覆盖、配置项不生效的问题。~~ 该修改有误，**已在 2.2.0 更正**：协议要求 `CaptchaType` 固定为 `9`。 |
| 并发与健壮性 | 修复区号缓存、极验服务状态和 `JsonUtils` 的线程安全问题；验证码查询按创建时间选择最新记录，避免并发记录引发非唯一结果异常；修正 CORS 来源通配符匹配，并补齐请求字段和区号格式校验。 |
| 解绑与表单 | 解绑时同时清理号码验证状态和短信 OTP 凭据；登录表单失败后回显输入，但不回显密码与验证码。 |
| 依赖与体积 | 升级 BouncyCastle、阿里云 SDK、org.json、Lombok 等依赖；移除 okhttp，改用 JDK 原生解析 `redirect_uri` 查询参数。不保留未经构建产物核实的历史 jar 体积数字。 |
| 阿里云打包 | 阿里云模块不再捆绑 BouncyCastle，排除 SDK 传递引入的相关依赖，运行时统一使用 Keycloak 自带版本，修复生产模式启动时的类冲突。 |
| 测试 | 新增核心逻辑单元测试和基于 Testcontainers 的 Keycloak 集成测试模块。 |

## 2.0.0

| 类别 | 变更 |
|---|---|
| API 契约 | 统一短信与人机验证接口的响应结构，使用标准 HTTP 状态码表达成功或失败，不再以 HTTP 200 配合 `status` 字段统一表示业务结果。 |
| 成功响应 | 成功时直接返回数据对象，移除 `status` 包装；发送验证码的时间字段改为 `expiresIn`、`resendExpires`，绑定与解绑成功使用 HTTP 204 空响应。 |
| 错误响应 | 引入统一错误模型，以 `code`、`message`、`timestamp` 及可选 `details` 表达错误，替代 `error`、`errormsg`。后续版本继续完善具体错误映射。 |
| 人机验证 | 加入腾讯云验证码支持；配置响应使用 JSON 对象和统一配置字段。 |
| 兼容性 | **破坏性更新**：v1.x 前端需要同步调整接口调用、成功状态判断、字段名和错误处理；不能继续依赖 `status`。迁移步骤见 [API.md](./API.md)。 |

## 1.2.x（旧版本）

以下记录以仓库 `v1.2.1` 源码为历史参照，不代表当前接口。

| 类别 | 历史行为 |
|---|---|
| HTTP 状态 | 短信接口的常规业务成功与失败均通过 HTTP 200 返回，并非当前标准状态码契约。解析异常等非业务失败仍可能返回 HTTP 500，不能理解为所有请求绝对只返回 200。 |
| 业务状态 | 使用 `status` 数值表示结果，包括 `1`、`0`、`-1`、`-2`。 |
| 错误字段 | 业务错误同时包含 `error`、`errormsg` 两个字段。 |
| 时间字段 | 发送验证码响应使用 `expires_in`、`resend_expires`，查询重发时间使用 `resend_expire`；当前字段及响应结构以 [API.md](./API.md) 为准。 |
