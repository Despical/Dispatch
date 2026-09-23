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
import dev.despical.dispatch.entity.mail.Attachment;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
@RequiredArgsConstructor
public class ClamAvService {

    private final DispatchProperties properties;

    @Getter
    private volatile boolean available;

    public ScanResult scan(Path path) {
        try (Socket socket = new Socket()) {
            socket.connect(
                new InetSocketAddress(properties.clamav().host(), properties.clamav().port()),
                properties.clamav().timeoutMs());
            socket.setSoTimeout(properties.clamav().timeoutMs());

            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));

            try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(path))) {
                byte[] buffer = new byte[8192];
                int read;

                while ((read = input.read(buffer)) >= 0) {
                    output.writeInt(read);
                    output.write(buffer, 0, read);
                }
            }

            output.writeInt(0);
            output.flush();

            ByteArrayOutputStream response = new ByteArrayOutputStream();

            int value;
            while ((value = socket.getInputStream().read()) > 0 && response.size() < 1024) {
                response.write(value);
            }

            String result = response.toString(StandardCharsets.UTF_8);
            available = true;

            if (result.contains("FOUND")) {
                return new ScanResult(Attachment.ScanStatus.QUARANTINED, "Threat detected");
            }

            if (result.contains("OK")) {
                return new ScanResult(Attachment.ScanStatus.CLEAN,
                    "No threat detected by current scanner definitions");
            }

            return new ScanResult(Attachment.ScanStatus.SUSPICIOUS,
                "Scanner returned an unexpected result");
        } catch (Exception _) {
            available = false;
            return new ScanResult(Attachment.ScanStatus.UNAVAILABLE,
                "Scanner unavailable; file is not trusted");
        }
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 1_000)
    public void checkAvailability() {
        try (Socket socket = new Socket()) {
            socket.connect(
                new InetSocketAddress(properties.clamav().host(), properties.clamav().port()),
                properties.clamav().timeoutMs());
            socket.setSoTimeout(properties.clamav().timeoutMs());
            socket.getOutputStream().write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            ByteArrayOutputStream response = new ByteArrayOutputStream();

            int value;
            while ((value = socket.getInputStream().read()) > 0 && response.size() < 16) {
                response.write(value);
            }

            available = response.toString(StandardCharsets.US_ASCII).equals("PONG");
        } catch (Exception _) {
            available = false;
        }
    }

    public record ScanResult(Attachment.ScanStatus status, String detail) {
    }
}
