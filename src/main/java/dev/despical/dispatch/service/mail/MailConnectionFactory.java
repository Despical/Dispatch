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
import dev.despical.dispatch.entity.mail.MailAccount;
import dev.despical.dispatch.security.CryptoService;

import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Properties;

/**
 * @author Despical
 * <p>
 * Created at 21.09.2026
 */
@Service
public class MailConnectionFactory {

    private final DispatchProperties properties;
    private final CryptoService crypto;
    private final boolean allowPrivateHosts;
    private final HostPolicy hostPolicy;
    private final ObjectProvider<GoogleOAuthService> googleOAuth;

    public MailConnectionFactory(DispatchProperties properties, CryptoService crypto,
                                 HostPolicy hostPolicy,
                                 ObjectProvider<GoogleOAuthService> googleOAuth) {
        this.properties = properties;
        this.crypto = crypto;
        this.allowPrivateHosts = properties.mail().allowPrivateHosts();
        this.hostPolicy = hostPolicy;
        this.googleOAuth = googleOAuth;
    }

    @PostConstruct
    void initializeProviders() throws MessagingException {
        Session session = Session.getInstance(new Properties());
        session.getStore("imaps");
        session.getTransport("smtp");
    }

    public Store openImap(MailAccount account) throws Exception {
        hostPolicy.validate(account.getImapHost());
        Properties mail = baseTimeouts();
        mail.put("mail.store.protocol", "imaps");
        mail.put("mail.imaps.ssl.enable", "true");
        mail.put("mail.imaps.ssl.checkserveridentity", Boolean.toString(!allowPrivateHosts));
        if (allowPrivateHosts) mail.put("mail.imaps.ssl.trust", "*");
        mail.put("mail.imaps.peek", "true");
        mail.put("mail.imaps.fetchsize", "1048576");
        if ("GOOGLE".equals(account.getAuthProvider()))
            mail.put("mail.imaps.auth.mechanisms", "XOAUTH2");
        Store store = Session.getInstance(mail).getStore("imaps");
        store.connect(account.getImapHost(), account.getImapPort(), account.getUsername(),
            credential(account));
        return store;
    }

    public Transport openSmtp(MailAccount account) throws Exception {
        hostPolicy.validate(account.getSmtpHost());
        boolean implicitTls = account.getSmtpPort() == 465;
        Properties mail = baseTimeouts();
        mail.put("mail.transport.protocol", "smtp");
        mail.put("mail.smtp.auth", "true");
        mail.put("mail.smtp.ssl.enable", Boolean.toString(implicitTls));
        mail.put("mail.smtp.ssl.checkserveridentity", Boolean.toString(!allowPrivateHosts));
        if (allowPrivateHosts) mail.put("mail.smtp.ssl.trust", "*");
        mail.put("mail.smtp.starttls.enable", Boolean.toString(!implicitTls));
        mail.put("mail.smtp.starttls.required", Boolean.toString(!implicitTls));
        if ("GOOGLE".equals(account.getAuthProvider()))
            mail.put("mail.smtp.auth.mechanisms", "XOAUTH2");
        Transport transport = Session.getInstance(mail).getTransport("smtp");
        transport.connect(account.getSmtpHost(), account.getSmtpPort(), account.getUsername(),
            credential(account));
        return transport;
    }

    public Session smtpSession(MailAccount account) {
        boolean implicitTls = account.getSmtpPort() == 465;
        Properties mail = baseTimeouts();
        mail.put("mail.smtp.auth", "true");
        mail.put("mail.smtp.ssl.enable", Boolean.toString(implicitTls));
        mail.put("mail.smtp.ssl.checkserveridentity", Boolean.toString(!allowPrivateHosts));
        if (allowPrivateHosts) mail.put("mail.smtp.ssl.trust", "*");
        mail.put("mail.smtp.starttls.enable", Boolean.toString(!implicitTls));
        mail.put("mail.smtp.starttls.required", Boolean.toString(!implicitTls));
        if ("GOOGLE".equals(account.getAuthProvider()))
            mail.put("mail.smtp.auth.mechanisms", "XOAUTH2");
        return Session.getInstance(mail);
    }

    private String credential(MailAccount account) {
        return "GOOGLE".equals(account.getAuthProvider())
            ? googleOAuth.getObject().accessToken(account)
            : crypto.decrypt(account.getEncryptedPassword());
    }

    private Properties baseTimeouts() {
        Properties mail = new Properties();
        String connect = Integer.toString(properties.mail().connectionTimeoutMs());
        String read = Integer.toString(properties.mail().readTimeoutMs());
        for (String protocol : new String[]{"imap", "imaps", "smtp", "smtps"}) {
            mail.put("mail." + protocol + ".connectiontimeout", connect);
            mail.put("mail." + protocol + ".timeout", read);
            mail.put("mail." + protocol + ".writetimeout", read);
        }
        mail.put("mail.debug", "false");
        return mail;
    }
}
