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
package dev.despical.dispatch.entity.mail;

import dev.despical.dispatch.entity.common.BaseEntity;
import dev.despical.dispatch.entity.security.AdminUser;

import jakarta.persistence.*;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
@Getter
@Setter
@Entity
@Table(
    name = "mail_shares",
    uniqueConstraints = @UniqueConstraint(columnNames = {"owner_id", "viewer_id"}))
public class MailShare extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private AdminUser owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "viewer_id", nullable = false)
    private AdminUser viewer;

    @Column(nullable = false)
    private boolean hidden;

    @Column(nullable = false)
    private boolean canView = true;

    @ManyToMany
    @JoinTable(
        name = "mail_share_accounts",
        joinColumns = @JoinColumn(name = "share_id"),
        inverseJoinColumns = @JoinColumn(name = "account_id"))
    private Set<MailAccount> accounts = new LinkedHashSet<>();

    @Column(nullable = false)
    private boolean canSend;

    @Column(nullable = false)
    private boolean canOrganize;

    @Column(nullable = false)
    private boolean canDelete;
}
