#!/bin/sh
set -eu

if /usr/bin/docker ps --filter name='^/tikfetch-nginx-1$' --format '{{.Names}}' |
    grep -qx 'tikfetch-nginx-1'; then
    /usr/bin/docker exec tikfetch-nginx-1 nginx -s reload
fi
