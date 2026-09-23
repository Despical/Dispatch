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
package dev.despical.dispatch.entity.security;

import dev.despical.dispatch.entity.common.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Getter
@Setter
@Entity
@Table(name = "admin_users")
public class AdminUser extends BaseEntity {

    @Column(nullable = false, unique = true, length = 190)
    private String email;

    @Column(nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role = UserRole.USER;

    @Column(nullable = false)
    private String passwordHash;

    @Column(length = 512)
    private String encryptedTotpSecret;

    @Column(nullable = false)
    private boolean totpEnabled;

    @Column(nullable = false)
    private boolean passwordChangeRequired;

    @Column(nullable = false)
    private boolean enabled = true;

    private Long lastAcceptedTotpStep;

    private Instant passwordChangedAt;

    private Instant lastLoginAt;
}
