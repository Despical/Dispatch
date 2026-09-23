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
package dev.despical.dispatch.service.mail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.despical.dispatch.config.DispatchProperties;
import dev.despical.dispatch.entity.mail.Attachment;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.mail.AttachmentRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
class AttachmentScanRetryTest {
    final AttachmentRepository repository = mock(AttachmentRepository.class);
    final ClamAvService scanner = mock(ClamAvService.class);
    @TempDir
    Path root;

    AttachmentService service() {
        var mail =
            new DispatchProperties.Mail(
                root.toString(), 5000, false, 1000, 1000, 1048576, 1048576, 3);
        return new AttachmentService(
            repository,
            mock(ArchiveSafetyService.class),
            scanner,
            new DispatchProperties(null, mail, null));
    }

    @Test
    void retryRequiresOwnershipAndAnUnavailableStatus() {
        when(repository.findByIdAndMessageAccountOwnerId(7L, 42L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().retryScan(7L, 42L)).isInstanceOf(ApiException.class);
        Attachment attachment = new Attachment();
        attachment.setScanStatus(Attachment.ScanStatus.QUARANTINED);
        when(repository.findByIdAndMessageAccountOwnerId(7L, 42L))
            .thenReturn(Optional.of(attachment));
        assertThat(service().retryScan(7L, 42L).getScanStatus())
            .isEqualTo(Attachment.ScanStatus.QUARANTINED);
        verifyNoInteractions(scanner);
    }

    @Test
    void retryUsesTheActualScannerResult() throws Exception {
        Path file = root.resolve("example.txt");
        Files.writeString(file, "Example content");
        Attachment attachment = new Attachment();
        attachment.setScanStatus(Attachment.ScanStatus.UNAVAILABLE);
        attachment.setStoragePath("example.txt");
        when(repository.findByIdAndMessageAccountOwnerId(7L, 42L))
            .thenReturn(Optional.of(attachment));
        when(scanner.scan(file))
            .thenReturn(
                new ClamAvService.ScanResult(
                    Attachment.ScanStatus.QUARANTINED, "Threat detected"));
        assertThat(service().retryScan(7L, 42L).getScanStatus())
            .isEqualTo(Attachment.ScanStatus.QUARANTINED);
        assertThat(attachment.getScanDetail()).isEqualTo("Threat detected");
    }
}
