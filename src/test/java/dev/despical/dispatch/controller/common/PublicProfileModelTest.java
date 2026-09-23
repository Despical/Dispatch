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
package dev.despical.dispatch.controller.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.despical.dispatch.config.DispatchProperties;
import dev.despical.dispatch.security.DispatchPrincipal;
import dev.despical.dispatch.service.security.AuthService;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.ui.ExtendedModelMap;

import java.util.List;
import java.util.UUID;

/**
 * @author Despical
 * <p>
 * Created at 23.09.2026
 */
class PublicProfileModelTest {
    private final PageController pages =
        new PageController(
            mock(AuthService.class),
            mock(DispatchProperties.class));

    @Test
    void rendersAuthenticatedProfileBeforeClientSideRefresh() {
        var principal =
            new DispatchPrincipal(1L, "berke@example.test", "Berke Akçen", UUID.randomUUID());
        var authentication =
            UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        var model = new ExtendedModelMap();
        pages.publicProfile(model, principal, authentication, new MockHttpServletRequest());
        assertThat(model.get("publicSignedIn")).isEqualTo(true);
        assertThat(model.get("publicInitials")).isEqualTo("BA");
        assertThat(model.get("publicRoleLabel")).isEqualTo("Administrator");
    }

    @Test
    void hidesSignInWhileRefreshCookieIsBeingChecked() {
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie("DISPATCH_REFRESH", "opaque"));
        var model = new ExtendedModelMap();
        pages.publicProfile(model, null, null, request);
        assertThat(model.get("publicSignedIn")).isEqualTo(false);
        assertThat(model.get("publicAuthPending")).isEqualTo(true);
    }
}
