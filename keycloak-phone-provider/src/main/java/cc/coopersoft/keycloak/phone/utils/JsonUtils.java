package cc.coopersoft.keycloak.phone.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * JSON工具类
 */
public class JsonUtils {
    private static JsonUtils instance;

    public ObjectMapper mapper;

    /**
     * 获取JSON工具类单例实例
     *
     * @return JSON工具类单例实例
     */
    public synchronized JsonUtils getInstance() {
        if (instance == null) {
            instance = new JsonUtils();
        }
        return instance;
    }

    /**
     * JSON工具类构造方法
     */
    public JsonUtils() {
        mapper = new ObjectMapper();
    }

    /**
     * 将Map对象转换为JSON字符串
     *
     * @param map 要转换为JSON的Map对象
     * @return 转换后的JSON字符串
     * @throws JsonProcessingException JSON转换过程异常时抛出
     */
    public String encode(Map<String, Object> map) throws JsonProcessingException {
        return mapper.writeValueAsString(map);
    }

    /**
     * 将JSON字符串转换为Map对象
     */
    public Map<String, Object> decode(String json) throws JsonProcessingException {
        return mapper.readValue(json, new TypeReference<>() {
        });
    }
}
