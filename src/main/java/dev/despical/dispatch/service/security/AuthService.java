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

import dev.despical.dispatch.config.DispatchProperties;
import dev.despical.dispatch.dto.security.AuthDtos;
import dev.despical.dispatch.dto.security.AuthDtos.*;
import dev.despical.dispatch.entity.security.*;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.security.*;
import dev.despical.dispatch.security.CryptoService;
import dev.despical.dispatch.security.JwtService;
import dev.despical.dispatch.security.TotpService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AdminUserRepository users;
    private final AuthSessionRepository sessions;
    private final LoginChallengeRepository challenges;
    private final RecoveryCodeRepository recoveryCodes;
    private final RefreshTokenGraceRepository refreshGrace;
    private final PasswordEncoder passwordEncoder;
    private final CryptoService crypto;
    private final TotpService totp;
    private final JwtService jwt;
    private final DispatchProperties properties;
    private final SecurityEventService events;
    private final SecureRandom random = new SecureRandom();

    public boolean bootstrapAvailable() {
        return users.count() == 0;
    }

    @Transactional(readOnly = true)
    public List<dev.despical.dispatch.dto.security.AuthDtos.AdminView> listAdmins() {
        return users.findAll()
            .stream()
            .map(user
                -> new dev.despical.dispatch.dto.security.AuthDtos.AdminView(
                user.getId(), user.getDisplayName(), user.getEmail(), user.isEnabled(),
                user.isTotpEnabled(), user.getRole()))
            .toList();
    }

    @Transactional
    public void disableAdmin(Long targetId, Long actingId, String ip) {
        if (targetId.equals(actingId)) {
            throw new ApiException(HttpStatus.CONFLICT, "You cannot disable your own administrator account.");
        }

        AdminUser user = users.findByIdForUpdate(targetId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Administrator not found."));
        user.setEnabled(false);

        Instant now = Instant.now();
        sessions.findAllByAdminUserIdAndRevokedAtIsNull(targetId).forEach(session -> session.setRevokedAt(now));

        events.record(targetId, "ADMIN_DISABLED", "SUCCESS", ip, "Disabled by administrator " + actingId);
    }

    @Transactional
    public BootstrapResponse bootstrap(BootstrapRequest request, String bootstrapToken, String ip) {
        if (users.count() != 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Bootstrap is no longer available.");
        }

        if (!CryptoService.constantTimeEquals(properties.security().bootstrapToken(), bootstrapToken)) {
            events.record(null, "BOOTSTRAP", "DENIED", ip, "Invalid bootstrap token");

            throw new ApiException(HttpStatus.UNAUTHORIZED, "Bootstrap token is invalid.");
        }

        return provisionAdmin(request.displayName(), request.email(), request.password(), ip);
    }

    @Transactional
    public AuthDtos.AdminView createAdmin(AdminCreateRequest request, String ip) {
        if (request.role() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a user role.");
        }

        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (users.findByEmailIgnoreCase(email).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "An administrator with that email already exists.");
        }

        AdminUser user = new AdminUser();
        user.setDisplayName(normalizeDisplayName(request.displayName()));
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setPasswordChangedAt(Instant.now());
        user.setPasswordChangeRequired(true);
        user.setRole(request.role());
        users.save(user);

        events.record(user.getId(), request.role().name() + "_CREATED", "SUCCESS", ip, "First sign-in setup pending");

        return new AuthDtos.AdminView(
            user.getId(), user.getDisplayName(), user.getEmail(), user.isEnabled(), false,
            user.getRole());
    }

    private BootstrapResponse provisionAdmin(String displayName, String email, String password, String ip) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (users.findByEmailIgnoreCase(normalized).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "An administrator with that email already exists.");
        }

        AdminUser user = new AdminUser();
        user.setRole(dev.despical.dispatch.entity.security.UserRole.ADMIN);
        user.setDisplayName(normalizeDisplayName(displayName));
        user.setEmail(normalized);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setPasswordChangedAt(Instant.now());

        String secret = totp.newSecret();
        user.setEncryptedTotpSecret(crypto.encrypt(secret));
        users.save(user);

        LoginChallenge challenge = newChallenge(user);
        events.record(user.getId(), "BOOTSTRAP", "SUCCESS", ip, "TOTP enrollment pending");
        return new BootstrapResponse(challenge.getPublicId(), secret, otpAuthUri(normalized, secret));
    }

    @Transactional(readOnly = true)
    public ProfileView profile(Long adminId) {
        AdminUser user = users.findById(adminId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Administrator not found."));
        return new ProfileView(user.getId(), user.getDisplayName(), user.getEmail(), user.getRole());
    }

    @Transactional
    public ProfileView updateProfile(Long adminId, ProfileUpdateRequest request, String ip) {
        AdminUser user = users.findByIdForUpdate(adminId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Administrator not found."));
        user.setDisplayName(normalizeDisplayName(request.displayName()));

        events.record(adminId, "PROFILE_UPDATED", "SUCCESS", ip, "Display name changed");
        return new ProfileView(user.getId(), user.getDisplayName(), user.getEmail(), user.getRole());
    }

    @Transactional
    public ChallengeResponse login(String email, String password, String ip) {
        AdminUser user = users.findByEmailForUpdate(email.trim()).orElse(null);

        if (user == null || !user.isEnabled() || !passwordEncoder.matches(password, user.getPasswordHash())) {
            events.record(user == null ? null : user.getId(), "LOGIN_PASSWORD", "DENIED", ip, "Invalid credentials");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Email or password is incorrect.");
        }

        LoginChallenge challenge = newChallenge(user);
        String setupSecret = null;

        if (!user.isTotpEnabled()) {
            if (user.getEncryptedTotpSecret() == null) {
                user.setEncryptedTotpSecret(crypto.encrypt(totp.newSecret()));
            }

            setupSecret = crypto.decrypt(user.getEncryptedTotpSecret());
        }

        events.record(user.getId(), "LOGIN_PASSWORD", "SUCCESS", ip, "Second factor required");
        return new ChallengeResponse(challenge.getPublicId(), !user.isTotpEnabled(), setupSecret,
            setupSecret == null ? null : otpAuthUri(user.getEmail(), setupSecret),
            user.isPasswordChangeRequired());
    }

    private LoginChallenge newChallenge(AdminUser user) {
        LoginChallenge challenge = new LoginChallenge();
        challenge.setPublicId(UUID.randomUUID());
        challenge.setAdminUser(user);
        challenge.setSetupRequired(!user.isTotpEnabled());
        challenge.setExpiresAt(Instant.now().plus(Duration.ofMinutes(5)));
        return challenges.save(challenge);
    }

    @Transactional
    public ChallengeResponse recoverAuthenticator(
        UUID challengeId,
        String recoveryCode,
        String ip
    ) {
        LoginChallenge challenge = challenges.findByPublicId(challengeId)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Sign in again to continue."));

        Instant now = Instant.now();
        if (challenge.getConsumedAt() != null || !now.isBefore(challenge.getExpiresAt())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Login challenge has expired.");
        }

        AdminUser user = users.findByIdForUpdate(challenge.getAdminUser().getId()).orElseThrow();
        if (!user.isEnabled() || !user.isTotpEnabled() || challenge.isSetupRequired() ||
            challenge.getEncryptedPendingTotpSecret() != null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Sign in again to continue.");
        }

        if (!consumeRecoveryCode(user.getId(), recoveryCode)) {
            events.record(user.getId(), "AUTHENTICATOR_RECOVERY", "DENIED", ip, "Invalid recovery code");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Recovery code is invalid.");
        }

        String secret = totp.newSecret();
        challenge.setEncryptedPendingTotpSecret(crypto.encrypt(secret));
        challenge.setSetupRequired(true);
        challenge.setExpiresAt(now.plus(Duration.ofMinutes(10)));

        events.record(user.getId(), "AUTHENTICATOR_RECOVERY", "PENDING", ip, "Recovery code accepted; enrollment pending");
        return new ChallengeResponse(challenge.getPublicId(), true, secret, otpAuthUri(user.getEmail(), secret), user.isPasswordChangeRequired());
    }

    @Transactional
    public void recoverPassword(
        String email,
        String authenticatorCode,
        String recoveryCode,
        String newPassword,
        String ip
    ) {
        AdminUser user = users.findByEmailForUpdate(email).orElse(null);
        if (user == null || !user.isEnabled() || !user.isTotpEnabled() ||
            user.getEncryptedTotpSecret() == null) {

            throw new ApiException(HttpStatus.UNAUTHORIZED, "Recovery details could not be verified.");
        }

        if (passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Choose a different password.");
        }

        long step = totp.verifyAndResolveStep(crypto.decrypt(user.getEncryptedTotpSecret()), authenticatorCode);

        if (step < 0 || user.getLastAcceptedTotpStep() != null && step <= user.getLastAcceptedTotpStep() ||
            !consumeRecoveryCode(user.getId(), recoveryCode)
        ) {
            events.record(user.getId(), "PASSWORD_RECOVERY", "DENIED", ip, "Invalid recovery details");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Recovery details could not be verified.");
        }

        Instant now = Instant.now();
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(now);
        user.setPasswordChangeRequired(false);
        user.setLastAcceptedTotpStep(step);

        sessions.findAllByAdminUserIdAndRevokedAtIsNull(user.getId()).forEach(session -> session.setRevokedAt(now));
        challenges.findAllByAdminUserIdAndConsumedAtIsNull(user.getId()).forEach(challenge -> challenge.setConsumedAt(now));
        events.record(user.getId(), "PASSWORD_RECOVERY", "SUCCESS", ip, "Password reset; active sessions revoked");
    }

    @Transactional
    public AuthenticationResult completeChallenge(
        UUID challengeId,
        String code,
        String userAgent,
        String ip, String newPassword
    ) {
        LoginChallenge challenge = challenges.findByPublicId(challengeId)
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Login challenge is invalid."));
        Instant now = Instant.now();

        if (challenge.getConsumedAt() != null || !now.isBefore(challenge.getExpiresAt())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Login challenge has expired.");
        }

        AdminUser user = users.findByIdForUpdate(challenge.getAdminUser().getId()).orElseThrow();
        boolean resettingAuthenticator = challenge.getEncryptedPendingTotpSecret() != null;

        if (!user.isEnabled() || user.getEncryptedTotpSecret() == null ||
            (resettingAuthenticator ? (!challenge.isSetupRequired() || !user.isTotpEnabled())
                : challenge.isSetupRequired() == user.isTotpEnabled())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Sign in again to continue.");
        }

        if (user.isPasswordChangeRequired() &&
            (newPassword == null || newPassword.isBlank() || newPassword.length() < 14 ||
                newPassword.length() > 200 ||
                passwordEncoder.matches(newPassword, user.getPasswordHash()))) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Choose a new password of at least 14 characters, different from your temporary"
                    + " password.");
        }

        boolean recoveryUsed = false;
        String verificationSecret = resettingAuthenticator
            ? crypto.decrypt(challenge.getEncryptedPendingTotpSecret())
            : crypto.decrypt(user.getEncryptedTotpSecret());

        long step = totp.verifyAndResolveStep(verificationSecret, code);
        if (step < 0 && user.isTotpEnabled() && !resettingAuthenticator) {
            recoveryUsed = consumeRecoveryCode(user.getId(), code);
        }

        if (step < 0 && !recoveryUsed) {
            events.record(user.getId(), "LOGIN_2FA", "DENIED", ip, "Invalid factor");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Verification code is invalid.");
        }

        if (step >= 0 && !resettingAuthenticator && user.getLastAcceptedTotpStep() != null && step <= user.getLastAcceptedTotpStep()) {
            events.record(user.getId(), "LOGIN_2FA", "DENIED", ip, "TOTP replay blocked");
            throw new ApiException(
                HttpStatus.UNAUTHORIZED,
                "This authenticator code was already used. Wait for a new code and try again.");
        }

        if (step >= 0) {
            user.setLastAcceptedTotpStep(step);
        }

        boolean firstEnrollment = !user.isTotpEnabled();
        if (user.isPasswordChangeRequired()) {
            user.setPasswordHash(passwordEncoder.encode(newPassword));
            user.setPasswordChangedAt(now);
            user.setPasswordChangeRequired(false);
        }

        if (resettingAuthenticator) {
            user.setEncryptedTotpSecret(challenge.getEncryptedPendingTotpSecret());
            challenge.setEncryptedPendingTotpSecret(null);

            sessions.findAllByAdminUserIdAndRevokedAtIsNull(user.getId())
                .forEach(session -> session.setRevokedAt(now));
            events.record(user.getId(), "AUTHENTICATOR_RECOVERY", "SUCCESS", ip,
                "Authenticator replaced; previous sessions revoked");
        }

        user.setTotpEnabled(true);
        user.setLastLoginAt(now);
        challenge.setConsumedAt(now);

        List<String> newRecoveryCodes = firstEnrollment || resettingAuthenticator ? replaceRecoveryCodes(user) : List.of();
        AuthenticationResult result = newSession(user, userAgent, ip);

        events.record(user.getId(), "LOGIN_2FA", "SUCCESS", ip, recoveryUsed ? "Recovery code" : "TOTP");
        return new AuthenticationResult(result.session(), result.accessToken(),
            result.refreshToken(),
            new CompleteAuthResponse(true, newRecoveryCodes));
    }

    private boolean consumeRecoveryCode(Long userId, String supplied) {
        String hash = CryptoService.sha256(normalizeRecoveryCode(supplied));

        for (RecoveryCode candidate : recoveryCodes.findAllByAdminUserIdAndUsedAtIsNull(userId)) {
            if (CryptoService.constantTimeEquals(candidate.getCodeHash(), hash)) {
                candidate.setUsedAt(Instant.now());
                return true;
            }
        }

        return false;
    }

    private List<String> replaceRecoveryCodes(AdminUser user) {
        recoveryCodes.deleteAllByAdminUserId(user.getId());
        List<String> plain = new ArrayList<>();

        for (int index = 0; index < 10; index++) {
            byte[] bytes = new byte[8];
            random.nextBytes(bytes);

            String code = HexFormat.of().formatHex(bytes).toUpperCase(Locale.ROOT);
            code = code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8, 12) + "-" + code.substring(12);

            RecoveryCode stored = new RecoveryCode();
            stored.setAdminUser(user);
            stored.setCodeHash(CryptoService.sha256(normalizeRecoveryCode(code)));

            recoveryCodes.save(stored);
            plain.add(code);
        }

        return plain;
    }

    @Transactional
    public AuthenticationResult refresh(String token, String userAgent, String ip) {
        RefreshParts parts = parseRefresh(token);
        AuthSession session = sessions.findLockedByPublicId(parts.sessionId())
            .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Session is invalid."));
        Instant now = Instant.now();

        if (!session.isActiveAt(now) || !session.getAdminUser().isEnabled() ||
            !session.getAdminUser().isTotpEnabled() ||
            session.getAdminUser().isPasswordChangeRequired()
        ) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "The 30-day session has expired. Sign in again.");
        }

        String suppliedHash = CryptoService.sha256(parts.secret());
        boolean current =
            CryptoService.constantTimeEquals(session.getCurrentRefreshHash(), suppliedHash);
        RefreshTokenGrace grace =
            refreshGrace.findByAuthSessionIdAndTokenHash(session.getId(), suppliedHash)
                .orElse(null);
        boolean gracefulPrevious = grace != null && now.isBefore(grace.getValidUntil());
        if (!current && !gracefulPrevious) {
            session.setRevokedAt(now);
            events.record(session.getAdminUser().getId(), "REFRESH_REUSE", "DENIED", ip,
                "Session revoked");
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                "Refresh token reuse was detected. Sign in again.");
        }
        String nextSecret = randomToken();
        RefreshTokenGrace retired =
            refreshGrace
                .findByAuthSessionIdAndTokenHash(session.getId(), session.getCurrentRefreshHash())
                .orElseGet(RefreshTokenGrace::new);
        retired.setAuthSession(session);
        retired.setTokenHash(session.getCurrentRefreshHash());
        retired.setValidUntil(now.plusSeconds(properties.security().refreshGraceSeconds()));
        refreshGrace.save(retired);
        session.setCurrentRefreshHash(CryptoService.sha256(nextSecret));
        session.setLastSeenAt(now);
        session.setUserAgent(limit(userAgent, 255));
        session.setIpAddress(limit(ip, 64));
        return new AuthenticationResult(session, jwt.createAccessToken(session),
            session.getPublicId() + "." + nextSecret,
            new CompleteAuthResponse(true, List.of()));
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300_000)
    @Transactional
    public void deleteExpiredRefreshGrace() {
        refreshGrace.deleteAllByValidUntilBefore(Instant.now().minus(Duration.ofHours(1)));
    }

    @Transactional
    public void reauthenticate(Long adminId, UUID sessionId, String password, String code,
                               String ip) {
        AdminUser user = users.findByIdForUpdate(adminId).orElseThrow();
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            events.record(adminId, "REAUTH", "DENIED", ip, "Invalid password");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Reauthentication failed.");
        }
        long step = totp.verifyAndResolveStep(crypto.decrypt(user.getEncryptedTotpSecret()), code);
        if (step < 0 ||
            (user.getLastAcceptedTotpStep() != null && step <= user.getLastAcceptedTotpStep())) {
            events.record(adminId, "REAUTH", "DENIED", ip, "Invalid or replayed TOTP");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Reauthentication failed.");
        }
        user.setLastAcceptedTotpStep(step);
        AuthSession session = sessions.findLockedByPublicId(sessionId).orElseThrow();
        session.setRecentAuthAt(Instant.now());
        events.record(adminId, "REAUTH", "SUCCESS", ip, "Critical operations unlocked");
    }

    @Transactional
    public void requireActiveSession(UUID sessionId) {
        AuthSession session = sessions.findByPublicId(sessionId).orElseThrow();
        if (!session.isActiveAt(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                "Your session has expired. Sign in again.");
        }
    }

    @Transactional
    public void logout(UUID sessionId, boolean allDevices, String ip) {
        AuthSession current = sessions.findByPublicId(sessionId).orElseThrow();
        Instant now = Instant.now();
        if (allDevices) {
            sessions.findAllByAdminUserIdAndRevokedAtIsNull(current.getAdminUser().getId())
                .forEach(session -> session.setRevokedAt(now));
        } else {
            current.setRevokedAt(now);
        }
        events.record(current.getAdminUser().getId(), allDevices ? "LOGOUT_ALL" : "LOGOUT",
            "SUCCESS", ip,
            allDevices ? "Signed out on all devices" : "Signed out on this device");
    }

    private AuthenticationResult newSession(AdminUser user, String userAgent, String ip) {
        String secret = randomToken();
        Instant now = Instant.now();
        AuthSession session = new AuthSession();
        session.setPublicId(UUID.randomUUID());
        session.setAdminUser(user);
        session.setCurrentRefreshHash(CryptoService.sha256(secret));
        session.setAbsoluteExpiresAt(
            now.plus(Duration.ofDays(properties.security().sessionDays())));
        session.setLastSeenAt(now);
        session.setRecentAuthAt(now);
        session.setUserAgent(limit(userAgent, 255));
        session.setIpAddress(limit(ip, 64));
        sessions.save(session);
        return new AuthenticationResult(session, jwt.createAccessToken(session),
            session.getPublicId() + "." + secret,
            new CompleteAuthResponse(true, List.of()));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private RefreshParts parseRefresh(String token) {
        try {
            int separator = token.indexOf('.');
            return new RefreshParts(UUID.fromString(token.substring(0, separator)),
                token.substring(separator + 1));
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Refresh token is invalid.");
        }
    }

    private String otpAuthUri(String email, String secret) {
        String label = URLEncoder.encode("Dispatch:" + email, StandardCharsets.UTF_8);
        return "otpauth://totp/" + label + "?secret=" + secret +
            "&issuer=Dispatch&algorithm=SHA1&digits=6&period=30";
    }

    private String normalizeRecoveryCode(String code) {
        return code.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    }

    private String limit(String value, int length) {
        if (value == null) return null;
        return value.length() <= length ? value : value.substring(0, length);
    }

    private String normalizeDisplayName(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    public record AuthenticationResult(AuthSession session, String accessToken, String refreshToken,
                                       CompleteAuthResponse response) {
    }

    private record RefreshParts(UUID sessionId, String secret) {
    }
}
