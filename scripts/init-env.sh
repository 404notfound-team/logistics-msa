#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
if [[ ! -e .env ]]; then
    (
        set -o noclobber
        {
            for key in POSTGRES_PASSWORD USERS_DB_PASSWORD COMPANY_DB_PASSWORD HUB_DB_PASSWORD ORDERS_DB_PASSWORD DELIVERY_DB_PASSWORD; do
                printf '%s=%s\n' "$key" "$(openssl rand -hex 24)"
            done
            printf 'GATEWAY_PORT=8080\nEUREKA_PORT=8761\nZIPKIN_PORT=9411\n'
        } > .env
    )
fi
# 기존 DB 비밀번호와 지정한 키는 보존하고, 비어 있거나 없는 개발 인증 키만 준비한다.
python3 - <<'PY'
import re
import secrets
from pathlib import Path

path = Path('.env')
text = path.read_text()
for key in ['JWT_SECRET', 'GATEWAY_SERVICE_TOKEN', 'USERS_SERVICE_TOKEN', 'ORDERS_SERVICE_TOKEN']:
    pattern = re.compile(r'^' + key + r'=(.*)$', re.MULTILINE)
    match = pattern.search(text)
    if match and match.group(1).strip() not in ['', '""', "''"]:
        continue
    line = key + '=' + secrets.token_hex(24)
    if match:
        text = text[:match.start()] + line + text[match.end():]
    else:
        text = text.rstrip('\n') + '\n' + line + '\n'
path.write_text(text)
PY
printf '로컬 .env를 준비했습니다. 기존 DB 비밀번호와 설정된 인증 키는 보존합니다.\n'
