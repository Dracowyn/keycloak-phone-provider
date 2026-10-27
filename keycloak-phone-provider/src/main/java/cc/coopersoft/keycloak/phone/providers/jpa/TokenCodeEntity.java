package cc.coopersoft.keycloak.phone.providers.jpa;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Getter
@Setter
@RequiredArgsConstructor
@Entity
@Table(name = "PHONE_MESSAGE_TOKEN_CODE")
@NamedQueries({
        @NamedQuery(
                name = "currentProcess",
                query = "SELECT t FROM TokenCodeEntity t WHERE t.realmId = :realmId " +
                        "AND t.areaCode = :areaCode AND t.phoneNumber = :phoneNumber " +
                        "AND t.expiresAt >= :now AND t.type = :type " +
                        "ORDER BY t.createdAt DESC"
        ),
        @NamedQuery(
                name = "getAll",
                query = "SELECT t FROM TokenCodeEntity t WHERE t.realmId = :realmId " +
                        "AND t.areaCode = :areaCode AND t.phoneNumber = :phoneNumber " +
                        "AND t.type = :type"
        ),
        @NamedQuery(
                name = "processesSince",
                query = "SELECT t FROM TokenCodeEntity t WHERE t.realmId = :realmId " +
                        "AND t.areaCode = :areaCode AND t.phoneNumber = :phoneNumber " +
                        "AND t.createdAt >= :date AND t.type = :type"
        )
})
public class TokenCodeEntity {
    @Id
    @Column(name = "ID")
    private String id;

    @Column(name = "REALM_ID", nullable = false)
    private String realmId;

    @Column(name = "AREA_CODE", nullable = false)
    private String areaCode;

    @Column(name = "PHONE_NUMBER", nullable = false)
    private String phoneNumber;

    @Column(name = "TYPE", nullable = false)
    private String type;

    @Column(name = "CODE", nullable = false)
    private String code;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "RESEND_EXPIRES_AT", nullable = false)
    private LocalDateTime resendExpiresAt;

    @Column(name = "EXPIRES_AT", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "CONFIRMED", nullable = false)
    private Boolean confirmed;

    @Column(name = "BY_WHOM")
    private String byWhom;

    /**
     * 验证码校验失败的累计次数，用于防爆破。
     * 达到上限后该验证码记录会被作废，用户须重新发送。
     */
    @Column(name = "ATTEMPTS", nullable = false)
    private Integer attempts = 0;

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null) {
            return false;
        }
        if (!(o instanceof TokenCodeEntity that)) {
            return false;
        }

        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : 0;
    }
}
