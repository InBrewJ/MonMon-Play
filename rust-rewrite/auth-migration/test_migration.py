#!/usr/bin/env python3
"""
Unit and integration test for Keycloak to Rauthy migration script.
"""

import json
import os
import subprocess
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MIGRATION_SCRIPT = REPO_ROOT / "rust-rewrite/auth-migration/migrate_keycloak_to_rauthy.py"
SUMMARY_FILE = REPO_ROOT / "rust-rewrite/auth-migration/migration_summary.json"
BOOTSTRAP_DIR = REPO_ROOT / "rust-rewrite/rauthy/bootstrap"


class TestKeycloakToRauthyMigration(unittest.TestCase):
    def test_dry_run_execution(self):
        """Verify dry-run completes successfully without throwing errors."""
        res = subprocess.run(
            ["python3", str(MIGRATION_SCRIPT), "--dry-run"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            cwd=str(REPO_ROOT),
        )
        self.assertEqual(res.returncode, 0, f"Dry-run failed: {res.stderr}")
        self.assertIn("Keycloak to Rauthy Migration Tool", res.stdout)
        self.assertIn("Retrieved 16 users from Keycloak", res.stdout)

    def test_migration_summary_integrity(self):
        """Verify migration summary contains expected user records and UUIDs."""
        self.assertTrue(SUMMARY_FILE.exists(), "migration_summary.json missing")
        with open(SUMMARY_FILE, "r", encoding="utf-8") as f:
            summary = json.load(f)

        self.assertEqual(summary["total_users"], 16)
        self.assertEqual(summary["keycloak_db"], "keycloak_live")
        self.assertEqual(summary["monmon_db"], "monmon_live")

        usernames = {u["username"]: u for u in summary["users"]}
        self.assertIn("inbrewj", usernames)
        self.assertIn("admin", usernames)
        self.assertIn("demo", usernames)
        self.assertIn("caity", usernames)

        # Confirm exact UUID mapping for inbrewj
        self.assertEqual(usernames["inbrewj"]["keycloak_uuid"], "2ccc203f-a98c-488f-a5ba-8d20a384a9fc")
        self.assertEqual(usernames["inbrewj"]["rauthy_id"], "2ccc203fa98c488fa5ba8d20")
        self.assertTrue(usernames["inbrewj"]["is_admin"])

    def test_rauthy_bootstrap_files(self):
        """Verify bootstrap files generated for Rauthy are valid JSON."""
        required_files = ["users.json", "clients.json", "roles.json", "groups.json", "scopes.json"]
        for fname in required_files:
            file_path = BOOTSTRAP_DIR / fname
            self.assertTrue(file_path.exists(), f"Bootstrap file {fname} not found")
            with open(file_path, "r", encoding="utf-8") as f:
                data = json.load(f)
                self.assertTrue(len(data) > 0, f"{fname} is empty")

        # Verify users.json structure
        with open(BOOTSTRAP_DIR / "users.json", "r", encoding="utf-8") as f:
            users = json.load(f)
        self.assertEqual(len(users), 16)
        inbrewj_user = next(u for u in users if u["preferred_username"] == "inbrewj")
        self.assertEqual(inbrewj_user["email"], "jason@urawizard.com")
        self.assertEqual(inbrewj_user["id"], "2ccc203fa98c488fa5ba8d20")
        self.assertIn("admin", inbrewj_user["roles"])


if __name__ == "__main__":
    unittest.main()
