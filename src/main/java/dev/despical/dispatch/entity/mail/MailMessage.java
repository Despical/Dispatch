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

import java.time.Instant;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Getter
@Setter
@Entity
@Table(
    name = "mail_messages",
    uniqueConstraints =
    @UniqueConstraint(columnNames = {"mail_folder_id", "uid_validity", "imap_uid"}))
public class MailMessage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mail_account_id", nullable = false)
    private MailAccount account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mail_folder_id", nullable = false)
    private MailFolder folder;

    @Column(nullable = false)
    private long uidValidity;

    @Column(nullable = false)
    private long imapUid;

    @Column(length = 998)
    private String internetMessageId;

    @Column(length = 32)
    private String gmailMessageId;

    @Column(length = 128)
    private String trashOriginLabels;

    @Column(length = 998)
    private String inReplyTo;

    @Column(length = 998)
    private String referencesHeader;

    @Column(nullable = false, length = 1000)
    private String subject;

    @Column(nullable = false, length = 1000)
    private String fromAddress;

    @Column(columnDefinition = "TEXT")
    private String recipients;

    @Column(columnDefinition = "LONGTEXT")
    private String textBody;

    @Column(columnDefinition = "LONGTEXT")
    private String sanitizedHtml;

    @Column(columnDefinition = "LONGTEXT")
    private String styledHtml;

    @Column(nullable = false)
    private boolean readFlag;

    @Column(nullable = false)
    private boolean starredFlag;

    @Column(nullable = false)
    private boolean pinnedFlag;

    private Instant pinnedAt;

    @Column(nullable = false)
    private boolean trashedFlag;

    private Instant trashedAt;

    @Column(length = 512)
    private String trashOriginFolder;

    @Column(nullable = false)
    private boolean hasAttachments;

    private Instant sentAt;

    private Instant receivedAt;
}
