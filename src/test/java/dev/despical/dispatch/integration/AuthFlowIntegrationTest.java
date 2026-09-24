/*
 * Dispatch - A private webmail application.
 * Copyright (C) 2026 Berke Akçen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.despical.dispatch.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.despical.dispatch.entity.security.AuthSession;
import dev.despical.dispatch.repository.security.AuthSessionRepository;

import jakarta.servlet.http.Cookie;

import org.apache.commons.codec.binary.Base32;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIntegrationTest extends PostgresIntegrationTestSupport {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    AuthSessionRepository sessions;

    @Test
    void acceptsTokenReturnedByCsrfEndpointForJsonRequests() throws Exception {
        var csrfResponse =
            mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        String token = body(csrfResponse.getContentAsString()).get("token").asText();
        Cookie cookie = csrfResponse.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(token).isEqualTo(cookie.getValue());

        mvc.perform(
                post("/api/auth/login")
                    .cookie(cookie)
                    .header("X-XSRF-TOKEN", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                            {"email":"missing@example.test","password":"definitely-not-the-password"}
                            """))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void blocksTotpReplayRotatesRefreshAndKeepsAbsoluteDeadline() throws Exception {
        JsonNode bootstrap =
            body(
                mvc.perform(
                        post("/api/auth/bootstrap")
                            .with(csrf())
                            .cookie(
                                new Cookie(
                                    "DISPATCH_BOOTSTRAP",
                                    "test-bootstrap-token-with-enough-entropy"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                """
                                    {"displayName":"Test Administrator","email":"admin@example.test","password":"a-very-long-test-password"}
                                    """))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
        String secret = bootstrap.get("totpSecret").asText();
        String challenge = bootstrap.get("challengeId").asText();
        String code = totp(secret, Instant.now().getEpochSecond() / 30);

        var verified =
            mvc.perform(
                    post("/api/auth/verify")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            "{\"challengeId\":\""
                                + challenge
                                + "\",\"code\":\""
                                + code
                                + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        Cookie access = verified.getCookie("DISPATCH_ACCESS");
        Cookie refresh = verified.getCookie("DISPATCH_REFRESH");
        String recoveryCode =
            body(verified.getContentAsString()).get("recoveryCodes").get(0).asText();
        assertThat(access).isNotNull();
        assertThat(refresh).isNotNull();
        mvc.perform(get("/api/auth/me").cookie(access)).andExpect(status().isOk());
        mvc.perform(get("/mail").cookie(access)).andExpect(status().isOk());
        UUID sessionId =
            UUID.fromString(refresh.getValue().substring(0, refresh.getValue().indexOf('.')));
        AuthSession stored = sessions.findByPublicId(sessionId).orElseThrow();
        Instant absoluteDeadline = stored.getAbsoluteExpiresAt();

        var rotated =
            mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(refresh))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie("DISPATCH_REFRESH");
        assertThat(rotated).isNotNull();
        assertThat(rotated.getValue()).isNotEqualTo(refresh.getValue());
        assertThat(sessions.findByPublicId(sessionId).orElseThrow().getAbsoluteExpiresAt())
            .isEqualTo(absoluteDeadline);

        JsonNode nextLogin =
            body(
                mvc.perform(
                        post("/api/auth/login")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                "{\"email\":\"admin@example.test\",\"password\":\"a-very-long-test-password\"}"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
        mvc.perform(
                post("/api/auth/verify")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"challengeId\":\""
                            + nextLogin.get("challengeId").asText()
                            + "\",\"code\":\""
                            + code
                            + "\"}"))
            .andExpect(status().isUnauthorized());

        stored = sessions.findByPublicId(sessionId).orElseThrow();
        stored.setAbsoluteExpiresAt(Instant.now().minusSeconds(1));
        sessions.saveAndFlush(stored);
        mvc.perform(post("/api/auth/refresh").with(csrf()).cookie(rotated))
            .andExpect(status().isUnauthorized());

        JsonNode recoveryLogin =
            body(
                mvc.perform(
                        post("/api/auth/login")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                "{\"email\":\"admin@example.test\",\"password\":\"a-very-long-test-password\"}"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
        String recoveryChallenge = recoveryLogin.get("challengeId").asText();
        JsonNode reset =
            body(
                mvc.perform(
                        post("/api/auth/recover-authenticator")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                "{\"challengeId\":\""
                                    + recoveryChallenge
                                    + "\",\"recoveryCode\":\""
                                    + recoveryCode
                                    + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
        assertThat(reset.get("setupRequired").asBoolean()).isTrue();
        String replacementCode =
            totp(reset.get("totpSecret").asText(), Instant.now().getEpochSecond() / 30);
        JsonNode recovered =
            body(
                mvc.perform(
                        post("/api/auth/verify")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                "{\"challengeId\":\""
                                    + recoveryChallenge
                                    + "\",\"code\":\""
                                    + replacementCode
                                    + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString());
        assertThat(recovered.get("recoveryCodes").size()).isEqualTo(10);
        assertThat(recovered.get("recoveryCodes").get(0).asText()).isNotEqualTo(recoveryCode);
    }

    private JsonNode body(String value) throws Exception {
        return json.readTree(value);
    }

    private String totp(String secret, long step) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(new Base32().decode(secret), "HmacSHA1"));
        byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
        int offset = hash[hash.length - 1] & 15;
        int binary =
            ((hash[offset] & 127) << 24)
                | ((hash[offset + 1] & 255) << 16)
                | ((hash[offset + 2] & 255) << 8)
                | (hash[offset + 3] & 255);
        return "%06d".formatted(binary % 1_000_000);
    }
}
