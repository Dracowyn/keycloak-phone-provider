package cc.coopersoft.keycloak.phone.providers.jpa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TokenCodeEntityTest {

    @Test
    @DisplayName("空 id 实体可以加入哈希集合且不会把不同临时实体合并")
    void nullIdEntities_remainDistinctInHashSet() {
        TokenCodeEntity first = new TokenCodeEntity();
        TokenCodeEntity second = new TokenCodeEntity();
        Set<TokenCodeEntity> entities = new HashSet<>();

        assertThat(first.hashCode()).isZero();
        assertThat(second.hashCode()).isZero();
        assertThat(entities.add(first)).isTrue();
        assertThat(entities.add(second)).isTrue();
        assertThat(entities).containsExactlyInAnyOrder(first, second);
        assertThat(first.equals(second)).isFalse();
        assertThat(second.equals(first)).isFalse();
        assertThat(first.equals(first)).isTrue();
        assertThat(entities.contains(first)).isTrue();
    }

    @Test
    @DisplayName("相同非空 id 实体相等且哈希一致")
    void matchingIds_areEqualAndDeduplicated() {
        TokenCodeEntity first = new TokenCodeEntity();
        first.setId("token-1");
        TokenCodeEntity second = new TokenCodeEntity();
        second.setId("token-1");
        Set<TokenCodeEntity> entities = new HashSet<>();

        assertThat(first).isEqualTo(second);
        assertThat(second).isEqualTo(first);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(entities.add(first)).isTrue();
        assertThat(entities.add(second)).isFalse();
    }

    @Test
    @DisplayName("不同 id、空 id 和非实体对象均不相等")
    void differentOrMissingIds_areNotEqual() {
        TokenCodeEntity persisted = new TokenCodeEntity();
        persisted.setId("token-1");
        TokenCodeEntity different = new TokenCodeEntity();
        different.setId("token-2");
        TokenCodeEntity transientEntity = new TokenCodeEntity();

        assertThat(persisted).isNotEqualTo(different);
        assertThat(persisted).isNotEqualTo(transientEntity);
        assertThat(transientEntity).isNotEqualTo(persisted);
        assertThat(transientEntity).isNotEqualTo(null);
        assertThat(transientEntity).isNotEqualTo("token-1");
    }
}
