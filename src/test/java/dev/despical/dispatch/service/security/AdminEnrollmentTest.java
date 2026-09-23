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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.despical.dispatch.config.DispatchProperties;
import dev.despical.dispatch.dto.security.AuthDtos.AdminCreateRequest;
import dev.despical.dispatch.entity.security.*;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.security.*;
import dev.despical.dispatch.security.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
class AdminEnrollmentTest {
    final AdminUserRepository users = mock(AdminUserRepository.class);
    final AuthSessionRepository sessions = mock(AuthSessionRepository.class);
    final LoginChallengeRepository challenges = mock(LoginChallengeRepository.class);
    final PasswordEncoder passwords = mock(PasswordEncoder.class);
    final CryptoService crypto = mock(CryptoService.class);
    final TotpService totp = mock(TotpService.class);
    final JwtService jwt = mock(JwtService.class);
    final DispatchProperties properties =
        new DispatchProperties(
            new DispatchProperties.Security(
                "key", "key", "bootstrap", "prometheus", false, "Lax", 10, 30, 30),
            null,
            null);
    final AuthService service =
        new AuthService(
            users,
            sessions,
            challenges,
            mock(RecoveryCodeRepository.class),
            mock(RefreshTokenGraceRepository.class),
            passwords,
            crypto,
            totp,
            jwt,
            properties,
            mock(SecurityEventService.class));
    AdminUser user;
    LoginChallenge challenge;

    @BeforeEach
    void setup() {
        user = new AdminUser();
        user.setId(42L);
        user.setEmail("new@example.test");
        user.setPasswordHash("hash:temporary-password");
        user.setPasswordChangeRequired(true);
        when(users.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findByEmailForUpdate(user.getEmail())).thenReturn(Optional.of(user));
        when(users.findByIdForUpdate(42L)).thenReturn(Optional.of(user));
        when(users.save(any()))
            .thenAnswer(
                call -> {
                    AdminUser saved = call.getArgument(0);
                    saved.setId(43L);
                    return saved;
                });
        when(passwords.encode(anyString())).thenAnswer(call -> "hash:" + call.getArgument(0));
        when(passwords.matches(anyString(), anyString()))
            .thenAnswer(call -> ("hash:" + call.getArgument(0)).equals(call.getArgument(1)));
        when(totp.newSecret()).thenReturn("secret");
        when(crypto.encrypt("secret")).thenReturn("encrypted");
        when(crypto.decrypt("encrypted")).thenReturn("secret");
        when(challenges.save(any()))
            .thenAnswer(
                call -> {
                    challenge = call.getArgument(0);
                    when(challenges.findByPublicId(challenge.getPublicId()))
                        .thenReturn(Optional.of(challenge));
                    return challenge;
                });
        when(totp.verifyAndResolveStep(anyString(), anyString())).thenReturn(-1L);
        when(totp.verifyAndResolveStep("secret", "123456")).thenReturn(100L);
        when(jwt.createAccessToken(any())).thenReturn("access");
    }

    @Test
    void creatorReceivesNoAuthenticatorSecretOrLoginChallenge() {
        var result =
            service.createAdmin(
                new AdminCreateRequest(
                    "New Admin",
                    "another@example.test",
                    "temporary-password",
                    UserRole.ADMIN),
                "127.0.0.1");
        assertThat(result.totpEnabled()).isFalse();
        var captured = org.mockito.ArgumentCaptor.forClass(AdminUser.class);
        verify(users).save(captured.capture());
        assertThat(captured.getValue().isPasswordChangeRequired()).isTrue();
        assertThat(captured.getValue().getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(captured.getValue().getEncryptedTotpSecret()).isNull();
        verifyNoInteractions(challenges, sessions, totp, crypto);
    }

    @Test
    void regularUserKeepsSelectedRoleAndMustCompleteEnrollment() {
        var result =
            service.createAdmin(
                new AdminCreateRequest(
                    "New User",
                    "another@example.test",
                    "temporary-password",
                    UserRole.USER),
                "127.0.0.1");
        assertThat(result.role()).isEqualTo(UserRole.USER);
        var captured = org.mockito.ArgumentCaptor.forClass(AdminUser.class);
        verify(users).save(captured.capture());
        assertThat(captured.getValue().getRole()).isEqualTo(UserRole.USER);
        assertThat(captured.getValue().isPasswordChangeRequired()).isTrue();
        assertThat(captured.getValue().isTotpEnabled()).isFalse();
        verifyNoInteractions(challenges, sessions, totp, crypto);
    }

    @Test
    void omittedRoleCannotCreateAnAccount() {
        assertThatThrownBy(
            () ->
                service.createAdmin(
                    new AdminCreateRequest(
                        "New User",
                        "another@example.test",
                        "temporary-password",
                        null),
                    "127.0.0.1"))
            .isInstanceOf(ApiException.class);
        verify(users, never()).save(any());
    }

    @Test
    void setupIsOnlyReturnedAfterTheTemporaryPasswordIsVerified() {
        assertThatThrownBy(() -> service.login(user.getEmail(), "wrong", "ip"))
            .isInstanceOf(ApiException.class);
        verifyNoInteractions(totp, challenges, sessions);
        var result = service.login(user.getEmail(), "temporary-password", "ip");
        assertThat(result.setupRequired()).isTrue();
        assertThat(result.passwordChangeRequired()).isTrue();
        assertThat(result.totpSecret()).isEqualTo("secret");
        verifyNoInteractions(sessions);
    }

    @Test
    void missingOrUnchangedPasswordAndWrongTotpCannotCreateASession() {
        var id = service.login(user.getEmail(), "temporary-password", "ip").challengeId();
        for (String password : new String[]{null, "short", "temporary-password"}) {
            assertThatThrownBy(
                () ->
                    service.completeChallenge(
                        id, "123456", "browser", "ip", password))
                .isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(
            () ->
                service.completeChallenge(
                    id, "000000", "browser", "ip", "permanent-password"))
            .isInstanceOf(ApiException.class);
        assertThat(user.isTotpEnabled()).isFalse();
        assertThat(challenge.getConsumedAt()).isNull();
        assertThat(user.getPasswordHash()).isEqualTo("hash:temporary-password");
        verifyNoInteractions(sessions);
    }

    @Test
    void successfulSetupChangesPasswordAndReturnsRecoveryCodesOnlyToTheNewAdmin() {
        UUID id = service.login(user.getEmail(), "temporary-password", "ip").challengeId();
        var result = service.completeChallenge(id, "123456", "browser", "ip", "permanent-password");
        assertThat(result.response().authenticated()).isTrue();
        assertThat(result.response().recoveryCodes()).hasSize(10);
        assertThat(user.isTotpEnabled()).isTrue();
        assertThat(user.isPasswordChangeRequired()).isFalse();
        assertThat(user.getPasswordHash()).isEqualTo("hash:permanent-password");
        verify(sessions).save(any(AuthSession.class));
        assertThatThrownBy(() -> service.login(user.getEmail(), "temporary-password", "ip"))
            .isInstanceOf(ApiException.class);
        assertThat(service.login(user.getEmail(), "permanent-password", "ip").totpSecret())
            .isNull();
    }

    @Test
    void expiredDisabledOrPreviouslyCompletedSetupChallengesCannotBeUsed() {
        UUID id = service.login(user.getEmail(), "temporary-password", "ip").challengeId();
        challenge.setExpiresAt(Instant.now().minusSeconds(1));
        assertThatThrownBy(
            () ->
                service.completeChallenge(
                    id, "123456", "browser", "ip", "permanent-password"))
            .isInstanceOf(ApiException.class);
        challenge.setExpiresAt(Instant.now().plusSeconds(60));
        user.setEnabled(false);
        assertThatThrownBy(
            () ->
                service.completeChallenge(
                    id, "123456", "browser", "ip", "permanent-password"))
            .isInstanceOf(ApiException.class);
        user.setEnabled(true);
        user.setTotpEnabled(true);
        assertThatThrownBy(
            () ->
                service.completeChallenge(
                    id, "123456", "browser", "ip", "permanent-password"))
            .isInstanceOf(ApiException.class);
        verifyNoInteractions(sessions);
    }

    @Test
    void administratorsCannotDisableTheirOwnAccount() {
        assertThatThrownBy(() -> service.disableAdmin(42L, 42L, "127.0.0.1"))
            .isInstanceOf(ApiException.class);
        assertThat(user.isEnabled()).isTrue();
        verifyNoInteractions(sessions);
    }
}
