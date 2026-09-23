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
import static org.mockito.Mockito.*;

import dev.despical.dispatch.config.DispatchProperties;
import dev.despical.dispatch.entity.security.AdminUser;
import dev.despical.dispatch.entity.security.AuthSession;
import dev.despical.dispatch.entity.security.LoginChallenge;
import dev.despical.dispatch.entity.security.RecoveryCode;
import dev.despical.dispatch.repository.security.*;
import dev.despical.dispatch.security.CryptoService;
import dev.despical.dispatch.security.JwtService;
import dev.despical.dispatch.security.TotpService;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

/**
 * @author Despical
 * <p>
 * Created at 23.09.2026
 */
class PasswordRecoveryTest {
    @Test
    void requiresAuthenticatorAndRecoveryCodeThenRevokesExistingAccess() {
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
        var passwords = mock(PasswordEncoder.class);
        var service =
            new AuthService(
                users,
                sessions,
                challenges,
                recoveryCodes,
                mock(RefreshTokenGraceRepository.class),
                passwords,
                crypto,
                totp,
                mock(JwtService.class),
                properties,
                mock(SecurityEventService.class));
        var user = new AdminUser();
        user.setId(1L);
        user.setEmail("alex@example.test");
        user.setEnabled(true);
        user.setTotpEnabled(true);
        user.setEncryptedTotpSecret(crypto.encrypt("SECRET"));
        user.setPasswordHash("old-hash");
        var code = new RecoveryCode();
        code.setCodeHash(CryptoService.sha256("AAAABBBBCCCCDDDD"));
        var session = new AuthSession();
        var challenge = new LoginChallenge();
        when(users.findByEmailForUpdate("alex@example.test")).thenReturn(Optional.of(user));
        when(totp.verifyAndResolveStep("SECRET", "123456")).thenReturn(123L);
        when(recoveryCodes.findAllByAdminUserIdAndUsedAtIsNull(1L)).thenReturn(List.of(code));
        when(passwords.encode("new-secure-password")).thenReturn("new-hash");
        when(sessions.findAllByAdminUserIdAndRevokedAtIsNull(1L)).thenReturn(List.of(session));
        when(challenges.findAllByAdminUserIdAndConsumedAtIsNull(1L)).thenReturn(List.of(challenge));

        service.recoverPassword(
            "alex@example.test",
            "123456",
            "AAAA-BBBB-CCCC-DDDD",
            "new-secure-password",
            "127.0.0.1");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getLastAcceptedTotpStep()).isEqualTo(123L);
        assertThat(code.getUsedAt()).isNotNull();
        assertThat(session.getRevokedAt()).isNotNull();
        assertThat(challenge.getConsumedAt()).isNotNull();
    }
}
