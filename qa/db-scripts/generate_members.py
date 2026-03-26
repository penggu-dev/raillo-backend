"""
Member 테스트 데이터 생성 스크립트

사용법:
    pip install pymysql bcrypt faker (패키지 설치 필요)
    python qa/db-scripts/generate_members.py

옵션:
    --total   생성할 회원 수 (기본값: 10000)
    --batch   배치 사이즈 (기본값: 500)
    --host    DB 호스트 (기본값: localhost)
    --port    DB 포트 (기본값: 3306)
    --db      DB 이름 (기본값: raillo)
    --user    DB 유저 (기본값: sa)
    --password DB 비밀번호 (기본값: secret)
"""

import argparse
import os
import sys
from datetime import date, timedelta
from random import randint, choice

import bcrypt
import pymysql
from faker import Faker

# ────────────────────────────────────────────────
# 설정
# ────────────────────────────────────────────────

BASE_DATE = date(2026, 3, 3)   # member_no 기준일
MAX_PER_DAY = 9999             # member_no yyyyMMddCCCC → 하루 최대 9999
TEST_PASSWORD = "Test1234!"    # 모든 테스트 계정 공통 비밀번호

GENDERS = ["M", "W"]
BIRTH_START = date(1970, 1, 1)
BIRTH_END = date(2005, 12, 31)
BIRTH_RANGE_DAYS = (BIRTH_END - BIRTH_START).days

fake = Faker("ko_KR")


# ────────────────────────────────────────────────
# 데이터 생성 함수
# ────────────────────────────────────────────────

def hash_password(raw: str) -> str:
    """BCrypt 해시 (Spring Security 호환 $2a$ prefix)"""
    hashed = bcrypt.hashpw(raw.encode("utf-8"), bcrypt.gensalt(rounds=10))
    return hashed.decode("utf-8")


def generate_phone(i: int) -> str:
    """순차 생성으로 중복 없는 전화번호: 01000000000 ~ 01000009999"""
    prefix = i // 10000
    suffix = i % 10000
    return f"010{prefix:04d}{suffix:04d}"


def generate_member_no(i: int) -> str:
    """
    yyyyMMddCCCC 포맷, 하루 9999명 초과 시 날짜를 하루씩 앞당김
    ex) 0~9998 → 20260303_0001~9999
        9999    → 20260302_0001
    """
    day_offset = i // MAX_PER_DAY
    counter = (i % MAX_PER_DAY) + 1
    target_date = BASE_DATE - timedelta(days=day_offset)
    return f"{target_date.strftime('%Y%m%d')}{counter:04d}"


def generate_email(i: int) -> str:
    return f"user{i + 1:05d}@raillo.com"


def generate_birth_date() -> date:
    return BIRTH_START + timedelta(days=randint(0, BIRTH_RANGE_DAYS))


# ────────────────────────────────────────────────
# 메인
# ────────────────────────────────────────────────

def cleanup(conn):
    """
    테스트 멤버(user%@raillo.com) 삭제
    Member → Order CASCADE 설정이 있으므로 연관 데이터가 남아있다면
    generate_seat_conflict_data.py --cleanup 을 먼저 실행
    """
    cursor = conn.cursor()
    print("[정리 시작]")

    cursor.execute("DELETE FROM member WHERE email LIKE 'user%@raillo.com'")
    conn.commit()
    print(f"  Member 삭제 : {cursor.rowcount:,}건")

    cursor.close()
    print("[정리 완료]")


def main():
    parser = argparse.ArgumentParser(description="Member 테스트 데이터 생성")
    parser.add_argument("--total",    type=int, default=10_000)
    parser.add_argument("--batch",    type=int, default=500)
    parser.add_argument("--host",     default=os.environ.get("DB_HOST", "localhost"))
    parser.add_argument("--port",     type=int, default=int(os.environ.get("DB_PORT", "3306")))
    parser.add_argument("--db",       default=os.environ.get("DB_NAME", "raillo"))
    parser.add_argument("--user",     default=os.environ.get("DB_USER", "sa"))
    parser.add_argument("--password", default=os.environ.get("DB_PASSWORD", "secret"))
    parser.add_argument("--cleanup",  action="store_true", help="테스트 멤버 삭제 (연관 데이터 포함 CASCADE)")
    args = parser.parse_args()

    try:
        conn = pymysql.connect(
            host=args.host,
            port=args.port,
            db=args.db,
            user=args.user,
            password=args.password,
            charset="utf8mb4",
        )
    except pymysql.Error as e:
        print(f"[오류] DB 연결 실패: {e}", file=sys.stderr)
        sys.exit(1)

    if args.cleanup:
        cleanup(conn)
        conn.close()
        return

    print(f"[설정] 총 {args.total:,}명 / 배치 {args.batch}개")
    print("[비밀번호 해싱 중...]")
    hashed_pw = hash_password(TEST_PASSWORD)
    print(f"[완료] 해시: {hashed_pw[:30]}...")

    cursor = conn.cursor()

    sql = """
        INSERT INTO member
            (name, password, phone_number, role,
             member_no, email, birth_date, gender,
             is_deleted, created_at, updated_at)
        VALUES
            (%s, %s, %s, %s,
             %s, %s, %s, %s,
             %s, NOW(), NOW())
    """

    batch = []
    inserted = 0

    print(f"[데이터 삽입 시작]")

    for i in range(args.total):
        row = (
            fake.name(),
            hashed_pw,
            generate_phone(i),
            "MEMBER",
            generate_member_no(i),
            generate_email(i),
            generate_birth_date(),
            choice(GENDERS),
            0,  # is_deleted = false
        )
        batch.append(row)

        if len(batch) == args.batch:
            cursor.executemany(sql, batch)
            conn.commit()
            inserted += len(batch)
            batch = []
            print(f"  → {inserted:,} / {args.total:,} 삽입 완료")

    # 남은 배치 처리
    if batch:
        cursor.executemany(sql, batch)
        conn.commit()
        inserted += len(batch)
        print(f"  → {inserted:,} / {args.total:,} 삽입 완료")

    cursor.close()
    conn.close()
    print(f"\n[완료] 총 {inserted:,}명 생성. 비밀번호: {TEST_PASSWORD}")


if __name__ == "__main__":
    main()
