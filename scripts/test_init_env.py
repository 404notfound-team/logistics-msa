#!/usr/bin/env python3
"""실제 Compose dotenv 파서로 기존 키 보존과 빈 값 보충을 검증한다."""
import importlib.util
import os
import stat
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("init_env", Path(__file__).with_name("init-env.py"))
init_env = importlib.util.module_from_spec(spec)
spec.loader.exec_module(init_env)


class InitEnvTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / ".env"
        self.environment = patch.dict(os.environ, {}, clear=True)
        # Docker CLI 경로만 이어받고 실제 인증값은 테스트 환경에 전달하지 않는다.
        original = {key: os.environ[key] for key in ["PATH", "HOME"] if key in os.environ}
        self.environment.start()
        os.environ.update(original)
        self.addCleanup(self.environment.stop)

    def test_fresh_file_is_private_distinct_and_repeatable(self):
        init_env.initialize(self.path)
        first = self.path.read_bytes()
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        values = init_env.configured_values(self.path)
        self.assertEqual(len(set(values.values())), len(values))
        self.assertTrue(all(values.values()))
        init_env.initialize(self.path)
        self.assertEqual(self.path.read_bytes(), first)

    def test_valid_existing_formats_are_preserved_byte_for_byte(self):
        formats = ["export {key}='{value}'", " {key} = \"{value}\" # keep", "{key}={value}"]
        for format_ in formats:
            with self.subTest(format_=format_):
                original = "# keep my settings\nCOMPANY_DB_PASSWORD=unchanged\n"
                original += "".join(format_.format(key=k, value="test-existing-" + k) + "\n" for k in init_env.AUTH_KEYS)
                self.path.write_text(original)
                init_env.initialize(self.path)
                self.assertEqual(self.path.read_text(), original)

    def test_empty_values_and_missing_keys_are_filled_without_changing_db_password(self):
        original = "COMPANY_DB_PASSWORD='db-secret-test-only'\nJWT_SECRET=\nexport GATEWAY_SERVICE_TOKEN = '' # empty\n"
        self.path.write_text(original)
        init_env.initialize(self.path)
        updated = self.path.read_text()
        self.assertTrue(updated.startswith(original))
        self.assertTrue(all(init_env.configured_values(self.path).values()))
        init_env.initialize(self.path)
        self.assertEqual(self.path.read_text(), updated)

    def test_last_duplicate_and_interpolation_follow_compose(self):
        self.path.write_text("SOURCE=test-existing-jwt\nJWT_SECRET=${SOURCE}\nJWT_SECRET=\n")
        init_env.initialize(self.path)
        values = init_env.configured_values(self.path)
        self.assertTrue(values["JWT_SECRET"])
        self.path.write_text("SOURCE=test-existing-jwt\nJWT_SECRET=${SOURCE}\n")
        init_env.initialize(self.path)
        self.assertEqual(init_env.configured_values(self.path)["JWT_SECRET"], "test-existing-jwt")

    def test_symlink_is_retained(self):
        target = self.path.with_name("private.env")
        target.write_text("COMPANY_DB_PASSWORD=test-only\n")
        self.path.symlink_to(target)
        init_env.initialize(self.path)
        self.assertTrue(self.path.is_symlink())
        self.assertTrue(all(init_env.configured_values(self.path).values()))

    def test_malformed_file_is_not_changed(self):
        original = 'JWT_SECRET="unterminated\n'
        self.path.write_text(original)
        with self.assertRaises(SystemExit):
            init_env.initialize(self.path)
        self.assertEqual(self.path.read_text(), original)
        self.assertEqual(list(self.path.parent.glob(".env.tmp-*")), [])


if __name__ == "__main__":
    unittest.main()
