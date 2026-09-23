# Shared VPS ingress for Dispatch

`mail.despical.dev` is already an A record for `45.141.150.252` with Cloudflare
**DNS only**. It is also the MX target for `despical.dev`. Keep it DNS only so
SMTP and IMAP continue to reach the mail server. The VPS already has a TikFetch
Nginx container listening on ports 80 and 443; do not start a second public
Nginx container.

When deploying:

1. Put the Dispatch checkout at `/opt/dispatch`. Set production values in its
   ignored `.env`; keep `APP_PORT=8080`, set
   `GOOGLE_OAUTH_REDIRECT_URI=https://mail.despical.dev/oauth/google/callback`,
   and provide distinct secrets. Use
   `docker compose -f docker-compose.yml -f docker-compose.vps.yml up -d --build`
   from that directory. The override joins the existing `plugins-hub-edge`
   network and gives the app the DNS alias `dispatch-app`. The host port is
   limited to `127.0.0.1:8082` by default.
2. In `/opt/tikfetch/infrastructure/docker-compose.override.yml`, replace the
   existing `/opt/mailserver/acme-nginx.conf:/etc/nginx/conf.d/acme-mail.conf:ro`
   bind mount with
   `/opt/dispatch/docker/nginx/mail.conf:/etc/nginx/conf.d/acme-mail.conf:ro`.
   Add `/etc/letsencrypt:/etc/letsencrypt:ro` to the same Nginx service's
   volumes. Keep `/var/lib/acme-webroot:/var/www/acme:ro` and the
   `plugins-hub-edge` network. The full Let's Encrypt directory is needed
   because the live certificate files are symlinks into its archive directory.
3. Recreate the shared Nginx container using the TikFetch compose files, then
   run `nginx -t` inside it and verify the HTTPS page and ACME challenge path.
   Install `docker/nginx/reload-on-renewal.sh` as an executable file in
   `/etc/letsencrypt/renewal-hooks/deploy/` so Nginx reloads renewed certificates.

The mail hostname remains DNS only in Cloudflare because it is the MX target.
