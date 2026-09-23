CREATE TABLE admin_users
(
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    version                 BIGINT       NOT NULL DEFAULT 0,
    email                   VARCHAR(190) NOT NULL,
    display_name            VARCHAR(120) NOT NULL,
    password_hash           VARCHAR(255) NOT NULL,
    password_change_required BOOLEAN     NOT NULL DEFAULT FALSE,
    role                    VARCHAR(20)  NOT NULL DEFAULT 'USER',
    encrypted_totp_secret   VARCHAR(512),
    totp_enabled            BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE,
    last_accepted_totp_step BIGINT,
    password_changed_at     TIMESTAMP(6),
    last_login_at           TIMESTAMP(6),
    created_at              TIMESTAMP(6) NOT NULL,
    updated_at              TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_admin_users_email (email)
);

CREATE TABLE recovery_codes
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    version       BIGINT       NOT NULL DEFAULT 0,
    admin_user_id BIGINT       NOT NULL,
    code_hash     VARCHAR(64)  NOT NULL,
    used_at       TIMESTAMP(6),
    created_at    TIMESTAMP(6) NOT NULL,
    updated_at    TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY           idx_recovery_user (admin_user_id),
    CONSTRAINT fk_recovery_user FOREIGN KEY (admin_user_id) REFERENCES admin_users (id) ON DELETE CASCADE
);

CREATE TABLE auth_sessions
(
    id                           BIGINT       NOT NULL AUTO_INCREMENT,
    version                      BIGINT       NOT NULL DEFAULT 0,
    public_id                    BINARY(16) NOT NULL,
    admin_user_id                BIGINT       NOT NULL,
    current_refresh_hash         VARCHAR(64)  NOT NULL,
    absolute_expires_at          TIMESTAMP(6) NOT NULL,
    last_seen_at                 TIMESTAMP(6) NOT NULL,
    recent_auth_at               TIMESTAMP(6),
    revoked_at                   TIMESTAMP(6),
    user_agent                   VARCHAR(255),
    ip_address                   VARCHAR(64),
    created_at                   TIMESTAMP(6) NOT NULL,
    updated_at                   TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_auth_sessions_public_id (public_id),
    KEY                          idx_auth_sessions_user (admin_user_id),
    CONSTRAINT fk_auth_sessions_user FOREIGN KEY (admin_user_id) REFERENCES admin_users (id) ON DELETE CASCADE
);

CREATE TABLE login_challenges
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    version       BIGINT       NOT NULL DEFAULT 0,
    public_id     BINARY(16) NOT NULL,
    admin_user_id BIGINT       NOT NULL,
    expires_at    TIMESTAMP(6) NOT NULL,
    consumed_at   TIMESTAMP(6),
    setup_required BOOLEAN      NOT NULL DEFAULT FALSE,
    encrypted_pending_totp_secret VARCHAR(512),
    created_at    TIMESTAMP(6) NOT NULL,
    updated_at    TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_login_challenge_public_id (public_id),
    CONSTRAINT fk_login_challenge_user FOREIGN KEY (admin_user_id) REFERENCES admin_users (id) ON DELETE CASCADE
);

CREATE TABLE security_events
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    version       BIGINT       NOT NULL DEFAULT 0,
    admin_user_id BIGINT,
    event_type    VARCHAR(64)  NOT NULL,
    outcome       VARCHAR(16)  NOT NULL,
    ip_address    VARCHAR(64),
    detail        VARCHAR(255),
    created_at    TIMESTAMP(6) NOT NULL,
    updated_at    TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY           idx_security_events_created (created_at)
);

CREATE TABLE refresh_token_grace
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    version         BIGINT       NOT NULL DEFAULT 0,
    auth_session_id BIGINT       NOT NULL,
    token_hash      VARCHAR(64)  NOT NULL,
    valid_until     TIMESTAMP(6) NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    updated_at      TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_refresh_grace_session_hash (auth_session_id, token_hash),
    KEY idx_refresh_grace_valid_until (valid_until),
    CONSTRAINT fk_refresh_grace_session FOREIGN KEY (auth_session_id) REFERENCES auth_sessions (id) ON DELETE CASCADE
);

CREATE TABLE mail_accounts
(
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    version            BIGINT        NOT NULL DEFAULT 0,
    owner_admin_user_id BIGINT       NOT NULL,
    display_name       VARCHAR(120)  NOT NULL,
    display_order      INT           NOT NULL DEFAULT 2147483647,
    email              VARCHAR(190)  NOT NULL,
    imap_host          VARCHAR(255)  NOT NULL,
    imap_port          INT           NOT NULL,
    smtp_host          VARCHAR(255)  NOT NULL,
    smtp_port          INT           NOT NULL,
    username           VARCHAR(190)  NOT NULL,
    encrypted_password VARCHAR(1024) NOT NULL,
    auth_provider      VARCHAR(16)   NOT NULL DEFAULT 'PASSWORD',
    signature_html     TEXT,
    active             BOOLEAN       NOT NULL DEFAULT TRUE,
    last_sync_at       TIMESTAMP(6),
    sync_status        VARCHAR(32),
    sync_error         VARCHAR(500),
    created_at         TIMESTAMP(6)  NOT NULL,
    updated_at         TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_mail_accounts_owner_active (owner_admin_user_id, active, display_name),
    CONSTRAINT fk_mail_account_owner FOREIGN KEY (owner_admin_user_id) REFERENCES admin_users (id) ON DELETE CASCADE
);

CREATE TABLE mail_folders
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    version         BIGINT       NOT NULL DEFAULT 0,
    mail_account_id BIGINT       NOT NULL,
    full_name       VARCHAR(512) NOT NULL,
    display_name    VARCHAR(120) NOT NULL,
    uid_validity    BIGINT       NOT NULL DEFAULT 0,
    last_synced_uid BIGINT       NOT NULL DEFAULT 0,
    unread_count    INT          NOT NULL DEFAULT 0,
    special_use     VARCHAR(16),
    created_at      TIMESTAMP(6) NOT NULL,
    updated_at      TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_folder_account_name (mail_account_id, full_name),
    CONSTRAINT fk_folder_account FOREIGN KEY (mail_account_id) REFERENCES mail_accounts (id) ON DELETE CASCADE
);

CREATE TABLE mail_messages
(
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    version             BIGINT        NOT NULL DEFAULT 0,
    mail_account_id     BIGINT        NOT NULL,
    mail_folder_id      BIGINT        NOT NULL,
    uid_validity        BIGINT        NOT NULL,
    imap_uid            BIGINT        NOT NULL,
    internet_message_id VARCHAR(998),
    gmail_message_id    VARCHAR(32),
    trash_origin_labels VARCHAR(128),
    in_reply_to         VARCHAR(998),
    references_header   VARCHAR(998),
    subject             VARCHAR(1000) NOT NULL,
    from_address        VARCHAR(1000) NOT NULL,
    recipients          TEXT,
    text_body           LONGTEXT,
    sanitized_html      LONGTEXT,
    styled_html         LONGTEXT,
    read_flag           BOOLEAN       NOT NULL DEFAULT FALSE,
    starred_flag        BOOLEAN       NOT NULL DEFAULT FALSE,
    pinned_flag         BOOLEAN       NOT NULL DEFAULT FALSE,
    pinned_at           TIMESTAMP(6),
    trashed_flag        BOOLEAN       NOT NULL DEFAULT FALSE,
    trashed_at          TIMESTAMP(6),
    trash_origin_folder VARCHAR(512),
    has_attachments     BOOLEAN       NOT NULL DEFAULT FALSE,
    sent_at             TIMESTAMP(6),
    received_at         TIMESTAMP(6),
    created_at          TIMESTAMP(6)  NOT NULL,
    updated_at          TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_message_identity (mail_folder_id, uid_validity, imap_uid),
    KEY                 idx_messages_account_received (mail_account_id, received_at),
    KEY                 idx_messages_folder_received (mail_folder_id, received_at),
    KEY idx_messages_trash_pin_order (trashed_flag, pinned_flag, pinned_at, received_at, id),
    KEY idx_messages_trash_retention (trashed_flag, trashed_at),
    KEY idx_mail_messages_gmail_id (mail_account_id, gmail_message_id),
    CONSTRAINT fk_message_account FOREIGN KEY (mail_account_id) REFERENCES mail_accounts (id) ON DELETE CASCADE,
    CONSTRAINT fk_message_folder FOREIGN KEY (mail_folder_id) REFERENCES mail_folders (id) ON DELETE CASCADE
);

CREATE TABLE attachments
(
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    version         BIGINT       NOT NULL DEFAULT 0,
    mail_message_id BIGINT       NOT NULL,
    filename        VARCHAR(512) NOT NULL,
    content_type    VARCHAR(255) NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    storage_path    VARCHAR(512) NOT NULL,
    sha256          VARCHAR(64)  NOT NULL,
    scan_status     VARCHAR(32)  NOT NULL,
    scan_detail     VARCHAR(255),
    created_at      TIMESTAMP(6) NOT NULL,
    updated_at      TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_attachment_message FOREIGN KEY (mail_message_id) REFERENCES mail_messages (id) ON DELETE CASCADE
);

CREATE TABLE outbound_messages
(
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    version             BIGINT        NOT NULL DEFAULT 0,
    public_id           BINARY(16) NOT NULL,
    idempotency_key     VARCHAR(100),
    mail_account_id     BIGINT        NOT NULL,
    created_by_id       BIGINT        NOT NULL,
    source_message_id   BIGINT,
    recipients          TEXT          NOT NULL,
    cc                  TEXT,
    bcc                 TEXT,
    subject             VARCHAR(1000) NOT NULL,
    body_html           MEDIUMTEXT,
    body_text           MEDIUMTEXT,
    in_reply_to         VARCHAR(998),
    references_header   TEXT,
    status              VARCHAR(32)   NOT NULL,
    last_draft_saved_at TIMESTAMP(6),
    trashed_at          TIMESTAMP(6),
    send_started_at     TIMESTAMP(6),
    sent_at             TIMESTAMP(6),
    failure_reason      VARCHAR(500),
    created_at          TIMESTAMP(6)  NOT NULL,
    updated_at          TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_outbound_public_id (public_id),
    UNIQUE KEY uq_outbound_idempotency (idempotency_key),
    KEY                 idx_outbound_status_created (status, created_at),
    KEY idx_outbound_draft_trash (status, trashed_at, last_draft_saved_at),
    CONSTRAINT fk_outbound_account FOREIGN KEY (mail_account_id) REFERENCES mail_accounts (id),
    CONSTRAINT fk_outbound_creator FOREIGN KEY (created_by_id) REFERENCES admin_users (id) ON DELETE CASCADE,
    CONSTRAINT fk_outbound_source FOREIGN KEY (source_message_id) REFERENCES mail_messages (id) ON DELETE SET NULL
);

CREATE TABLE canned_responses
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    version    BIGINT       NOT NULL DEFAULT 0,
    owner_admin_user_id BIGINT NOT NULL,
    title      VARCHAR(120) NOT NULL,
    body_html  MEDIUMTEXT   NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_canned_responses_owner (owner_admin_user_id, title),
    CONSTRAINT fk_canned_response_owner FOREIGN KEY (owner_admin_user_id) REFERENCES admin_users (id) ON DELETE CASCADE
);

CREATE TABLE outbound_attachments
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    version             BIGINT       NOT NULL DEFAULT 0,
    outbound_message_id BIGINT       NOT NULL,
    filename            VARCHAR(512) NOT NULL,
    content_type        VARCHAR(255) NOT NULL,
    size_bytes          BIGINT       NOT NULL,
    storage_path        VARCHAR(512) NOT NULL,
    sha256              VARCHAR(64)  NOT NULL,
    scan_status         VARCHAR(32)  NOT NULL,
    scan_detail         VARCHAR(255),
    created_at          TIMESTAMP(6) NOT NULL,
    updated_at          TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_outbound_attachment_message FOREIGN KEY (outbound_message_id) REFERENCES outbound_messages (id) ON DELETE CASCADE
);

CREATE TABLE saved_contacts
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    version             BIGINT       NOT NULL DEFAULT 0,
    owner_admin_user_id BIGINT       NOT NULL,
    display_name        VARCHAR(120) NOT NULL,
    email               VARCHAR(190) NOT NULL,
    deleted_at          TIMESTAMP(6),
    starred             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP(6) NOT NULL,
    updated_at          TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uq_saved_contact_owner_email (owner_admin_user_id, email),
    CONSTRAINT fk_saved_contact_owner FOREIGN KEY (owner_admin_user_id) REFERENCES admin_users (id) ON DELETE CASCADE
);

CREATE TABLE mail_shares
(
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    version      BIGINT       NOT NULL DEFAULT 0,
    owner_id     BIGINT       NOT NULL,
    viewer_id    BIGINT       NOT NULL,
    hidden       BOOLEAN      NOT NULL DEFAULT FALSE,
    can_view     BOOLEAN      NOT NULL DEFAULT TRUE,
    can_send     BOOLEAN      NOT NULL DEFAULT FALSE,
    can_organize BOOLEAN      NOT NULL DEFAULT FALSE,
    can_delete   BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP(6) NOT NULL,
    updated_at   TIMESTAMP(6) NOT NULL,
    UNIQUE KEY uq_mail_share_owner_viewer (owner_id, viewer_id),
    CONSTRAINT fk_mail_share_owner FOREIGN KEY (owner_id) REFERENCES admin_users (id) ON DELETE CASCADE,
    CONSTRAINT fk_mail_share_viewer FOREIGN KEY (viewer_id) REFERENCES admin_users (id) ON DELETE CASCADE
);

CREATE TABLE mail_share_accounts
(
    share_id     BIGINT  NOT NULL,
    account_id   BIGINT  NOT NULL,
    can_view     BOOLEAN NOT NULL DEFAULT TRUE,
    can_send     BOOLEAN NOT NULL DEFAULT FALSE,
    can_organize BOOLEAN NOT NULL DEFAULT FALSE,
    can_delete   BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (share_id, account_id),
    CONSTRAINT fk_share_accounts_share FOREIGN KEY (share_id) REFERENCES mail_shares (id) ON DELETE CASCADE,
    CONSTRAINT fk_share_accounts_account FOREIGN KEY (account_id) REFERENCES mail_accounts (id) ON DELETE CASCADE
);

CREATE TABLE google_oauth_flows
(
    state_hash              CHAR(64)     NOT NULL PRIMARY KEY,
    owner_user_id           BIGINT       NOT NULL,
    encrypted_code_verifier VARCHAR(512) NOT NULL,
    expires_at              TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_google_oauth_owner FOREIGN KEY (owner_user_id) REFERENCES admin_users (id) ON DELETE CASCADE
);
