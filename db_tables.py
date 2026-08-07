import os
import psycopg

print("1. 프로그램 시작")

conn = psycopg.connect(
    host=os.environ["APARTMENT_DB_HOST"],
    port=os.environ.get("APARTMENT_DB_PORT", "5432"),
    dbname=os.environ["APARTMENT_DB_NAME"],
    user=os.environ["APARTMENT_DB_USER"],
    password=os.environ["APARTMENT_DB_PASSWORD"],
    sslmode=os.environ.get("APARTMENT_DB_SSLMODE", "require"),
)

print("2. DB 연결 성공")

with conn.cursor() as cur:
    cur.execute("""
        SELECT table_schema, table_name
        FROM information_schema.tables
        ORDER BY table_schema, table_name
    """)
    rows = cur.fetchall()

print(f"3. 조회 완료: {len(rows)}개")

for schema, table in rows[:20]:
    print(f"{schema}.{table}")

conn.close()
print("4. 종료")