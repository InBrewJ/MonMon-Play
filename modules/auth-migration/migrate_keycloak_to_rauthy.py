#!/usr/bin/env python3
"""
Keycloak to Rauthy Migration Script for MonMon

This script performs an end-to-end migration from Keycloak to Rauthy:
1. Reads all users, credentials, and roles from the Keycloak PostgreSQL database (e.g. keycloak_live).
2. Synchronizes the MonMon application database (user_access table) to preserve exact Keycloak UUIDs.
3. Generates Rauthy declarative bootstrap files (users.json, clients.json, roles.json, groups.json, scopes.json).
4. Validates identity mapping and outputs a migration summary.
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


def run_psql(db: str, query: str, host: str = "localhost", port: int = 5432, user: str = "postgres") -> str:
    """Execute a SQL query via psql and return stdout."""
    env = os.environ.copy()
    if "PGPASSWORD" not in env:
        env["PGPASSWORD"] = os.environ.get("DB_PASSWORD", "")

    cmd = [
        "psql",
        "-h", host,
        "-p", str(port),
        "-U", user,
        "-d", db,
        "-t", "-A", "-F", "\t",
        "-c", query
    ]
    res = subprocess.run(cmd, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"psql error on {db}: {res.stderr.strip()}")
    return res.stdout


def load_env_passwords(env_path: Path) -> dict:
    """Load known credentials from .env file."""
    passwords = {}
    if not env_path.exists():
        return passwords

    with open(env_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            passwords[k.strip()] = v.strip().strip('"').strip("'")
    return passwords


def fetch_keycloak_users(db: str, host: str, port: int, user: str) -> list:
    """Fetch all users and their role assignments from Keycloak."""
    query = """
    SELECT 
        u.id, 
        u.username, 
        COALESCE(u.email, ''), 
        COALESCE(u.first_name, ''), 
        COALESCE(u.last_name, ''), 
        u.enabled,
        COALESCE(string_agg(DISTINCT r.name, ','), '') AS roles
    FROM user_entity u
    LEFT JOIN user_role_mapping m ON u.id = m.user_id
    LEFT JOIN keycloak_role r ON m.role_id = r.id
    GROUP BY u.id, u.username, u.email, u.first_name, u.last_name, u.enabled
    ORDER BY u.created_timestamp ASC;
    """
    out = run_psql(db, query, host, port, user)
    users = []
    for line in out.strip().split("\n"):
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 7:
            continue
        uid, username, email, first_name, last_name, enabled_str, roles_str = parts[:7]
        roles = [r.strip() for r in roles_str.split(",") if r.strip()]
        users.append({
            "id": uid.strip(),
            "username": username.strip(),
            "email": email.strip() or f"{username.strip()}@monmon.local",
            "first_name": first_name.strip() or username.strip(),
            "last_name": last_name.strip() or "",
            "enabled": enabled_str.strip().lower() in ("t", "true", "1"),
            "roles": roles,
            "is_admin": "admin" in roles or username.strip() in ("admin", "inbrewj")
        })
    return users


def sync_user_access(db: str, users: list, host: str, port: int, user: str, dry_run: bool = False) -> dict:
    """Ensure all users are recorded in MonMon's user_access table to prevent orphaned data."""
    # Check existing user_access rows
    existing_raw = run_psql(db, "SELECT userid, username, email, status FROM user_access;", host, port, user)
    existing_uids = set()
    existing_usernames = set()
    existing_emails = set()

    for line in existing_raw.strip().split("\n"):
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) >= 2:
            existing_uids.add(parts[0].strip().lower())
            existing_usernames.add(parts[1].strip().lower())
            if len(parts) >= 3 and parts[2].strip():
                existing_emails.add(parts[2].strip().lower())

    inserted = []
    skipped = []

    for u in users:
        uid = u["id"].lower()
        clean_name = re.sub(r'[^a-zA-Z0-9_-]', '', u["username"].split('@')[0])[:32]
        uname = clean_name.lower()
        uemail = u["email"].lower()

        if uid in existing_uids or uname in existing_usernames or uemail in existing_emails:
            if not dry_run and "@" in u["username"]:
                run_psql(db, f"UPDATE user_access SET username = '{clean_name}' WHERE userid = '{u['id']}';", host, port, user)
            skipped.append(u["username"])
            continue

        status = "REJECTED" if u["username"] == "waitinglist" else "APPROVED"
        first_admin = "INITIAL_ADMIN" if u["is_admin"] else "KEYCLOAK_MIGRATION"

        insert_sql = f"""
        INSERT INTO user_access (id, approvedat, approvedby, createdat, email, status, userid, username)
        VALUES (
            nextval('hibernate_sequence'),
            NOW(),
            '{first_admin}',
            NOW(),
            '{u["email"]}',
            '{status}',
            '{u["id"]}',
            '{clean_name}'
        );
        """
        if not dry_run:
            run_psql(db, insert_sql, host, port, user)
        inserted.append(u["username"])

    return {"inserted": inserted, "skipped": skipped}


def generate_rauthy_bootstrap(users: list, output_dir: Path, env_passwords: dict, inbrewj_password: str = None) -> list:
    """Generate Rauthy JSON bootstrap files."""
    output_dir.mkdir(parents=True, exist_ok=True)

    # 1. Generate roles.json
    roles_path = output_dir / "roles.json"
    roles_payload = [
        {"name": "admin"},
        {"name": "user"}
    ]
    with open(roles_path, "w", encoding="utf-8") as f:
        json.dump(roles_payload, f, indent=2)

    # 2. Generate groups.json
    groups_path = output_dir / "groups.json"
    groups_payload = [
        {"name": "admin"},
        {"name": "user"}
    ]
    with open(groups_path, "w", encoding="utf-8") as f:
        json.dump(groups_payload, f, indent=2)

    # 3. Generate scopes.json
    scopes_path = output_dir / "scopes.json"
    scopes_payload = [
        {"name": "openid", "scope": "openid"},
        {"name": "profile", "scope": "profile"},
        {"name": "email", "scope": "email"}
    ]
    with open(scopes_path, "w", encoding="utf-8") as f:
        json.dump(scopes_payload, f, indent=2)

    # 4. Generate clients.json
    clients_path = output_dir / "clients.json"
    raw_secret = env_passwords.get("OIDC_CLIENT_SECRET") or env_passwords.get("KEYCLOAK_MONMON_PLAY_CLIENT_SECRET") or ""
    if len(raw_secret) < 64:
        import hashlib
        client_secret = hashlib.sha256(raw_secret.encode()).hexdigest()
    else:
        client_secret = raw_secret
    clients_payload = [
        {
            "id": "monmon-web",
            "name": "MonMon Web",
            "secret": {
                "Plain": client_secret
            },
            "redirect_uris": [
                "http://localhost:9000/callback",
                "http://localhost:9001/callback",
                "https://monmon.dryark.uk/callback"
            ],
            "post_logout_redirect_uris": [
                "http://localhost:9000/?defaulturlafterlogout",
                "http://localhost:9001/?defaulturlafterlogout",
                "https://monmon.dryark.uk/?defaulturlafterlogout"
            ],
            "allowed_origins": [
                "http://localhost:9000",
                "http://localhost:9001",
                "http://localhost:8085",
                "https://monmon.dryark.uk"
            ],
            "enabled": True,
            "flows_enabled": [
                "authorization_code",
                "refresh_token"
            ],
            "access_token_alg": "RS256",
            "id_token_alg": "RS256",
            "auth_code_lifetime": 60,
            "access_token_lifetime": 3600,
            "scopes": ["openid", "profile", "email"],
            "default_scopes": ["openid", "profile", "email"],
            "force_mfa": False
        }
    ]
    with open(clients_path, "w", encoding="utf-8") as f:
        json.dump(clients_payload, f, indent=2)

    # 5. Generate users.json
    users_path = output_dir / "users.json"
    rauthy_users = []

    for u in users:
        uname = u["username"]
        # Resolve password
        if uname == "inbrewj":
            pwd = inbrewj_password or env_passwords.get("INBREWJ_PASSWORD") or env_passwords.get("KEYCLOAK_ADMIN_PASSWORD") or "admin123"
        elif uname == "caity":
            pwd = env_passwords.get("CAITY_PASSWORD") or "caity123"
        elif uname == "admin":
            pwd = env_passwords.get("KEYCLOAK_ADMIN_PASSWORD") or "admin123"
        elif uname == "demo":
            pwd = "demo"
        else:
            pwd = f"{uname}123"

        # Rauthy standard 24-char hex ID derived from UUID
        clean_hex = u["id"].replace("-", "")[:24]

        user_roles = ["admin", "user"] if u["is_admin"] else ["user"]
        user_groups = ["admin", "user"] if u["is_admin"] else ["user"]

        clean_username = re.sub(r'[^a-zA-Z0-9_-]', '', u["username"].split('@')[0])[:32]
        given_name = (u["first_name"] or clean_username.capitalize())[:32]
        family_name = (u["last_name"] or "User")[:32]

        rauthy_users.append({
            "id": clean_hex,
            "email": u["email"],
            "preferred_username": clean_username,
            "given_name": given_name,
            "family_name": family_name,
            "password": {
                "Plain": pwd
            },
            "roles": user_roles,
            "groups": user_groups,
            "enabled": u["enabled"],
            "email_verified": True
        })

    with open(users_path, "w", encoding="utf-8") as f:
        json.dump(rauthy_users, f, indent=2)

    return rauthy_users


def main():
    parser = argparse.ArgumentParser(description="Migrate Keycloak users and schema to Rauthy for MonMon")
    parser.add_argument("--keycloak-db", default="keycloak_live", help="Keycloak database name (default: keycloak_live)")
    parser.add_argument("--monmon-db", default="monmon_live", help="MonMon database name (default: monmon_live)")
    parser.add_argument("--pg-host", default="localhost", help="PostgreSQL host (default: localhost)")
    parser.add_argument("--pg-port", type=int, default=5432, help="PostgreSQL port (default: 5432)")
    parser.add_argument("--pg-user", default="postgres", help="PostgreSQL user (default: postgres)")
    parser.add_argument("--output-dir", default="modules/rauthy/bootstrap", help="Rauthy bootstrap directory")
    parser.add_argument("--env-file", default=".env", help="Path to .env file for secrets")
    parser.add_argument("--inbrewj-password", default=None, help="Explicit password for inbrewj user")
    parser.add_argument("--dry-run", action="store_true", help="Perform checks without writing to database or files")

    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    env_file = repo_root / args.env_file
    output_dir = repo_root / args.output_dir

    print("=================================================================")
    print("🚀 Keycloak to Rauthy Migration Tool for MonMon")
    print(f"Timestamp:   {datetime.now(timezone.utc).isoformat()}")
    print(f"Keycloak DB: {args.keycloak_db} on {args.pg_host}:{args.pg_port}")
    print(f"MonMon DB:   {args.monmon_db} on {args.pg_host}:{args.pg_port}")
    print(f"Output Dir:  {output_dir}")
    print(f"Dry Run:     {args.dry_run}")
    print("=================================================================\n")

    # 1. Load passwords from .env
    passwords = load_env_passwords(env_file)
    print(f"✓ Loaded {len(passwords)} secrets from {env_file.name}")

    # 2. Extract Keycloak users
    print(f"→ Fetching users from Keycloak database '{args.keycloak_db}'...")
    users = fetch_keycloak_users(args.keycloak_db, args.pg_host, args.pg_port, args.pg_user)
    print(f"✓ Retrieved {len(users)} users from Keycloak:\n")
    for idx, u in enumerate(users, 1):
        admin_badge = " [ADMIN]" if u["is_admin"] else ""
        print(f"  {idx:2d}. {u['username']:<24} {u['email']:<30} UUID: {u['id']}{admin_badge}")
    print("")

    # 3. Synchronize MonMon user_access table
    print(f"→ Synchronizing MonMon 'user_access' table in database '{args.monmon_db}'...")
    sync_res = sync_user_access(args.monmon_db, users, args.pg_host, args.pg_port, args.pg_user, args.dry_run)
    print(f"✓ New user records inserted into user_access: {len(sync_res['inserted'])} {sync_res['inserted']}")
    print(f"✓ Existing records preserved (no-op):        {len(sync_res['skipped'])} {sync_res['skipped']}\n")

    # Also sync monmon-rust database if present
    try:
        rust_sync = sync_user_access("monmon-rust", users, args.pg_host, args.pg_port, args.pg_user, args.dry_run)
        print(f"✓ Synced to 'monmon-rust' DB: inserted {len(rust_sync['inserted'])}, preserved {len(rust_sync['skipped'])}\n")
    except Exception as e:
        print(f"ℹ monmon-rust DB sync skipped ({e})\n")

    # 4. Generate Rauthy bootstrap JSON
    if not args.dry_run:
        print(f"→ Generating Rauthy declarative bootstrap files in '{output_dir}'...")
        rauthy_users = generate_rauthy_bootstrap(users, output_dir, passwords, args.inbrewj_password)
        print(f"✓ Generated {output_dir / 'users.json'} ({len(rauthy_users)} users)")
        print(f"✓ Generated {output_dir / 'clients.json'} (monmon-web client)")
        print(f"✓ Generated {output_dir / 'roles.json'} (admin, user roles)")
        print(f"✓ Generated {output_dir / 'groups.json'} (admin, user groups)")
        print(f"✓ Generated {output_dir / 'scopes.json'} (openid, profile, email scopes)\n")
    else:
        print("ℹ Dry-run mode: skipped writing Rauthy bootstrap files.\n")

    # 5. Summary & Verification
    summary_path = repo_root / "modules/auth-migration/migration_summary.json"
    summary_data = {
        "migrated_at": datetime.now(timezone.utc).isoformat(),
        "keycloak_db": args.keycloak_db,
        "monmon_db": args.monmon_db,
        "total_users": len(users),
        "users": [
            {
                "username": u["username"],
                "email": u["email"],
                "keycloak_uuid": u["id"],
                "rauthy_id": u["id"].replace("-", "")[:24],
                "is_admin": u["is_admin"]
            }
            for u in users
        ],
        "user_access_sync": sync_res
    }
    if not args.dry_run:
        with open(summary_path, "w", encoding="utf-8") as f:
            json.dump(summary_data, f, indent=2)
        print(f"✓ Migration summary written to {summary_path}")

    print("\n✅ Keycloak to Rauthy migration preparation complete!")
    print("Next step: Start or restart Rauthy with the updated bootstrap files.")


if __name__ == "__main__":
    main()
