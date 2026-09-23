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
package dev.despical.dispatch.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Validated
@ConfigurationProperties(prefix = "dispatch")
public record DispatchProperties(@Valid Security security, @Valid Mail mail, @Valid ClamAv clamav) {

    public record Security(
        @NotBlank String jwtSigningKeyBase64,
        @NotBlank String dataEncryptionKeyBase64,
        @NotBlank String bootstrapToken,
        @NotBlank String prometheusScrapeToken,
        boolean cookieSecure,
        @NotBlank String cookieSameSite,
        @Min(1) int accessMinutes,
        @Min(1) int sessionDays,
        @Min(1) int refreshGraceSeconds) {
    }

    public record Mail(
        @NotBlank String storagePath,
        @Min(5000) long syncIntervalMs,
        boolean allowPrivateHosts,
        @Min(1000) int connectionTimeoutMs,
        @Min(1000) int readTimeoutMs,
        @Min(1024) long attachmentMaxBytes,
        @Min(1024) long archiveExpandedMaxBytes,
        @Min(1) @Max(10) int archiveMaxDepth) {
    }

    public record ClamAv(
        @NotBlank String host, @Min(1) @Max(65535) int port, @Min(1000) int timeoutMs) {
    }
}
