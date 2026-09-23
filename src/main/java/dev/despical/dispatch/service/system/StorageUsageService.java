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
package dev.despical.dispatch.service.system;

import dev.despical.dispatch.config.DispatchProperties;
import dev.despical.dispatch.dto.mail.MailDtos.StorageUsage;
import dev.despical.dispatch.exception.ApiException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
public class StorageUsageService {

    private final Path storageRoot;

    @Autowired
    public StorageUsageService(DispatchProperties properties) {
        this(Path.of(properties.mail().storagePath()));
    }

    StorageUsageService(Path storageRoot) {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
    }

    public StorageUsage current() {
        try {
            Path probe = nearestExistingPath(storageRoot);
            var fileStore = Files.getFileStore(probe);
            long total = Math.max(0, fileStore.getTotalSpace());
            long available = Math.max(0, fileStore.getUsableSpace());
            long used = Math.max(0, total - available);
            int percent = total == 0 ? 0 : (int) Math.min(100, Math.round(used * 100.0 / total));
            return new StorageUsage(used, total, percent);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "Storage usage is temporarily unavailable.");
        }
    }

    private Path nearestExistingPath(Path start) throws IOException {
        Path candidate = start;
        while (candidate != null && !Files.exists(candidate))
            candidate = candidate.getParent();
        if (candidate == null) throw new IOException("No existing storage path is available");
        return candidate;
    }
}
