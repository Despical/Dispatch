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
package dev.despical.dispatch.security;

import dev.despical.dispatch.config.DispatchProperties;
import dev.despical.dispatch.entity.security.AuthSession;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final DispatchProperties properties;

    public JwtService(DispatchProperties properties) {
        this.properties = properties;
        byte[] raw = Base64.getDecoder().decode(properties.security().jwtSigningKeyBase64());

        if (raw.length < 32) {
            throw new IllegalStateException("JWT_SIGNING_KEY_BASE64 must decode to at least 32 bytes");
        }

        key = Keys.hmacShaKeyFor(raw);
    }

    public String createAccessToken(AuthSession session) {
        Instant now = Instant.now();
        Instant expires = now.plusSeconds(properties.security().accessMinutes() * 60L);

        if (expires.isAfter(session.getAbsoluteExpiresAt())) {
            expires = session.getAbsoluteExpiresAt();
        }

        return Jwts.builder()
            .subject(session.getAdminUser().getId().toString())
            .id(session.getPublicId().toString())
            .claim("email", session.getAdminUser().getEmail())
            .claim("name", session.getAdminUser().getDisplayName())
            .claim("typ", "access")
            .issuedAt(Date.from(now))
            .expiration(Date.from(expires))
            .signWith(key)
            .compact();
    }

    public Claims parseAccessToken(String token) {
        Claims claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();

        if (!"access".equals(claims.get("typ", String.class))) {
            throw new IllegalArgumentException("Wrong token type");
        }

        return claims;
    }
}
