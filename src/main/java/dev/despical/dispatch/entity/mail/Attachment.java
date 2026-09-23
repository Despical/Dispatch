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
@Table(name = "attachments")
public class Attachment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mail_message_id", nullable = false)
    private MailMessage message;

    @Column(nullable = false, length = 512)
    private String filename;

    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 512)
    private String storagePath;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ScanStatus scanStatus = ScanStatus.SCANNING;

    @Column
    private String scanDetail;

    public enum ScanStatus {
        SCANNING,
        CLEAN,
        SUSPICIOUS,
        UNAVAILABLE,
        QUARANTINED
    }
}
