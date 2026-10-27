package cc.coopersoft.keycloak.phone.providers.sender;

import cc.coopersoft.keycloak.phone.providers.constants.MessageSendResult;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.spi.MessageSenderService;
import cc.coopersoft.keycloak.phone.utils.PhoneNumber;
import com.aliyuncs.CommonRequest;
import com.aliyuncs.CommonResponse;
import com.aliyuncs.DefaultAcsClient;
import com.aliyuncs.IAcsClient;
import com.aliyuncs.exceptions.ClientException;
import com.aliyuncs.http.MethodType;
import com.aliyuncs.profile.DefaultProfile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.RealmModel;

public class AliyunSmsSenderServiceProviderImpl implements MessageSenderService {
    private static final Logger logger = Logger.getLogger(AliyunSmsSenderServiceProviderImpl.class);
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final Config.Scope config;
    private final RealmModel realm;
    private final IAcsClient client;

    public AliyunSmsSenderServiceProviderImpl(Config.Scope config, RealmModel realm) {
        this.config = config;
        this.realm = realm;
        DefaultProfile profile = DefaultProfile.getProfile("cn-hangzhou", config.get("accessKeyId"), config.get("accessSecret"));
        client = new DefaultAcsClient(profile);
    }

    private String getConfig(String realm, String type, String key) {
        realm = realm.toUpperCase();
        type = type.toUpperCase();
        key = key.toUpperCase();
        String value = config.get(realm + "_" + type + "_" + key);
        if (value == null) {
            value = config.get(type + "_" + key);
            if (value == null) {
                value = config.get("DEFAULT_" + key);
            }
        }
        return value;
    }

    @Override
    public MessageSendResult sendSmsMessage(TokenCodeType type, PhoneNumber phoneNumber, String code, int expires) {
        String templateId = this.getConfig(realm.getName(), type.name(), "template");
        String signName = this.getConfig(realm.getName(), type.name(), "signName");

        CommonRequest request = new CommonRequest();
        request.setSysMethod(MethodType.POST);
        request.setSysDomain("dysmsapi.aliyuncs.com");
        request.setSysVersion("2017-05-25");
        request.setSysAction("SendSms");
        request.putQueryParameter("RegionId", "cn-hangzhou");
        request.putQueryParameter("PhoneNumbers", formatPhoneNumber(phoneNumber));
        request.putQueryParameter("SignName", signName);
        request.putQueryParameter("TemplateCode", templateId);

        request.putQueryParameter("TemplateParam", String.format("{\"code\":\"%s\"}", code));
        try {
            CommonResponse response = client.getCommonResponse(request);
            return parseResponse(response.getData(), expires);
        } catch (ClientException e) {
            logger.error(e.getMessage());
            return new MessageSendResult(-1).setError(e.getErrCode(), e.getErrMsg());
        }
    }

    static String formatPhoneNumber(PhoneNumber phoneNumber) {
        // 国内号码不带区号，国际号码拼接区号且不添加 + 或 00 前缀。
        return "86".equals(phoneNumber.getAreaCode()) ? phoneNumber.getPhoneNumber()
                : phoneNumber.getAreaCode() + phoneNumber.getPhoneNumber();
    }

    static MessageSendResult parseResponse(String data, int expires) {
        // HTTP 请求成功不代表短信发送成功，必须检查阿里云业务码。
        if (data == null || data.isBlank()) {
            return responseParseError("阿里云响应为空");
        }
        try {
            JsonNode response = JSON.readTree(data);
            if (response == null || !response.isObject() || !response.path("Code").isTextual()) {
                return responseParseError("阿里云响应缺少字符串 Code");
            }
            String code = response.get("Code").textValue();
            if ("OK".equals(code)) {
                return new MessageSendResult(1).setResendExpires(60).setExpires(expires);
            }
            String message = response.path("Message").asText(null);
            logger.warnf("阿里云短信发送失败，Code=%s，Message=%s", code, message);
            return new MessageSendResult(-1).setError(code, message);
        } catch (JsonProcessingException e) {
            return responseParseError("阿里云响应 JSON 无效：" + e.getOriginalMessage());
        }
    }

    private static MessageSendResult responseParseError(String message) {
        logger.error(message);
        return new MessageSendResult(-1).setError("ALIYUN_RESPONSE_PARSE_ERROR", message);
    }

    @Override
    public void close() {

    }
}
