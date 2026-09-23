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

import dev.despical.dispatch.exception.ApiException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
class AttachmentPreviewTest {
    @TempDir
    Path directory;

    @Test
    void detectsRealImagesInsteadOfTrustingFilename() throws Exception {
        Path path = directory.resolve("file.bin");
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", path.toFile());
        var result = AttachmentPreview.read(path, "unknown.bin");
        assertThat(result.kind()).isEqualTo("image");
        assertThat(result.content()).startsWith("data:image/png;base64,");
    }

    @Test
    void enforcesActualFileSizeAndTextLimit() throws Exception {
        Path path = directory.resolve("large.txt");
        Files.write(path, new byte[512 * 1024 + 1]);
        assertThatThrownBy(() -> AttachmentPreview.read(path, "large.txt"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("512 KB");
        Files.write(path, new byte[10 * 1024 * 1024 + 1]);
        assertThatThrownBy(() -> AttachmentPreview.read(path, "large.png"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("10 MB");
    }

    @Test
    void binaryAndUnsupportedFilesCannotMasqueradeAsText() throws Exception {
        Path path = directory.resolve("unknown");
        Files.write(path, new byte[]{0, 1, 2});
        assertThatThrownBy(() -> AttachmentPreview.read(path, "fake.txt"))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("file type");
        Files.writeString(path, "example");
        assertThatThrownBy(() -> AttachmentPreview.read(path, "example.exe"))
            .isInstanceOf(ApiException.class);
    }
}
