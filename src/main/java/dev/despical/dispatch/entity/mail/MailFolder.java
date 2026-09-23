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
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Getter
@Setter
@Entity
@Table(
    name = "mail_folders",
    uniqueConstraints = @UniqueConstraint(columnNames = {"mail_account_id", "full_name"}))
public class MailFolder extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mail_account_id", nullable = false)
    private MailAccount account;

    @Column(nullable = false, length = 512)
    private String fullName;

    @Column(nullable = false, length = 120)
    private String displayName;

    @Column(nullable = false)
    private long uidValidity;

    @Column(nullable = false)
    private long lastSyncedUid;

    @Column(nullable = false)
    private int unreadCount;

    @Column(length = 16)
    private String specialUse;
}
