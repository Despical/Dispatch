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
package dev.despical.dispatch.config;

import dev.despical.dispatch.security.AccessTokenFilter;
import dev.despical.dispatch.security.PrometheusTokenFilter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

import java.util.Locale;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Configuration
public class SecurityConfig {

    @Bean
    LocaleResolver localeResolver() {
        LocaleContextHolder.setDefaultLocale(Locale.ENGLISH);
        return new FixedLocaleResolver(Locale.ENGLISH);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        AccessTokenFilter accessTokenFilter,
        PrometheusTokenFilter prometheusTokenFilter
    ) {
        CookieCsrfTokenRepository csrf = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrf.setCookieName("XSRF-TOKEN");
        csrf.setHeaderName("X-XSRF-TOKEN");
        csrf.setCookiePath("/");
        CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfRequestHandler.setCsrfRequestAttributeName(null);

        return http.sessionManagement(
                session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(
                configurer ->
                    configurer
                        .csrfTokenRepository(csrf)
                        .csrfTokenRequestHandler(csrfRequestHandler)
                        .sessionAuthenticationStrategy(
                            (_, _, _) -> {
                            }))
            .authorizeHttpRequests(
                requests ->
                    requests.requestMatchers(
                            "/",
                            "/features",
                            "/security",
                            "/contact",
                            "/privacy-policy",
                            "/terms-of-service",
                            "/login",
                            "/bootstrap",
                            "/assets/**",
                            "/images/dispatch-live.png",
                            "/images/dispatch-hero.png",
                            "/images/dispatch-social.png",
                            "/favicon.svg",
                            "/preferences-bootstrap.js",
                            "/analytics.js",
                            "/robots.txt",
                            "/sitemap.xml",
                            "/oauth/google/callback",
                            "/error")
                        .permitAll()
                        .requestMatchers("/api/auth/**")
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        .requestMatchers("/actuator/prometheus")
                        .permitAll()
                        .requestMatchers("/api/admin/**")
                        .hasRole("ADMIN")
                        .requestMatchers("/admin/**")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .authenticated())
            .headers(
                headers ->
                    headers.contentSecurityPolicy(
                            csp ->
                                csp.policyDirectives(
                                    "default-src 'self'; script-src"
                                        + " 'self' https://www.googletagmanager.com;"
                                        + " style-src 'self' 'unsafe-inline';"
                                        + " img-src 'self' data: https://www.google-analytics.com"
                                        + " https://region1.google-analytics.com; font-src"
                                        + " 'self'; connect-src 'self'"
                                        + " https://www.google-analytics.com"
                                        + " https://region1.google-analytics.com;"
                                        + " frame-src 'self';"
                                        + " object-src 'none'; base-uri"
                                        + " 'none'; form-action 'self';"
                                        + " frame-ancestors 'self'"))
                        .frameOptions(HeadersConfigurer.FrameOptionsConfig::sameOrigin)
                        .referrerPolicy(
                            policy ->
                                policy.policy(
                                    org.springframework.security.web
                                        .header.writers
                                        .ReferrerPolicyHeaderWriter
                                        .ReferrerPolicy
                                        .NO_REFERRER)))
            .addFilterBefore(accessTokenFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(prometheusTokenFilter, AccessTokenFilter.class)
            .exceptionHandling(
                errors ->
                    errors.authenticationEntryPoint(
                        (request, response, _) -> {
                            if (request.getRequestURI().startsWith("/api/")) {
                                response.sendError(401);
                            } else {
                                response.sendRedirect("/login");
                            }
                        }))
            .build();
    }
}
