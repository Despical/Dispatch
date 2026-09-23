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
package dev.despical.dispatch.service.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.despical.dispatch.security.CryptoService;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;

/**
 * @author Despical
 * <p>
 * Created at 23.09.2026
 */
class GoogleOAuthServiceTest {
    @Test
    void generatesAnOfflinePkceAuthorizationForTheAuthenticatedOwner() {
        var jdbc = mock(JdbcTemplate.class);
        var crypto = mock(CryptoService.class);
        when(crypto.encrypt(anyString())).thenReturn("encrypted-verifier");
        var service =
            new GoogleOAuthService(
                "client-id",
                "client-secret",
                "https://dispatch.example.test/oauth/google/callback",
                jdbc,
                crypto,
                new ObjectMapper(),
                mock(MailAccountService.class));

        String authorization = service.start(42L);

        assertThat(URI.create(authorization).getHost()).isEqualTo("accounts.google.com");
        assertThat(authorization)
            .contains(
                "access_type=offline",
                "code_challenge_method=S256",
                "scope=https%3A%2F%2Fmail.google.com%2F",
                "state=");
        verify(jdbc)
            .update(
                startsWith("INSERT INTO google_oauth_flows"),
                any(),
                eq(42L),
                eq("encrypted-verifier"),
                any());
    }
}
