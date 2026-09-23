# Dispatch

[![](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Java 25](https://img.shields.io/badge/Java-25-007396.svg)](https://www.java.com/)
![Gradle](https://img.shields.io/badge/Gradle-9.7.1-079ec0?logo=gradle&logoColor=white)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F.svg)](https://spring.io/projects/spring-boot)

Dispatch is a private, self-hosted webmail workspace for connecting existing mail accounts. It combines multiple inboxes with drafts, contacts, mailbox sharing, and an isolated reading pane. Administrators manage access, and the interface stays focused on mail.

---

## Features

* **Unified mail:** Read connected accounts together or open an individual mailbox.
* **Gmail and IMAP:** Connect Gmail through Google OAuth or use supported IMAP/SMTP accounts.
* **Drafts and sending:** Save drafts and send through a durable outbox.
* **Safer reading:** Render mail in an isolated pane and scan attachments with ClamAV.
* **Self-hostable:** Run the application with your own MySQL database and mail providers.

---

## Requirements

* Java 25
* Node.js 22.17 or newer and pnpm 10
* MySQL 8.4 or compatible
* ClamAV for attachment scanning

---

## Building From Source

### 1. Clone the Repository

Clone this repository and change into the `Dispatch` directory.

### 2. Environment Configuration

Create a local environment file and set database credentials and separate random values for `JWT_SIGNING_KEY_BASE64`, `DATA_ENCRYPTION_KEY_BASE64`, `BOOTSTRAP_TOKEN`, and `PROMETHEUS_SCRAPE_TOKEN`.

Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Linux / macOS:

```bash
cp .env.example .env
```

### 3. Build & Test

Windows:

```bat
gradlew.bat test
gradlew.bat build
```

Linux / macOS:

```bash
./gradlew test
./gradlew build
```

### 4. Run Locally

Windows:

```bat
gradlew.bat bootRun
```

Linux / macOS:

```bash
./gradlew bootRun
```

Once started, open `http://localhost:8080/`. The first administrator must complete local setup and authenticator enrollment.

The single V1 migration creates the current schema for a new database.

### Docker deployment

Copy `.env.example` to `.env`, set unique production secrets and mail provider
credentials, then start the stack with `docker compose up -d --build`. The app
binds to `127.0.0.1:8082` by default; set `APP_BIND_HOST` and `APP_HOST_PORT`
only when another local port is needed. For the shared Despical VPS, use
`docker compose -f docker-compose.yml -f docker-compose.vps.yml up -d --build`
and follow the [existing Nginx integration guide](docker/nginx/README.md).

---

## Mail Accounts

Dispatch connects to existing mail providers; it does not run a mail server. For Gmail OAuth, configure `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`, and `GOOGLE_OAUTH_REDIRECT_URI`. Other accounts use verified TLS connections. Attachments remain unavailable until a ClamAV scan succeeds.

---

## Security

We prioritize user privacy and application integrity. Please do not open public issues for discovered vulnerabilities.

Read our [SECURITY.md](SECURITY.md) for responsible disclosure reporting.

---

## Contributing

We welcome Pull Requests from the community. To help us maintain clean project history and formatting, please follow these guidelines:

* **No tabs:** Use spaces exclusively for indentation.
* **Style consistency:** Respect the established code architecture and style templates.
* **Version control cleanliness:** Do not increment project version numbers in example configurations within your PR.
* **Minimal diffs:** Disable automated reformat-on-save settings that affect untouched files.

Learn more via our formal [Contribution Guidelines](CONTRIBUTING.md).

---

## License

Dispatch is licensed under the [GPL-3.0-or-later License](http://www.gnu.org/licenses/gpl-3.0.html).

See the [LICENSE](LICENSE) file for comprehensive copyright notices and third-party attributions.
