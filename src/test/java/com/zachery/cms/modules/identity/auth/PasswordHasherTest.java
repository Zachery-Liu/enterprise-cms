package com.zachery.cms.modules.identity.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachery.cms.modules.identity.auth.dto.RegisterRequest;
import com.zachery.cms.modules.identity.auth.service.PasswordHasher;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PasswordHasherTest {
    private final PasswordHasher hasher = new PasswordHasher();
    // Independently derived with Node crypto.pbkdf2Sync, SHA-256, 600000 iterations.
    private static final String FIXTURE = "{pbkdf2-sha256}600000$AAECAwQFBgcICQoLDA0ODw==$wpcF9hlr08mvG4XuvsbKoBoWwAQhRgzErA9WLtQ1Tx8=";

    @Test
    void verifiesIndependentFixtureUsingTheSeedFormat() {
        assertThat(hasher.matches("FixturePass123!", FIXTURE)).isTrue();
        assertThat(hasher.matches("wrong-password", FIXTURE)).isFalse();
    }

    @Test
    void saltsAreRandomAndPasswordWhitespaceIsPreserved() {
        String password = "  password with spaces  ";
        String first = hasher.encode(password), second = hasher.encode(password);
        assertThat(first).isNotEqualTo(second).doesNotContain(password);
        String[] parts = first.substring("{pbkdf2-sha256}".length()).split("\\$");
        assertThat(parts[0]).isEqualTo("600000");
        assertThat(Base64.getDecoder().decode(parts[1])).hasSize(16);
        assertThat(Base64.getDecoder().decode(parts[2])).hasSize(32);
        assertThat(hasher.matches(password, first)).isTrue();
        assertThat(hasher.matches(password.strip(), first)).isFalse();
    }

    @Test
    void malformedHashesAndUnboundedWorkFactorsAreRejected() {
        for (String value : List.of("", "plaintext", FIXTURE.replace("600000", "999999999"),
                FIXTURE + "$extra", "{pbkdf2-sha256}600000$bad!$bad", "{pbkdf2-sha256}600000$AA==$AA=="))
            assertThat(hasher.matches("FixturePass123!", value)).isFalse();
        assertThat(hasher.matches(null, FIXTURE)).isFalse();
        assertThat(hasher.matches("password", null)).isFalse();
        assertThat(hasher.matches("x".repeat(129), FIXTURE)).isFalse();
    }

    @Test
    void requestToStringAndSerializationDoNotExposePasswords() throws Exception {
        RegisterRequest request = new RegisterRequest(" User.Name ", "DoNotPrint123!", null);
        assertThat(request.username()).isEqualTo("user.name");
        assertThat(request.toString()).doesNotContain(request.password());
        assertThat(new ObjectMapper().writeValueAsString(request)).doesNotContain("password", request.password());
    }
}
