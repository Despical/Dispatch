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

import dev.despical.dispatch.exception.ApiException;
import org.springframework.http.HttpStatus;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * @author Despical
 * <p>
 * Created at 22.09.2026
 */
public final class AttachmentPreview {
    private AttachmentPreview() {
    }

    public static Content read(Path path, String filename) throws IOException {
        if (Files.size(path) > 10 * 1024 * 1024) throw tooLarge("10 MB");

        try (var input = ImageIO.createImageInputStream(path.toFile())) {
            var readers = ImageIO.getImageReaders(input);

            if (readers.hasNext()) {
                var reader = readers.next();

                try {
                    reader.setInput(input);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);

                    if (Set.of("png", "jpeg", "gif", "bmp").contains(format)) {
                        if ((long) reader.getWidth(0) * reader.getHeight(0) > 20_000_000) {
                            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "This image is too large to preview (20 MB limit). Download it to view it locally.");
                        }

                        return new Content(
                            "image",
                            "data:image/"
                                + format
                                + ";base64,"
                                + Base64.getEncoder()
                                .encodeToString(Files.readAllBytes(path)));
                    }
                } finally {
                    reader.dispose();
                }
            }
        }
        String extension = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (Set.of(
            "txt", "log", "csv", "json", "xml", "html", "htm", "md", "yml", "yaml",
            "svg", "css", "js").contains(extension)
        ) {
            if (Files.size(path) > 512 * 1024) throw tooLarge("512 KB for text files");

            try {
                String text = StandardCharsets.UTF_8
                    .newDecoder()
                    .decode(ByteBuffer.wrap(Files.readAllBytes(path)))
                    .toString();

                if (!text.contains("\0")) {
                    return new Content("text", text);
                }
            } catch (CharacterCodingException _) {
            }
        }

        throw new ApiException(
            HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "Preview is not available for this file type. Download it to open it locally.");
    }

    private static ApiException tooLarge(String limit) {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "This file is too large to preview (" + limit
            + " limit). Download it to open it locally.");
    }

    public record Content(String kind, String content) {
    }
}
