#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
set -o noclobber
{
    for key in POSTGRES_PASSWORD USERS_DB_PASSWORD COMPANY_DB_PASSWORD HUB_DB_PASSWORD ORDERS_DB_PASSWORD DELIVERY_DB_PASSWORD JWT_SECRET; do
        printf '%s=%s\n' "$key" "$(openssl rand -hex 24)"
    done
    printf 'GATEWAY_PORT=8080\nEUREKA_PORT=8761\nZIPKIN_PORT=9411\n'
} > .env
printf '로컬 전용 .env를 만들었습니다. 기존 파일은 덮어쓰지 않습니다.\n'
