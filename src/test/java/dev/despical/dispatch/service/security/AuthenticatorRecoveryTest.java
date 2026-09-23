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
package dev.despical.dispatch.service.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.despical.dispatch.config.DispatchProperties;
import dev.despical.dispatch.entity.security.AdminUser;
import dev.despical.dispatch.entity.security.AuthSession;
import dev.despical.dispatch.entity.security.LoginChallenge;
import dev.despical.dispatch.entity.security.RecoveryCode;
import dev.despical.dispatch.repository.security.AdminUserRepository;
import dev.despical.dispatch.repository.security.AuthSessionRepository;
import dev.despical.dispatch.repository.security.LoginChallengeRepository;
import dev.despical.dispatch.repository.security.RecoveryCodeRepository;
import dev.despical.dispatch.repository.security.RefreshTokenGraceRepository;
import dev.despical.dispatch.security.CryptoService;
import dev.despical.dispatch.security.JwtService;
import dev.despical.dispatch.security.TotpService;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 23.09.2026
 */
class AuthenticatorRecoveryTest {

    @Test
    void replacesAuthenticatorOnlyAfterTheNewCodeAndRevokesPreviousSessions() {
        var properties =
            new DispatchProperties(
                new DispatchProperties.Security(
                    "unused",
                    "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=",
                    "unused",
                    "unused",
                    false,
                    "Strict",
                    15,
                    30,
                    15),
                null,
                null);
        var crypto = new CryptoService(properties);
        var users = mock(AdminUserRepository.class);
        var sessions = mock(AuthSessionRepository.class);
        var challenges = mock(LoginChallengeRepository.class);
        var recoveryCodes = mock(RecoveryCodeRepository.class);
        var totp = mock(TotpService.class);
        var jwt = mock(JwtService.class);
        var service =
            new AuthService(
                users,
                sessions,
                challenges,
                recoveryCodes,
                mock(RefreshTokenGraceRepository.class),
                mock(PasswordEncoder.class),
                crypto,
                totp,
                jwt,
                properties,
                mock(SecurityEventService.class));

        var user = new AdminUser();
        user.setId(1L);
        user.setEmail("alex@example.test");
        user.setEnabled(true);
        user.setTotpEnabled(true);
        String oldSecret = crypto.encrypt("OLDSECRET");
        user.setEncryptedTotpSecret(oldSecret);
        var challenge = new LoginChallenge();
        var id = UUID.randomUUID();
        challenge.setPublicId(id);
        challenge.setAdminUser(user);
        challenge.setExpiresAt(Instant.now().plusSeconds(300));
        var code = new RecoveryCode();
        code.setCodeHash(CryptoService.sha256("AAAABBBBCCCCDDDD"));
        var previousSession = new AuthSession();
        when(challenges.findByPublicId(id)).thenReturn(Optional.of(challenge));
        when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(recoveryCodes.findAllByAdminUserIdAndUsedAtIsNull(1L)).thenReturn(List.of(code));
        when(totp.newSecret()).thenReturn("NEWSECRET");
        when(totp.verifyAndResolveStep("NEWSECRET", "123456")).thenReturn(123L);
        when(sessions.findAllByAdminUserIdAndRevokedAtIsNull(1L))
            .thenReturn(List.of(previousSession));
        when(jwt.createAccessToken(any())).thenReturn("access-token");

        var setup = service.recoverAuthenticator(id, "AAAA-BBBB-CCCC-DDDD", "127.0.0.1");
        assertThat(setup.setupRequired()).isTrue();
        assertThat(setup.totpSecret()).isEqualTo("NEWSECRET");
        assertThat(code.getUsedAt()).isNotNull();
        assertThat(user.getEncryptedTotpSecret()).isEqualTo(oldSecret);
        assertThat(previousSession.getRevokedAt()).isNull();

        var result = service.completeChallenge(id, "123456", "test-agent", "127.0.0.1", null);
        assertThat(result.response().authenticated()).isTrue();
        assertThat(result.response().recoveryCodes()).hasSize(10);
        assertThat(crypto.decrypt(user.getEncryptedTotpSecret())).isEqualTo("NEWSECRET");
        assertThat(previousSession.getRevokedAt()).isNotNull();
        assertThat(challenge.getConsumedAt()).isNotNull();
        verify(recoveryCodes).deleteAllByAdminUserId(1L);
    }
}
