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

import org.apache.commons.codec.binary.Base32;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
public class TotpService {

    private static final long STEP_SECONDS = 30;

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Base32 base32 = new Base32();

    public TotpService() {
        this(Clock.systemUTC());
    }

    TotpService(Clock clock) {
        this.clock = clock;
    }

    public String newSecret() {
        byte[] secret = new byte[20];

        random.nextBytes(secret);
        return base32.encodeToString(secret).replace("=", "").toUpperCase(Locale.ROOT);
    }

    public long currentStep() {
        return Instant.now(clock).getEpochSecond() / STEP_SECONDS;
    }

    public long verifyAndResolveStep(String secret, String code) {
        if (code == null || !code.matches("\\d{6}")) {
            return -1;
        }

        long current = currentStep();

        for (long step = current - 1; step <= current + 1; step++) {
            if (constantTimeCode(codeForStep(secret, step), code)) {
                return step;
            }
        }

        return -1;
    }

    String codeForStep(String secret, long step) {
        try {
            byte[] key = base32.decode(secret);

            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));

            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());

            int offset = hash[hash.length - 1] & 0x0f;
            int binary =
                ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);

            return "%06d".formatted(binary % 1_000_000);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private boolean constantTimeCode(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(), supplied.getBytes());
    }
}
