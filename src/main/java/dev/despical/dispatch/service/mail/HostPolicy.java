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
import dev.despical.dispatch.exception.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
public class HostPolicy {

    private final boolean allowPrivate;

    public HostPolicy(DispatchProperties properties) {
        this.allowPrivate = properties.mail().allowPrivateHosts();
    }

    public void validate(String hostname) {
        if (hostname == null || hostname.isBlank() || hostname.contains("/") ||
            hostname.contains(":")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Mail server hostname is invalid.");
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(hostname)) {
                if (!allowPrivate &&
                    (address.isAnyLocalAddress() || address.isLoopbackAddress() ||
                        address.isLinkLocalAddress() || address.isSiteLocalAddress() ||
                        address.isMulticastAddress())) {
                    throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "Private or local mail server addresses are not allowed.");
                }
            }
        } catch (UnknownHostException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Mail server hostname could not be resolved.");
        }
    }
}
