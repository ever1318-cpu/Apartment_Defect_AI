"""Prompt for and reset the local postgres role password without logging it."""

from __future__ import annotations

import getpass

from psycopg import connect, sql


def main() -> None:
    first = getpass.getpass("New local postgres password: ")
    second = getpass.getpass("Confirm new local postgres password: ")
    if not first:
        raise SystemExit("Password must not be empty.")
    if first != second:
        raise SystemExit("Passwords do not match.")
    if len(first) < 12:
        raise SystemExit("Use at least 12 characters.")

    with connect(
        host="127.0.0.1",
        port=5432,
        dbname="postgres",
        user="postgres",
        sslmode="disable",
        connect_timeout=10,
    ) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                sql.SQL("ALTER ROLE {} PASSWORD {}").format(
                    sql.Identifier("postgres"),
                    sql.Literal(first),
                )
            )
    print("Local postgres password reset succeeded.")


if __name__ == "__main__":
    main()
