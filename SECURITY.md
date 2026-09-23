# Security Policy

Security reports are taken seriously. If you find a vulnerability in Dispatch,
please report it privately instead of opening a public issue.

## Reporting a Vulnerability

Please send security reports to:

```text
contact@despical.dev
```

When possible, include the following details:

* A clear description of the vulnerability.
* Steps to reproduce the issue.
* The affected version, commit, branch, or deployment environment.
* Any relevant logs, screenshots, request examples, or proof of concept details.
* Whether the issue appears to affect administrators, connected mailboxes,
  stored messages, attachments, or server configuration.

Please do not include destructive payloads, real user data, private credentials,
or anything that could damage a running deployment.

## Scope

The following areas are considered security-sensitive:

* Admin authentication, sessions, JWT handling, and cookies.
* CSRF protection and form submissions.
* Mailbox ownership, sharing permissions, and message access.
* IMAP/SMTP connections, OAuth tokens, and remote message operations.
* Attachment scanning, storage paths, and HTML rendering.
* Actuator, metrics, admin pages, and other internal status endpoints.
* Rate limiting for mail requests and administrator login attempts.

Reports about spam, abuse, or non-security bugs should use the
normal GitHub issue tracker instead.

## Supported Versions

Only the latest public version of Dispatch is currently supported. If you are
running an older version, please update before reporting unless the same issue
also exists on the latest version.

## Response

After a valid report is received, the issue will be reviewed as soon as possible.
If the report is confirmed, a fix will be prepared privately and released with
credit where appropriate.

Please avoid public disclosure until a fix is available.
