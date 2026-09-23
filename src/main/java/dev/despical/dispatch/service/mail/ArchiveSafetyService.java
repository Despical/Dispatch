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

import dev.despical.dispatch.config.DispatchProperties;

import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
public class ArchiveSafetyService {

    private final long expandedLimit;
    private final int depthLimit;

    public ArchiveSafetyService(DispatchProperties properties) {
        this.expandedLimit = properties.mail().archiveExpandedMaxBytes();
        this.depthLimit = properties.mail().archiveMaxDepth();
    }

    public void inspect(String filename, InputStream input) throws IOException {
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) return;
        inspectZip(input, 1, new Counter());
    }

    private void inspectZip(InputStream input, int depth, Counter counter) throws IOException {
        if (depth > depthLimit) throw new IOException("Archive nesting limit exceeded");

        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];

            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                ByteArrayOutputStream nested = entry.getName().toLowerCase(Locale.ROOT).endsWith(".zip")
                    ? new ByteArrayOutputStream()
                    : null;

                int read;
                while ((read = zip.read(buffer)) >= 0) {
                    counter.total += read;

                    if (counter.total > expandedLimit) {
                        throw new IOException("Archive expansion limit exceeded");
                    }

                    if (nested != null) {
                        nested.write(buffer, 0, read);
                    }
                }

                if (nested != null)
                    inspectZip(new ByteArrayInputStream(nested.toByteArray()), depth + 1, counter);
            }
        }
    }

    private static final class Counter {
        long total;
    }
}
