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

import java.time.Instant;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Getter
@Setter
@Entity
@Table(name = "outbound_messages")
public class OutboundMessage extends BaseEntity {

    @Column(nullable = false, unique = true, updatable = false)
    private UUID publicId;

    @Column(unique = true, length = 100)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mail_account_id", nullable = false)
    private MailAccount account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false, updatable = false)
    private AdminUser createdBy;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String recipients;

    @Column(columnDefinition = "TEXT")
    private String cc;

    @Column(columnDefinition = "TEXT")
    private String bcc;

    @Column(nullable = false, length = 1000)
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String bodyHtml;

    @Column(columnDefinition = "TEXT")
    private String bodyText;

    @Column(length = 998)
    private String inReplyTo;

    @Column(columnDefinition = "TEXT")
    private String referencesHeader;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Status status;

    private Instant lastDraftSavedAt;

    private Instant trashedAt;

    private Long sourceMessageId;

    private Instant sendStartedAt;

    private Instant sentAt;

    @Column(length = 500)
    private String failureReason;

    public enum Status {
        DRAFT,
        QUEUED,
        SENDING,
        SENT,
        FAILED,
        UNCERTAIN
    }
}
