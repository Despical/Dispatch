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
package dev.despical.dispatch.controller.mail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.despical.dispatch.entity.mail.Attachment;
import dev.despical.dispatch.exception.ApiException;
import dev.despical.dispatch.repository.mail.AttachmentRepository;
import dev.despical.dispatch.security.DispatchPrincipal;
import dev.despical.dispatch.service.mail.AttachmentService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
class AttachmentControllerTest {
    final AttachmentService service = mock(AttachmentService.class);
    final dev.despical.dispatch.service.mail.MailAccessService access =
        mock(dev.despical.dispatch.service.mail.MailAccessService.class);
    final AttachmentController controller = new AttachmentController(service, access);
    final DispatchPrincipal principal =
        new DispatchPrincipal(42L, "owner@example.test", "Owner", UUID.randomUUID());
    @TempDir
    Path directory;

    @Test
    void bothRoutesEnforceOwnershipBeforeReadingFiles() {
        when(access.attachment(42L, 7L))
            .thenThrow(
                new ApiException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "Attachment not found."));
        assertThatThrownBy(() -> controller.download(7L, principal))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.preview(7L, principal))
            .isInstanceOf(ApiException.class);
        verifyNoInteractions(service);
    }

    @Test
    void allUntrustedStatusesBlockBothRoutes() {
        for (var status : Attachment.ScanStatus.values()) {
            if (status == Attachment.ScanStatus.CLEAN) continue;
            Attachment attachment = new Attachment();
            attachment.setScanStatus(status);
            when(access.attachment(42L, 7L)).thenReturn(attachment);
            assertThatThrownBy(() -> controller.download(7L, principal))
                .isInstanceOf(ApiException.class);
            assertThatThrownBy(() -> controller.preview(7L, principal))
                .isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(service);
    }

    @Test
    void cleanTextDownloadsWithCorrectDispositionAndPreviewsAsText() throws Exception {
        Path file = directory.resolve("message.html");
        Files.writeString(file, "<script>alert('example')</script>");
        Attachment attachment = new Attachment();
        attachment.setScanStatus(Attachment.ScanStatus.CLEAN);
        attachment.setFilename("my message.html");
        when(access.attachment(42L, 7L)).thenReturn(attachment);
        when(service.resolveForDownload(attachment)).thenReturn(file);
        var download = controller.download(7L, principal);
        assertThat(download.getHeaders().getContentDisposition().getFilename())
            .isEqualTo("my message.html");
        assertThat(download.getHeaders().getCacheControl()).isEqualTo("no-store");
        var preview = controller.preview(7L, principal).getBody();
        assertThat(preview.kind()).isEqualTo("text");
        assertThat(preview.content()).startsWith("<script>");
    }
}
