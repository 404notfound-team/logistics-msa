#!/usr/bin/env python3
"""기존 설정을 보존하며 로컬 Compose의 누락된 인증값만 준비한다."""
import argparse
import json
import os
import secrets
import stat
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DB_KEYS = ["POSTGRES_PASSWORD", "USERS_DB_PASSWORD", "COMPANY_DB_PASSWORD", "HUB_DB_PASSWORD",
           "ORDERS_DB_PASSWORD", "DELIVERY_DB_PASSWORD"]
AUTH_KEYS = ["JWT_SECRET", "GATEWAY_SERVICE_TOKEN", "USERS_SERVICE_TOKEN", "ORDERS_SERVICE_TOKEN",
             "HUB_SERVICE_TOKEN", "COMPANY_SERVICE_TOKEN", "DELIVERY_SERVICE_TOKEN", "DEV_DATA_PASSWORD"]


def new_value(key):
    return "Aa1!" + secrets.token_hex(5) if key == "DEV_DATA_PASSWORD" else secrets.token_hex(24)


def configured_values(path):
    # Compose 자체 파서가 export·공백·인용·중복 선언·변수 참조를 실제 기동과 같이 해석한다.
    probe = {"services": {"env-check": {"image": "scratch", "environment": {
        key: "${" + key + "-}" for key in AUTH_KEYS}}}}
    try:
        result = subprocess.run(
            ["docker", "compose", "--env-file", str(path), "-p", "logistics-env-check",
             "-f", "-", "config", "--format", "json"],
            input=json.dumps(probe), text=True, capture_output=True, check=True, cwd=path.parent)
    except (OSError, subprocess.CalledProcessError):
        raise SystemExit("Compose가 .env를 읽지 못했습니다. Docker Compose 설치와 .env 형식을 확인하세요. 기존 파일은 유지했습니다.") from None
    return json.loads(result.stdout)["services"]["env-check"]["environment"]


def initialize(path):
    path = path.absolute()
    if not path.exists():
        text = "".join(key + "=" + new_value(key) + "\n" for key in DB_KEYS + AUTH_KEYS)
        text += "GATEWAY_PORT=8080\nEUREKA_PORT=8761\nZIPKIN_PORT=9411\n"
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as stream:
            stream.write(text)
        return
    target = path.resolve(strict=True)
    text = target.read_text()
    values = configured_values(path)
    missing = [key for key in AUTH_KEYS if not values.get(key)]
    if not missing:
        return
    # 실효 빈 값 뒤에 새 선언을 추가한다. 이미 쓰는 값과 다른 설정의 원문은 보존한다.
    updated = text + ("\n" if text and not text.endswith("\n") else "")
    updated += "".join(key + "=" + new_value(key) + "\n" for key in missing)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", dir=target.parent, prefix=".env.tmp-", delete=False) as stream:
            temporary = Path(stream.name)
            os.chmod(temporary, stat.S_IMODE(target.stat().st_mode))
            stream.write(updated)
        if target.read_text() != text:
            raise SystemExit("검사 도중 .env가 변경되어 중단했습니다. 다시 실행하세요.")
        os.replace(temporary, target)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=ROOT / ".env")
    args = parser.parse_args()
    initialize(args.env_file)
    print("로컬 .env를 준비했습니다. 기존 DB 비밀번호와 설정된 인증값은 보존합니다.")


if __name__ == "__main__":
    main()
