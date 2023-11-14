package cc.coopersoft.keycloak.phone.providers.exception;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 短信发送异常类
 * 用于在短信发送失败时抛出
 *
 * @author cooper
 * @since 2020/10/29
 */
@EqualsAndHashCode(callSuper = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MessageSendException extends Exception {

    private Integer statusCode = -1;
    private String errorCode = "";
    private String errorMessage = "";
}
