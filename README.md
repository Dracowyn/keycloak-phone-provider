# Keycloak Phone Provider

为 Keycloak 增加手机号登录、短信验证码、短信 MFA、手机号注册和凭据重置的 SPI 插件。本文面向部署运维与 realm 管理员；完整接口契约见 [API.md](./API.md)，版本变化见 [CHANGELOG.md](./CHANGELOG.md)。

## 目录

- [简介与来源](#简介与来源)
- [快速开始](#快速开始)
- [配置参考](#配置参考)
- [认证流程配置](#认证流程配置)
- [API 概览](#api-概览)
- [生产环境检查清单](#生产环境检查清单)
- [开发](#开发)
- [版本与升级](#版本与升级)
- [致谢](#致谢)

## 简介与来源

上游：[cooperlyt/keycloak-phone-provider](https://github.com/cooperlyt/keycloak-phone-provider)。本项目基于上游 [`10.0.2` 分支](https://github.com/cooperlyt/keycloak-phone-provider/tree/10.0.2)二次开发；该历史基线是 **Keycloak 10.0.2**，不是 11.0.3。上游后续提交 `af83e7e` 才升级到 11.0.3，本项目继续适配至 Keycloak 26。

配套前端：[Dracowyn/keycloak-phone-provider-frontend](https://gitee.com/Dracowyn/keycloak-phone-provider-frontend)。登录主题 `phone` 由前端项目单独提供和安装，**本仓库的生产模块不附带 FTL 模板**；集成测试的 `phone-it` 主题只用于测试。上游 Android 示例已迁移至 [KeycloakPhoneClient](https://github.com/cooperlyt/KeycloakPhoneClient)，其历史调用需要按 [API.md](./API.md#前端迁移步骤)核对后使用。

### 已支持

| 能力 | 范围 |
| --- | --- |
| 短信发送 | 阿里云短信；按 realm、验证码用途覆盖模板和签名 |
| 手机号认证 | 浏览器短信登录、手机号或密码登录、直授手机号认证 |
| 短信 MFA | `OTP over SMS`、首次配置必需操作、签名免短信验证 Cookie |
| 用户开户与维护 | 手机号注册、手机号或邮箱注册、Everybody 自动开户、验证码绑定与解绑、凭据重置 |
| 人机验证 | 极验 v3/v4、腾讯云验证码；reCAPTCHA 仅后端校验，前端配置不返回 siteKey |
| 区号与归属地 | 区号许可列表与前端选择配置、归属地黑名单检查 |
| 注册扩展 | 最简注册页面标记、从 `redirect_uri` 查询参数写入用户属性 |

### 不支持

| 能力 | 当前边界 |
| --- | --- |
| 开箱即用的多个生产短信服务商 | 当前只有阿里云生产发送实现；`dummy` 只输出日志，不发短信。新增发送商应参考阿里云模块的配置与结果处理，而非把 dummy 当生产实现 |
| 内置生产登录主题 | 需单独部署前端项目提供的主题 |
| 完整 reCAPTCHA 前后端集成 | 后端可校验 `g-recaptcha-response`，配置接口不返回站点密钥，且没有 POST 配置方法 |
| Bearer 调用绑定、解绑 | 两个接口只接受 Keycloak 登录会话 Cookie |

### 兼容性

| 项目 | 要求 |
| --- | --- |
| Keycloak | 26.x；当前编译与集成测试基线为 26.8.1，其他小版本应在目标环境验证 |
| Java 编译目标 | Java 17（`release=17`） |
| Java 运行环境 | 推荐 Java 21；插件的编译目标不替代所选 Keycloak 发行版自身的 JVM 要求 |

## 快速开始

### 模块与 jar

根目录构建会把六个 provider jar 汇总到 `target/providers/`，文件名不带版本号。按需部署，不要将该目录全部复制到生产环境。

| 模块 / jar | 用途 | 是否必需 |
| --- | --- | --- |
| `keycloak-phone-provider` / `keycloak-phone-provider.jar` | 核心 SPI、认证器、必需操作、短信 REST 与验证码持久化 | 是 |
| `keycloak-sms-provider-aliyun` / `keycloak-sms-provider-aliyun.jar` | 阿里云短信发送 | 生产发送使用阿里云时必需 |
| `keycloak-sms-provider-dummy` / `keycloak-sms-provider-dummy.jar` | 将验证码写入 info 日志，不发送短信 | 仅本地调试、联调和测试；生产不部署 |
| `keycloak-captcha-provider-tencent` / `keycloak-captcha-provider-tencent.jar` | 腾讯云人机验证及配置资源 | 按需；与前端选择保持一致 |
| `keycloak-captcha-provider-geetest` / `keycloak-captcha-provider-geetest.jar` | 极验 v3/v4 人机验证及配置资源 | 按需 |
| `keycloak-captcha-provider-recaptcha` / `keycloak-captcha-provider-recaptcha.jar` | reCAPTCHA 后端校验；配置接口不返回 siteKey | 按需，前端需自行集成 |
| `keycloak-phone-provider-it` / 无部署 jar | Testcontainers 集成测试，默认不加入构建 | 不部署；通过 `it` profile 启用 |

发送资源和各人机验证配置资源均获取当前默认的 `CaptchaService`，不根据请求路径或参数自动选厂商。最简单的部署是只安装一个人机验证模块；不要把三个模块一起复制后假定它们会自动匹配各自 URL。

### 构建与安装

在仓库根目录构建：

```bash
mvn -B clean package
```

以下以阿里云发送为例，`KEYCLOAK_HOME` 指向已安装的 Keycloak。只复制核心与实际使用的模块，之后执行 Keycloak 构建：

```bash
cp target/providers/keycloak-phone-provider.jar "$KEYCLOAK_HOME/providers/"
cp target/providers/keycloak-sms-provider-aliyun.jar "$KEYCLOAK_HOME/providers/"
# 按前端选型另行复制一个人机验证模块，并配齐该模块所需键。
cp keycloak-phone-provider/areacodes/areacode.json "$KEYCLOAK_HOME/conf/areacode.json"
"$KEYCLOAK_HOME/bin/kc.sh" build
```

部署前端项目提供的 `phone` 主题后，在 realm 设置（Realm settings）> 主题（Themes）> 登录主题（Login theme）选择 `phone`。不要把生产主题与测试主题 `phone-it` 混用。

### 最小插件配置

以下写入 Keycloak 的 `conf/keycloak.conf`，只包含插件所需配置，不替代数据库、主机名、TLS 等 Keycloak 服务配置。替换凭据、模板、签名和路径占位值；阿里云大写下划线键的原因见[配置命名例外](#配置命名例外)。

```properties
spi-phone-provider-config-sender-service=aliyun
spi-phone-provider-config-token-expires=300
spi-phone-provider-config-default-areacode=86
spi-phone-provider-config-areacode-config=/absolute/path/to/keycloak/conf/areacode.json
spi-phone-provider-config-area-locked=false
spi-message-sender-service-aliyun-access-key-id=<阿里云AccessKeyId>
spi-message-sender-service-aliyun-access-secret=<阿里云AccessKeySecret>
spi-message-sender-service-aliyun-DEFAULT_TEMPLATE=<短信模板ID>
spi-message-sender-service-aliyun-DEFAULT_SIGNNAME=<短信签名>
```

`sender-service=aliyun` 按 provider ID **不区分大小写**匹配。指定实现未部署或拼错时拒绝发送，不会回退到 dummy 或其他实现。不部署任何人机验证模块时会跳过人机验证；上述最小配置不是完整的生产防刷配置。

### 必须部署 areacode.json

| 项目 | 要求与失败表现 |
| --- | --- |
| 样例 | [keycloak-phone-provider/areacodes/areacode.json](./keycloak-phone-provider/areacodes/areacode.json) |
| 默认路径 | `./areacode.json`，相对 **Keycloak 进程工作目录**，不是自动相对 Keycloak 安装目录或 `conf/` |
| 推荐路径 | 在 `spi-phone-provider-config-areacode-config` 中指定已部署文件的绝对路径，并保证进程可读 |
| 内容 | JSON 数组，每项包含数字 `areaCode`、字符串 `countryCode`、语言到名称的对象 `name` |
| 未就位 | 文件无法读取或解析时，区号许可检查不通过；所有发送都无法成功，通过前置参数与人机检查的请求返回 `403 AREA_NOT_SUPPORTED`；`GET /sms` 返回 `500 INTERNAL_ERROR` |
| 默认区号 | 只是对前端提供的配置，不会为缺少 `areaCode` 的请求自动补值 |

## 配置参考

常规键在 `keycloak.conf` 中使用下表的 kebab-case 写法；命令行形式在键前加 `--`，环境变量形式加 `KC_` 后将连字符改为下划线并全大写，例如 `spi-phone-provider-config-sender-service` 对应 `--spi-phone-provider-config-sender-service=aliyun` 与 `KC_SPI_PHONE_PROVIDER_CONFIG_SENDER_SERVICE=aliyun`。

### 配置命名例外

当前源码有两组**不能机械转成 kebab-case**的键：阿里云动态拼出的 `DEFAULT_TEMPLATE`、`DEFAULT_SIGNNAME` 与分层覆盖键，以及极验 v4 的 `fallback_on_error`。Keycloak 26.8.1 的 `Configuration.toDashCase` 保留这些全大写与下划线，`keycloak.conf` 加载也只添加 `kc.` 前缀；写成 `default-template` 或 `fallback-on-error` 不会命中这些查询。下表保留可实际生效的精确键，不能为了统一排版提供无效配置。

这些例外优先写入配置文件；若使用环境变量，应通过配置文件的 `${变量名}` 引用，或用 `KC_自选标识` 与 `KCKEY_自选标识` 显式映射原始键，不能依赖普通的下划线自动转换。规则依据：[Keycloak 26.8.1 配置源码](https://github.com/keycloak/keycloak/blob/26.8.1/quarkus/runtime/src/main/java/org/keycloak/quarkus/runtime/configuration/Configuration.java)、[环境变量映射源码](https://github.com/keycloak/keycloak/blob/26.8.1/quarkus/runtime/src/main/java/org/keycloak/quarkus/runtime/configuration/KcEnvConfigSource.java)。

### 核心：phone-provider-config

下表列出 `ConfigService` 的全部键；归属地三项的组合行为在后文说明。

| 键名 | 默认值 | 说明 |
| --- | --- | --- |
| `spi-phone-provider-config-sender-service` | `dummy` | 短信发送 provider ID；生产显式设为 `aliyun` 并部署对应 jar；忽略大小写，不回退 |
| `spi-phone-provider-config-token-expires` | `300` | 验证码有效期，秒 |
| `spi-phone-provider-config-default-areacode` | `86` | 返回前端的默认区号；注意是 `areacode`，不是 `area-code` |
| `spi-phone-provider-config-areacode-config` | `./areacode.json` | 区号许可列表路径；文件必须存在 |
| `spi-phone-provider-config-area-locked` | `false` | 返回前端的区号锁定标记；服务端仍按列表校验请求区号，不强制等于默认值 |
| `spi-phone-provider-config-allow-unset` | `true` | 是否允许 `/sms/update-profile/unset`；允许时仍要求用户邮箱已验证 |
| `spi-phone-provider-config-location-verify` | `false` | 启用归属地黑名单检查 |
| `spi-phone-provider-config-location-appcode` | 未配置 | 归属地查询服务的 APPCODE |
| `spi-phone-provider-config-location-black-list` | 未配置 | 归属地黑名单，英文逗号分隔 |

### 阿里云短信

| 键名 | 默认值 | 说明 |
| --- | --- | --- |
| `spi-message-sender-service-aliyun-access-key-id` | 未配置 | 阿里云 AccessKeyId，需具备短信调用权限 |
| `spi-message-sender-service-aliyun-access-secret` | 未配置 | 阿里云 AccessKeySecret，只保存在服务端 |
| `spi-message-sender-service-aliyun-DEFAULT_TEMPLATE` | 未配置 | 默认模板 ID；精确键名例外，保留大写与下划线 |
| `spi-message-sender-service-aliyun-DEFAULT_SIGNNAME` | 未配置 | 默认短信签名；同上 |
| `spi-message-sender-service-aliyun-<TYPE>_<KEY>` | 回退到 `DEFAULT_<KEY>` | 按验证码用途覆盖；占位部分使用源码要求的大写值 |
| `spi-message-sender-service-aliyun-<REALM>_<TYPE>_<KEY>` | 回退到 `<TYPE>_<KEY>` | 再按 realm 覆盖，realm 名先转大写 |

模板与签名分别按 **`<REALM>_<TYPE>_<KEY>` → `<TYPE>_<KEY>` → `DEFAULT_<KEY>`** 查找第一个非 `null` 值。`KEY` 是 `TEMPLATE` 或 `SIGNNAME`；`TYPE` 使用枚举名，不使用 REST 路径：

| TYPE | 用途 |
| --- | --- |
| `VERIFY` | 绑定号码、首次配置短信 MFA |
| `OTP` | 短信 MFA 与手机号直授 |
| `LOGIN` | 浏览器手机号登录 |
| `REGISTRATION` | 注册 |
| `RESET` | 重置凭据 |

模板参数固定提交 `{"code":"验证码"}`，未把有效期作为模板参数发送。当前阿里云重发冷却为 60 秒；只有业务响应 `Code=OK` 才判发送成功。国内区号 86 发送号码部分，国际号码拼接区号，不加 `+` 或 `00`。

### dummy

| 键名 | 默认值 | 说明 |
| --- | --- | --- |
| 无独立配置键 | 不适用 | 构造器不读取配置；通过核心 `sender-service=dummy` 选择。只将完整号码和验证码写入 info 日志；返回成功、冷却 60 秒，有效期沿用核心配置 |

只用于本地或隔离测试。生产既不要选择 dummy，也不要部署该 jar。

### 腾讯云人机验证

| 键名 | 默认值 | 说明 |
| --- | --- | --- |
| `spi-captcha-service-tencent-secret-id` | 未配置 | 腾讯云 SecretId，必填 |
| `spi-captcha-service-tencent-secret-key` | 未配置 | 腾讯云 SecretKey，必填 |
| `spi-captcha-service-tencent-captcha-app-id` | 未配置 | 验证码应用 ID，必填且应可解析为整数 |
| `spi-captcha-service-tencent-app-secret-key` | 未配置 | 验证码应用密钥，必填 |
| `spi-captcha-service-tencent-endpoint` | `captcha.tencentcloudapi.com` | 腾讯验证码 API endpoint |
| `spi-captcha-service-tencent-allow-disaster-ticket` | `true` | 是否接受 `trerror_`、`terror_` 前缀容灾票据；设为 `true` 会接受可自行构造的前缀字符串 |
| `spi-captcha-service-tencent-fallback-on-error` | `false` | 捕获腾讯 SDK 异常时是否放行；与容灾票据开关独立 |
| `spi-captcha-service-tencent-captcha-type` | 未配置；协议固定为 `9` | 旧配置只在值非 `9` 时告警，不改变请求。`CaptchaType=9` 不是滑块或点选类型枚举 |

**四个必填键缺任一项（值为 `null`）时，代码会跳过人机验证并直接放行**，不是拒绝服务。配齐后前端随发送请求提交 `ticket`、`randstr`；具体协议与容灾取舍见 [API.md](./API.md#腾讯云验证码容灾票据)。

### 极验

| 键名 | 默认值 | 说明 |
| --- | --- | --- |
| `spi-captcha-service-geetest-id` | 未配置 | 极验应用 ID |
| `spi-captcha-service-geetest-key` | 未配置 | 极验密钥 |
| `spi-captcha-service-geetest-version` | `3` | 取 `3` 或 `4`，前端 SDK 与验证字段必须一致；源码仅在值为 `4` 时走 v4 |
| `spi-captcha-service-geetest-fallback_on_error` | `true` | **命名例外，保留下划线**；仅 v4 捕获连接/处理异常时决定是否放行 |

**ID 或密钥任一未配置时会直接放行人机验证**。v3 的 `digestmod` 在源码中固定为 `md5`，不是可配置键。v3 初始化失败按 challenge 使用宕机模式，v4 的异常回退另受上表开关控制；字段和响应结构见 [API.md](./API.md#人机验证配置)。

### reCAPTCHA

| 键名 | 默认值 | 说明 |
| --- | --- | --- |
| `spi-captcha-service-recaptcha-secret` | 未配置 | 后端向 `https://www.recaptcha.net/recaptcha/api/siteverify` 校验时使用的 secret；前端随发码请求提交 `g-recaptcha-response` |

没有可供前端配置接口读取的 siteKey 键，`getFrontendKey` 恒返回空字符串；`GET /recaptcha/code` 只返回 `type`。不要把“有后端校验模块”理解为主题已经完成适配。

### 归属地黑名单

| 键名 | 默认值 | 说明 |
| --- | --- | --- |
| `spi-phone-provider-config-location-verify` | `false` | 开启时才查询；未配置 APPCODE 也不会拦截 |
| `spi-phone-provider-config-location-appcode` | 未配置 | 对 `https://ec8a.api.huachen.cn/mobile` 发起 APPCODE 认证查询 |
| `spi-phone-provider-config-location-black-list` | 未配置 | 英文逗号分隔；用每项去匹配查询结果 `data.isp` 的子串，项与结果会去首尾空格 |

例如拦截运营商字段中含“虚拟”的号码：

```properties
spi-phone-provider-config-location-verify=true
spi-phone-provider-config-location-appcode=<归属地查询APPCODE>
spi-phone-provider-config-location-black-list=\u865a\u62df
```

命中时发送接口返回 `403 ILLEGAL_PHONE_NUMBER`；查询、解析或黑名单处理异常只记日志并按未命中处理，不返回 `LOCATION_CHECK_FAILED`。启用时必须同时配置有效 APPCODE 和黑名单，不能把该机制视为查询失败即拒绝的强制风控。

## 认证流程配置

以下路径采用“中文（English）”，英文标签按 Keycloak 26.8.1；内置流程别名为 `browser`、`direct grant`、`registration`、`reset credentials`。在身份验证（Authentication）> 流程（Flows）通过操作（Action）> 复制（Duplicate）创建副本，再编辑副本，不直接修改内置流程。添加执行器使用添加步骤（Add step）；需求（Requirement）按文中设置。绑定使用操作（Action）> 绑定流程（Bind flow），不沿用旧截图里的 Bindings 标签。

### 浏览器短信 MFA

**前置条件：** 已部署短信服务、区号文件与配套主题；已有可识别用户的浏览器登录步骤。在身份验证（Authentication）> 必需操作（Required actions）注册并启用 `Configure OTP over SMS`（`CONFIGURE_SMS_OTP`）；需要资料更新入口时再启用 `Update Phone Number`。这些操作不是短信发送接口。

**步骤：** 复制 `browser`，在完成用户名/密码等第一因子之后的表单子流程内添加 `OTP over SMS`（`sms-otp-authenticator`），设为必需（Required）；放在 `Username Password Form` 之后，不要放进按“用户已有凭据”才执行的条件子流程，否则未配置用户可能到不了首次配置步骤。若只需要短信第二因子，停用副本中原有的 TOTP 要求，避免同时要求两种 OTP。执行器配置 `Cookie Max Age` 的内部键为 `cookie.max.age`，默认 3600 秒，缺失、非法或非正数也使用默认值。通过绑定流程（Bind flow）选择浏览器流程（Browser flow）。未配置短信凭据时，执行器自动下发 `CONFIGURE_SMS_OTP`；其验证码通过 `/sms/verification-code` 获取：无号码或号码未验证的用户可绑定新号码并创建凭据，已有已验证号码的用户只能使用原号码。凭据 provider ID 为 `sms-otp`，数据库存储类型为 `phone-otp`。完成短信验证后签发的 `SMS_OTP_ANSWERED` 是绑定 realm、用户与凭据 ID 的签名 Cookie，不是可填写 `true` 的开关。

**失败表现：** 首次配置提交不同于已验证号码的值会报 `smsOtpPhoneNumberMismatch`，不修改号码与凭据；主题需提供该消息。号码/验证码缺失或校验失败仍显示 `login-sms-otp-config.ftl` 并回填号码，不再渲染改号页。正常 MFA 校验失败停留在短信验证页；旧版、伪造、跨用户或绑定凭据已删除的 Cookie 不产生免验证效果。首次配置规则自 2.2.1 起生效，详见 [CHANGELOG](./CHANGELOG.md)。

### 浏览器手机号登录表单

**前置条件：** 配套主题提供 `login-with-phone.ftl` 或适配后的 `login.ftl`；用户已有已验证手机号。表单短信从 `/sms/login-code` 获取，类型是 `LOGIN`，不能混用直授的 `authentication-code`。

**步骤：** 复制 `browser`，在表单子流程中替换原 `Username Password Form`，按需求选择一个必需（Required）认证器：`Phone Credit Login Form`（`auth-phone-login-form`）只处理 `loginType=phone` 的手机号验证码登录；`Phone Or Password Login Form`（`auth-phone-password-login-form`）在 `loginType=phone` 时用手机号验证码，否则沿用用户名/密码验证。主题应提交 `areaCode`、`phoneNumber`、`smsCode` 及正确的 `loginType`。通过绑定流程（Bind flow）绑定浏览器流程（Browser flow）；也可在客户端（Clients）> `<client-id>` > 高级（Advanced）> 认证流程覆盖（Authentication flow overrides）中选择浏览器流程（Browser Flow）。

**失败表现：** 未找到已验证号码用户、缺验证码或代码不匹配时返回表单错误；禁用用户或被暴力破解保护临时禁用的用户不能通过。`Phone Credit Login Form` 不收到 `loginType=phone` 会标记尝试但不认证；缺少对应 FTL 模板会导致页面无法正常渲染。它们是浏览器表单，不是给 token 端点替换参数的开关。

### 直授手机号登录

**前置条件：** 客户端（Clients）> `<client-id>` > 设置（Settings）> 能力配置（Capability config）开启直接访问授权（Direct access grants）；用户已有已验证手机号且没有未完成必需操作；验证码从 `/sms/authentication-code` 获取。

**步骤：** 复制 `direct grant`，删除或禁用副本中原用户名、密码等不需要的执行器，依次添加 `Provide phone number`（`phone-number-authenticator`）与 `Provide verification code`（`verification-code-authenticator`），均设为必需（Required）。通过绑定流程（Bind flow）选择直接授权流程（Direct grant flow）；或在客户端（Clients）> `<client-id>` > 高级（Advanced）> 认证流程覆盖（Authentication flow overrides）选择直接授权流程（Direct Grant Flow）。向 OIDC token 端点发送 form 参数 `grant_type=password`、`client_id`、`areaCode`、`phoneNumber`、`smsCode`，机密客户端另需客户端认证。

**失败表现：** 手机号、用户或验证码不通过时，认证器返回 OAuth `invalid_grant`，不是短信 REST 的 `ApiError`；验证码按 `OTP` 校验并在成功时消费。未启用直授、未绑定正确流程或尚有必需操作时也不能取得 token。执行器显示的 `Verification Code Max Age` 未被当前校验逻辑读取，有效期以短信发送与持久化记录为准。

### Everybody 自动开户

**前置条件：** 允许通过手机号验证创建新账户，客户端开启直接访问授权（Direct access grants）；需按业务调整 User Profile 必填规则和默认必需操作，因为直授无法交互补填资料。

**步骤：** 复制 `direct grant`，仅保留必需（Required）的 `Authentication everybody by phone`（`everybody-phone-authenticator`），按上一节绑定为 realm 或客户端直授流程。新号码先请求 `/sms/authentication-code`，再提交 `areaCode`、`phoneNumber`、`smsCode` 换取 token。只有 realm 的顶层流程树中存在启用的 Everybody 执行器、且所在父执行流程未禁用时，该发送端点才允许未注册号码；发码门控检查流程存在性，并不检查当前客户端最终绑定的是哪条流程。验证码通过后，找不到已验证号码用户时以号码部分创建并启用账户，再写入已验证手机号。

**失败表现：** 未启用该执行器时，`authentication-code` 对未找到用户的号码返回 `USER_NOT_FOUND`；`login-code`、`reset-code` 也会返回该码，`registration-code` 与 `verification-code` 不会。手机号部分已作为其他用户名存在会拒绝开户。新用户缺少 User Profile 必填项或有未完成必需操作时，直授会被 Keycloak 拒绝，不能承诺短信验证后必然取得 token。人机验证、重发冷却与小时记录检查仍适用，准确边界见 [API.md](./API.md#频控与验证码生命周期)。

### 注册

**前置条件：** realm 设置（Realm settings）> 登录（Login）启用用户注册（User registration）；部署支持注册字段的主题。realm 设置（Realm settings）> 用户资料（User profile）必须声明 `phoneNumber` 属性并配置相应访问权限，尤其使用手机号创建动作时，未声明属性会被 User Profile 丢弃。仅手机号注册还需调整姓名、邮箱等必填规则；隐藏页面字段不会取消后端要求。

**步骤：** 复制 `registration`，在注册表单子流程内按下表选配动作，新增项设为必需（Required）。保留且只使用一个用户创建动作，把创建动作放在验证动作之前；仅手机号方案可用手机号创建动作替换 `Registration User Creation`，手机号/邮箱二选一方案保留普通用户创建。通过绑定流程（Bind flow）选择注册流程（Registration flow）。

| 显示名 | ID | 用法 |
| --- | --- | --- |
| `Phone Validation` | `registration-phone` | 必须填写手机号与 `/sms/registration-code` 获取的 `smsCode` |
| `Phone Or Email Validation` | `registration-phone-or-email` | 与上一项按方案二选一；主题提交 `credentialType=phone` 或 `email`，手机号分支校验注册验证码，邮箱分支校验邮箱与密码 |
| `Registration Phone As Username Creation` | `registration-phone-username-creation` | 仅手机号方案；替换普通用户创建并移到最前。不要启用邮箱作为用户名，也不要用于邮箱注册分支 |
| `Registration Least` | `registration-least` | 给主题设置 `registrationLeast=true` 以简化页面；不会代替 User Profile 校验 |
| `Redirect parameter reader` | `registration-redirect-parameter` | 从实际 `redirect_uri` 的查询参数写用户属性，不是任意登录 URL 参数。执行器配置标签为 `Accept query param`，内部键 `registration.parameter.accept`，多个允许名称用 `##` 分隔；生产应显式设置允许名称，不能包含敏感账户属性 |

**失败表现：** 号码已被占用、验证码缺失或错误、User Profile 必填项不满足时，注册不能完成。验证码在表单 `validate` 阶段只检查，在 `success` 阶段才消费，其他字段失败不应仅因此重新发码。需要无密码注册时可停用副本中的 `Password Validation`，但必须同时调整主题与资料策略；邮箱分支自身仍要求密码。参数读取器只设置用户属性，不会修改客户端允许的重定向 URI，也不能使不合法的 `redirect_uri` 通过 Keycloak 检查。

### 重置凭据

**前置条件：** realm 设置（Realm settings）> 登录（Login）开启忘记密码（Forgot password）；主题提供 `login-reset-password-with-phone.ftl`。短信分支用户应有已验证号码；保留邮箱分支时需配好 realm 邮件服务。

**步骤：** 复制 `reset credentials`，把 `Choose User` 替换为必需（Required）的 `Reset Credential With Phone`（`reset-credentials-with-phone`）；把 `Send Reset Email` 替换为 `Send Reset Email If Not Phone`（`reset-credential-email-with-phone`），**该项设为条件（Conditional）**。保留后面的 `Reset Password` 等所需步骤，通过绑定流程（Bind flow）选择重置凭据流程（Reset credentials flow）。手机号分支不提交可命中账户的 `username`，而用 `areaCode`、`phoneNumber` 和 `/sms/reset-code` 获取的 `smsCode`；校验成功写入跳过邮件标记。用户名/邮箱分支仍经邮件验证后进入密码重置。

**失败表现：** 手机号未找到用户或 `RESET` 验证码不通过时停留在重置表单。`Send Reset Email If Not Phone` 设为必需（Required）会无条件走邮件，设为替代（Alternative）则该实现直接成功、不执行邮件校验；两者都不等价于所需的条件分支。不要直接删除邮件验证后保留可仅输入用户名的分支。

### 必需操作一览

**前置条件：** 已部署核心模块和相应主题；在身份验证（Authentication）> 必需操作（Required actions）中注册并启用实际需要的操作，不要一律设为所有用户默认操作。

**步骤：** 按下表选择；没有短信凭据时由 MFA 执行器下发配置操作，无需把它强行加到所有用户。号码验证与解绑 REST 细节统一见 [API.md](./API.md#绑定与解绑手机号)。

| ID | 显示名 | 作用 |
| --- | --- | --- |
| `CONFIGURE_SMS_OTP` | `Configure OTP over SMS` | 校验 `VERIFY` 验证码并创建短信 OTP 凭据；首次绑定与已有验证号码保护见 MFA 小节 |
| `UPDATE_PHONE_NUMBER` | `Update Phone Number` | 显示资料更新页面；当前操作自身不验证新号码，并要求提交号码与现值一致。真正绑定/改号通过 `/sms/update-profile` 完成 |
| `VERIFY_PHONE_OR_EMAIL` | `Verify Phone or Email` | 邮箱与手机号均未验证时要求验证；只有非空号码且 `phoneNumberVerified=true` 才算手机已验证，否则进入继承的邮箱验证流程 |

**失败表现：** 必需操作未启用或主题不匹配时，认证可能无法完成。已有 `phoneNumber` 但 `phoneNumberVerified=false` 不满足手机已验证条件；直授客户端无法完成需要页面交互的必需操作。

## API 概览

所有路径均相对 `<base-url>/realms/<realm>`。`<base-url>` 包含部署时配置的相对路径；示例中的 `<realm>`、客户端占位、`13800000000` 与 `{{SMS_CODE}}` 都必须替换。

| 方法 | 路径 | 用途 | 认证要求 |
| --- | --- | --- | --- |
| GET | `/sms` | 短信配置与区号列表 | 无 |
| POST | `/sms/registration-code` | 注册验证码 | 人机验证，或有效客户端 ID 与密钥豁免失败结果 |
| POST | `/sms/authentication-code` | 直授与短信认证验证码 | 同上；新号码受 Everybody 门控 |
| POST | `/sms/login-code` | 浏览器手机号登录验证码 | 同上；需已验证号码用户 |
| POST | `/sms/reset-code` | 重置凭据验证码 | 同上；需已验证号码用户 |
| POST | `/sms/verification-code` | 绑定或首次配置 MFA 验证码 | 人机验证，或有效客户端 ID 与密钥豁免失败结果 |
| GET、POST | `/sms/{发送端点}/resend-expires` | 五个发送端点各自的重发冷却查询 | 无 |
| POST | `/sms/update-profile` | 验证并绑定手机号 | Keycloak Identity Cookie；不支持 Bearer |
| POST | `/sms/update-profile/unset` | 解绑号码与短信 OTP 凭据 | 同上；另有配置与邮箱验证前置条件 |
| GET、POST、OPTIONS | `/geetest/code` | 极验配置与预检 | 不强制登录 |
| GET、POST、OPTIONS | `/tencent/code` | 腾讯云配置与预检 | 不强制登录 |
| GET、OPTIONS | `/recaptcha/code` | reCAPTCHA 配置与预检；无 POST | 不强制登录；不返回 siteKey |
| POST | `/protocol/openid-connect/token` | 手机号直授换 token | 客户端认证与已绑定的直授流程 |

### 示例一：发送登录 / 认证验证码

以下是**可信后端**使用机密客户端密钥获取 `OTP` 验证码的完整示例；前端直接发送时不得带 secret，应提交对应服务商的人机验证结果。浏览器登录表单应改用 `/sms/login-code`，不要混用验证码类型。

```bash
curl -X POST '<base-url>/realms/<realm>/sms/authentication-code' \
  --data-urlencode 'areaCode=86' \
  --data-urlencode 'phoneNumber=13800000000' \
  --data-urlencode 'client_id=<client-id>' \
  --data-urlencode 'client_secret=<client-secret>'
```

成功为 HTTP 200；两个值都是 epoch 毫秒时间戳，不是剩余秒数：

```json
{"expiresIn":1793097300000,"resendExpires":1793097060000}
```

### 示例二：直授换取 token

客户端已启用直授并绑定前述手机号流程，提交刚获取的 `OTP` 验证码；公共客户端省略 `client_secret`。

```bash
curl -X POST '<base-url>/realms/<realm>/protocol/openid-connect/token' \
  --data-urlencode 'grant_type=password' \
  --data-urlencode 'client_id=<client-id>' \
  --data-urlencode 'client_secret=<client-secret>' \
  --data-urlencode 'areaCode=86' \
  --data-urlencode 'phoneNumber=13800000000' \
  --data-urlencode 'smsCode={{SMS_CODE}}'
```

成功为 HTTP 200，返回 Keycloak 标准令牌对象；以下仅展示访问令牌字段：

```json
{"access_token":"<Keycloak签发的访问令牌>"}
```

其余端点的 form/JSON 参数、旧字段兼容范围、全部响应与错误码、人机验证字段及 CORS、安全行为和前端迁移，只在 [API.md](./API.md) 维护。

## 生产环境检查清单

| 检查项 | 验收条件 / 风险 |
| --- | --- |
| 真实发送商 | 显式配置 `sender-service=aliyun`，已部署对应 jar，模板、签名、权限与真实发送验证通过；不依赖默认 dummy，不假定配置错误会回退 |
| 不部署 dummy | `providers/` 中没有 `keycloak-sms-provider-dummy.jar`，避免验证码进入 info 日志 |
| 人机验证选型 | 安装并确认实际选中实现；腾讯四个必填键、极验 ID/密钥全部配齐，缺项会直接放行；未部署任何实现也会跳过 |
| 故障放行策略 | 显式决定腾讯 `allow-disaster-ticket` 与 `fallback-on-error`、极验 v4 `fallback_on_error`；腾讯容灾票据默认放行可被自行构造，安全敏感场景应评估设为 `false` |
| 客户端密钥 | 已启用的机密客户端凭正确 `client_id` + `client_secret` 可豁免人机验证失败结果，secret 只能放可信后端；**已有登录 Cookie 并不自动豁免人机验证** |
| 反向代理与真实 IP | 人机验证把 Keycloak 连接的 `remoteAddr` 发给厂商；按 [Keycloak 26.8.1 代理指南](https://github.com/keycloak/keycloak/blob/26.8.1/docs/guides/server/reverseproxy.adoc)配置可信代理，并由代理覆盖转发头，避免伪造或全变为代理 IP |
| 区号文件 | `areacode.json` 已放到配置路径、进程可读、内容有效；`GET /sms` 返回许可列表 |
| 主题与资料 | `phone` 已单独部署，补齐 `smsOtpPhoneNumberMismatch` 文案，声明 `phoneNumber`，必填字段与各流程一致 |
| 数据库迁移 | 验证码表为 `PHONE_MESSAGE_TOKEN_CODE`；`ATTEMPTS` 由 Liquibase `token-code-attempts` 自动迁移，有列存在前置检查，无需手工加列；按数据库变更流程备份与观察启动日志 |
| 短信防滥用 | 不把现有小时记录阈值当作严格“每号码每类型每小时最多 3 次”；实际 `>3` 边界与成功消费清记录的影响见 [API.md](./API.md#频控与验证码生命周期) |

## 开发

### 构建与验证命令

| 目的 | 命令 | 说明 |
| --- | --- | --- |
| 构建 provider | `mvn -B clean package` | 在仓库根目录运行，汇总产物位于 `target/providers/` |
| 单元测试 | `mvn -B test` | 默认模块测试，不启动集成测试模块 |
| 集成测试 | `mvn -B -Pit verify` | 需要可用 Docker；`it` profile 才加入集成测试模块，使用真实 Keycloak 容器 |

`PhoneProviderIT` 覆盖 provider/SPI/JPA/Liquibase 加载、短信配置、dummy 发码持久化与重发查询。`SecurityFixesIT` 覆盖伪造及跨用户短信 Cookie 不可豁免、本人签名 Cookie 可用、Everybody 验证码不可重放、输错 5 次后保留冷却且更早发出的验证码不会重新生效、注册其他字段错误不提前消费验证码，以及首次 MFA 绑定新号码和禁止替换已有已验证号码。测试使用隔离 dummy 与 `phone-it` 主题，不能替代生产发送商和前端主题验收，也没有证明严格的小时配额。

### 发版约定

发布时同步各模块版本与 CHANGELOG，完成构建和验证后，为发布提交创建附注标签 `vX.Y.Z`，将提交和标签推送到 `github`、`码云`、`云效`，并在 GitHub Release 附上本次构建的 provider jar。以下为发布人员操作示意，不是部署步骤：

```bash
git tag -a vX.Y.Z -m 'vX.Y.Z: <本次变更说明>'
git push github <发布分支> vX.Y.Z
git push 码云 <发布分支> vX.Y.Z
git push 云效 <发布分支> vX.Y.Z
```

Release 附件取 `target/providers/` 的六个 provider jar，并标清可选模块与 dummy 的测试用途；集成测试模块不作为 provider 发布。

## 版本与升级

当前版本：**2.2.1**；Keycloak 基线：**26.8.1**。版本历史见 [CHANGELOG.md](./CHANGELOG.md)，接口和安全迁移见 [API.md](./API.md#前端迁移步骤)。

### 已知限制

| 项目 | 说明 |
| --- | --- |
| 小时发送上限 | 不是严格的“每号码每类型每小时最多 3 次”硬配额，验证成功也会清空计数；准确边界见 [API.md](./API.md#频控与验证码生命周期) |
| 跨来源前端与 reCAPTCHA | 跨域部署需在真实代理与浏览器 Cookie 策略下验收 CORS；reCAPTCHA 的 siteKey 需由前端自行提供 |
| 配置键命名 | 阿里云动态键与极验 `fallback_on_error` 保留原始写法，暂无 kebab-case 别名，见[配置命名例外](#配置命名例外) |

## 致谢

感谢上游作者及 [keycloak-sms-provider](https://github.com/mths0x5f/keycloak-sms-provider)、[keycloak-phone-authenticator](https://github.com/FX-HAO/keycloak-phone-authenticator) 项目的代码与实现参考。
