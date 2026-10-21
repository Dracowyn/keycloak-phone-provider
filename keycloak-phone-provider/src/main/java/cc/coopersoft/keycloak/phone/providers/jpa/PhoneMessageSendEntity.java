package cc.coopersoft.keycloak.phone.providers.jpa;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** Sending quota metadata only; deliberately independent of consumable OTP rows. */
@Getter
@Setter
@Entity
@Table(name = "PHONE_MESSAGE_SEND")
@NamedQuery(name = "phoneSendsSince", query = "SELECT s FROM PhoneMessageSendEntity s WHERE "
        + "s.realmId = :realmId AND s.areaCode = :areaCode AND s.phoneNumber = :phoneNumber "
        + "AND s.type = :type AND s.createdAt >= :since ORDER BY s.createdAt DESC")
public class PhoneMessageSendEntity {
    @Id @Column(name = "ID", length = 36) private String id;
    @Column(name = "REALM_ID", nullable = false, length = 36) private String realmId;
    @Column(name = "AREA_CODE", nullable = false, length = 4) private String areaCode;
    @Column(name = "PHONE_NUMBER", nullable = false, length = 20) private String phoneNumber;
    @Column(name = "TYPE", nullable = false, length = 20) private String type;
    @Column(name = "CREATED_AT", nullable = false) private LocalDateTime createdAt;
    @Column(name = "RESEND_EXPIRES_AT", nullable = false) private LocalDateTime resendExpiresAt;
}
