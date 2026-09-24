"""Copy a stopped Dispatch site's MySQL data into its empty PostgreSQL schema.

Run this once after PostgreSQL's Flyway V1 has created the target tables. Keep
the MySQL container and its volume until the new application is verified.
Dependencies: PyMySQL and psycopg 3 (with its binary package).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from datetime import datetime, timezone
from uuid import UUID

import pymysql
import psycopg
from psycopg import sql


TABLES = (
    "admin_users", "recovery_codes", "auth_sessions", "login_challenges",
    "security_events", "refresh_token_grace", "mail_accounts", "mail_folders",
    "mail_messages", "attachments", "outbound_messages", "canned_responses",
    "outbound_attachments", "saved_contacts", "mail_shares",
    "mail_share_accounts", "google_oauth_flows",
)


def digest_row(values: tuple[object, ...]) -> bytes:
    def normalized(value: object) -> object:
        if isinstance(value, datetime):
            return value.astimezone(timezone.utc).isoformat(timespec="microseconds")
        if isinstance(value, UUID):
            return str(value)
        if isinstance(value, bytes):
            return value.hex()
        return value

    return json.dumps([normalized(value) for value in values], ensure_ascii=False,
                      separators=(",", ":"), default=str).encode("utf-8")


def run(verify_only: bool) -> None:
    username = os.environ["DATABASE_USERNAME"]
    password = os.environ["DATABASE_PASSWORD"]
    mysql = pymysql.connect(host=os.environ.get("MYSQL_HOST", "mysql"),
                            user=username, password=password, database="dispatch",
                            charset="utf8mb4", autocommit=False)
    postgres = psycopg.connect(host=os.environ.get("POSTGRES_HOST", "postgres"),
                               user=username, password=password, dbname="dispatch")
    try:
        with mysql.cursor() as source:
            source.execute("SET time_zone = '+00:00'")
            source.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT")
        with postgres.transaction():
            with postgres.cursor() as target, mysql.cursor() as source:
                for table in TABLES:
                    source.execute("SELECT column_name FROM information_schema.columns "
                                   "WHERE table_schema = DATABASE() AND table_name = %s "
                                   "ORDER BY ordinal_position", (table,))
                    source_columns = [row[0] for row in source.fetchall()]
                    target.execute("SELECT column_name, data_type FROM information_schema.columns "
                                   "WHERE table_schema = 'public' AND table_name = %s "
                                   "ORDER BY ordinal_position", (table,))
                    target_columns = target.fetchall()
                    if not source_columns or source_columns != [name for name, _ in target_columns]:
                        raise RuntimeError(f"Schema columns differ for {table}")

                    target.execute(sql.SQL("SELECT count(*) FROM {}")
                                   .format(sql.Identifier(table)))
                    target_count = target.fetchone()[0]
                    if not verify_only and target_count:
                        raise RuntimeError(f"Target table {table} is not empty")

                    source.execute("SELECT count(*) FROM `" + table + "`")
                    source_count = source.fetchone()[0]
                    source.execute("SELECT * FROM `" + table + "` ORDER BY " +
                                   ("share_id, account_id" if table == "mail_share_accounts"
                                    else "state_hash" if table == "google_oauth_flows" else "id"))
                    copied = 0
                    source_hash = hashlib.sha256()
                    insert = sql.SQL("INSERT INTO {} ({}) VALUES ({})").format(
                        sql.Identifier(table),
                        sql.SQL(", ").join(map(sql.Identifier, source_columns)),
                        sql.SQL(", ").join(sql.Placeholder() for _ in source_columns),
                    )
                    while batch := source.fetchmany(250):
                        converted = []
                        for row in batch:
                            values = tuple(
                                bool(value) if kind == "boolean" and value is not None else
                                UUID(bytes=value) if kind == "uuid" and value is not None else
                                value.replace(tzinfo=timezone.utc)
                                if kind == "timestamp with time zone" and value is not None else value
                                for value, (_, kind) in zip(row, target_columns)
                            )
                            source_hash.update(digest_row(values))
                            converted.append(values)
                        if not verify_only:
                            target.executemany(insert, converted)
                        copied += len(converted)
                    if copied != source_count:
                        raise RuntimeError(f"Source changed during copy of {table}")

                    target.execute(sql.SQL("SELECT * FROM {} ORDER BY {}").format(
                        sql.Identifier(table),
                        sql.SQL("share_id, account_id") if table == "mail_share_accounts"
                        else sql.Identifier("state_hash") if table == "google_oauth_flows"
                        else sql.Identifier("id"),
                    ))
                    target_hash = hashlib.sha256()
                    checked = 0
                    while batch := target.fetchmany(250):
                        for row in batch:
                            target_hash.update(digest_row(row))
                            checked += 1
                    if checked != source_count or source_hash.digest() != target_hash.digest():
                        raise RuntimeError(f"Row verification failed for {table}")

                    if not verify_only and "id" in source_columns:
                        target.execute(sql.SQL(
                            "SELECT setval(pg_get_serial_sequence(%s, 'id'), "
                            "GREATEST(COALESCE(MAX(id), 0), 1), MAX(id) IS NOT NULL) FROM {}"
                        ).format(sql.Identifier(table)), (table,))
                    print(f"{table}: {checked} rows verified")
        mysql.rollback()
    finally:
        postgres.close()
        mysql.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true")
    run(parser.parse_args().verify_only)
