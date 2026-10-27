# 腾讯云验证码配置说明

## 重要说明

### 关于 `captchaType` 参数

**DescribeCaptchaResult 请求中的 `CaptchaType` 是协议必填参数，固定为9。服务始终设置该值，不由 Keycloak 配置控制。**

9 不是滑块、文字点选或图形点选等验证码类型枚举；这些交互类型在腾讯云控制台的验证码应用中配置。

旧 `captchaType` 配置不再控制请求参数：

- 未配置或值为字符串 `"9"`：请求发送9，不告警。
- 其他值（包括非数字、空字符串）：记录一条 warn，仍发送9，不再解析该配置为数字。

建议移除旧 `captchaType` 配置，不存在通过该配置覆盖协议参数的场景。

### 必需配置项

只需配置以下4个参数：

```properties
# Keycloak 25 配置文件 (conf/keycloak.conf)
spi-captcha-service-provider=tencent
spi-captcha-service-tencent-secret-id=YOUR_SECRET_ID
spi-captcha-service-tencent-secret-key=YOUR_SECRET_KEY
spi-captcha-service-tencent-captcha-app-id=YOUR_CAPTCHA_APP_ID
spi-captcha-service-tencent-app-secret-key=YOUR_APP_SECRET_KEY
```

## 配置步骤

### 1. 在腾讯云控制台创建验证码应用

1. 登录[腾讯云验证码控制台](https://console.cloud.tencent.com/captcha)
2. 创建新的验证码应用
3. **选择验证码类型**（滑块/文字点选/图形点选等）← 在这里配置，不在Keycloak中配置
4. 配置应用域名白名单
5. 获取以下信息：
   - `CaptchaAppId`: 验证码应用ID
   - `AppSecretKey`: 验证码应用密钥

### 2. 获取腾讯云API密钥

1. 访问[腾讯云API密钥管理](https://console.cloud.tencent.com/cam/capi)
2. 创建或查看现有密钥
3. 获取：
   - `SecretId`: API密钥ID
   - `SecretKey`: API密钥Key

### 3. 配置Keycloak

将获取的信息配置到Keycloak中（参考上面的必需配置项）。

## 配置示例

### 最小配置（推荐）

```properties
# conf/keycloak.conf
spi-captcha-service-provider=tencent
spi-captcha-service-tencent-secret-id=AKIDxxxxxxxxxxxxx
spi-captcha-service-tencent-secret-key=xxxxxxxxxxxxxxxxxxxxx
spi-captcha-service-tencent-captcha-app-id=2012345678
spi-captcha-service-tencent-app-secret-key=xxxxxxxxxxxxxxxxxxxxx
```

### 完整配置（包含可选项）

```properties
# conf/keycloak.conf
spi-captcha-service-provider=tencent

# 必需配置
spi-captcha-service-tencent-secret-id=AKIDxxxxxxxxxxxxx
spi-captcha-service-tencent-secret-key=xxxxxxxxxxxxxxxxxxxxx
spi-captcha-service-tencent-captcha-app-id=2012345678
spi-captcha-service-tencent-app-secret-key=xxxxxxxxxxxxxxxxxxxxx

# 旧captchaType配置不再控制请求；协议固定使用9，非"9"值仅告警
# spi-captcha-service-tencent-captcha-type=9
# 可选配置
# spi-captcha-service-tencent-endpoint=captcha.tencentcloudapi.com
# spi-captcha-service-tencent-fallback-on-error=false
```

## 验证码类型说明

`DescribeCaptchaResult.CaptchaType` 必须固定为9，与滑块、文字点选、图形点选等交互类型无关。服务对所有正常校验请求设置 `request.setCaptchaType(9L)`，不使用旧 `captchaType` 配置决定参数。

## 常见问题

### Q1: 为什么之前的文档要求配置 captchaType？

**A**: 之前的说明混淆了协议参数与验证码交互类型。`CaptchaType` 是协议必填固定值9，由服务设置，不需要部署者配置。

### Q2: 如果我配置了 captchaType 会怎样？

**A**: 旧配置不再控制请求。值为字符串 `"9"` 时不告警；其他值（包括非数字和空字符串）仅记录一条 warn，请求仍发送9。

### Q3: 我在腾讯云控制台修改了验证码类型，需要修改Keycloak配置吗？

**A**: **不需要修改 `captchaType`**。交互类型由腾讯云控制台配置，与协议固定值9无关。如果应用ID或密钥改变，仍需更新对应配置。

### Q4: 可以通过 captchaType 覆盖协议参数吗？

**A**: 不可以。DescribeCaptchaResult 请求固定发送9，旧配置不会覆盖它。

### Q5: 我的配置里有 captchaType，需要删除吗？

**A**: 建议移除。保留值 `"9"` 不告警；其他值只会产生告警，不再控制请求参数。

## 代码实现

### 协议固定值逻辑

```java
// 构建请求
DescribeCaptchaResultRequest request = new DescribeCaptchaResultRequest();

// CaptchaType是协议必填固定值9，不代表滑块、文字点选等验证码类型。
request.setCaptchaType(9L);
String captchaTypeStr = config.get("captchaType");
if (captchaTypeStr != null && !"9".equals(captchaTypeStr)) {
    log.warn("captchaType旧配置不再控制请求参数，DescribeCaptchaResult固定使用CaptchaType=9");
}

request.setTicket(ticket);
request.setRandstr(randstr);
request.setUserIp(userIp);
request.setCaptchaAppId(Long.parseLong(captchaAppId));
request.setAppSecretKey(appSecretKey);
```

### 行为保证

1. **协议一致**: 正常校验请求始终包含固定的 `CaptchaType=9`
2. **旧配置不覆盖**: 非"9"配置仅告警，不改变请求参数
3. **不解析旧值**: 非数字的 `captchaType` 不再触发数字格式异常

## 迁移指南

如果你已经在使用包含 `captchaType` 的配置：

### 现有配置（不再控制请求参数）

```properties
spi-captcha-service-tencent-captcha-type=9
```

### 简化后的配置（推荐）

```properties
# 不配置 captchaType；服务仍按协议固定发送 CaptchaType=9
```

建议移除旧配置。值为"9"时保留不告警，其他值（包括非数字）会告警，但请求始终发送9。

## 参考资源

- [腾讯云验证码控制台](https://console.cloud.tencent.com/captcha)
- [腾讯云验证码文档](https://cloud.tencent.com/document/product/1110/36334)
- [腾讯云API密钥管理](https://console.cloud.tencent.com/cam/capi)

---

**最后更新**: 2026-01-14  
**版本**: 1.2.1+
