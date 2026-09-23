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
package dev.despical.dispatch.dto.security;

import dev.despical.dispatch.entity.security.UserRole;
import jakarta.validation.constraints.*;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@NoArgsConstructor
public final class AuthDtos {

    public record LoginRequest(@Email @NotBlank String email, @NotBlank String password) {
    }

    public record ChallengeResponse(
        UUID challengeId,
        boolean setupRequired,
        String totpSecret,
        String otpauthUri,
        boolean passwordChangeRequired
    ) {
    }

    public record TotpRequest(
        @NotNull UUID challengeId,
        @NotBlank String code,
        @Size(min = 14, max = 200) String newPassword
    ) {
    }

    public record AuthenticatorRecoveryRequest(
        @NotNull UUID challengeId,
        @NotBlank String recoveryCode
    ) {
    }

    public record PasswordRecoveryRequest(
        @Email @NotBlank String email,
        @NotBlank String authenticatorCode,
        @NotBlank String recoveryCode,
        @NotBlank @Size(min = 14, max = 200) String newPassword
    ) {
    }

    public record BootstrapRequest(
        @NotBlank @Size(max = 120) @Pattern(regexp = "^[^<>\\p{Cntrl}]+$") String displayName,
        @Email @NotBlank String email,
        @NotBlank @Size(min = 14, max = 200) String password
    ) {
    }

    public record BootstrapResponse(UUID challengeId, String totpSecret, String otpauthUri) {
    }

    public record CompleteAuthResponse(boolean authenticated, List<String> recoveryCodes) {
    }

    public record ReauthenticateRequest(@NotBlank String password, @NotBlank String code) {
    }

    public record AdminCreateRequest(
        @NotBlank @Size(max = 120) @Pattern(regexp = "^[^<>\\p{Cntrl}]+$") String displayName,
        @Email @NotBlank String email,
        @NotBlank @Size(min = 14, max = 200) String password,
        @NotNull
        UserRole role
    ) {
    }

    public record ProfileUpdateRequest(
        @NotBlank @Size(max = 120) @Pattern(regexp = "^[^<>\\p{Cntrl}]+$")
        String displayName
    ) {
    }

    public record ProfileView(
        Long id,
        String displayName,
        String email,
        UserRole role
    ) {
    }

    public record AdminView(
        Long id,
        String displayName,
        String email,
        boolean enabled,
        boolean totpEnabled,
        UserRole role
    ) {
    }
}
