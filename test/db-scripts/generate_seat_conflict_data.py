"""
좌석 충돌 정합성 테스트용 데이터 생성 스크립트

생성 데이터:
  [Phase 1 - 완료된 예약]
    Order    (ORDERED) × --ordered  (기본 200,000)
    Payment  (PAID)    × --ordered
    Booking  (BOOKED)  × --ordered
    SeatBooking        × --ordered  ← 충돌 감지 핵심

  [Phase 2 - 배경 데이터]
    Order    (PENDING) × --pending  (기본 100,000)
    OrderBooking       × --pending
    OrderSeatBooking   × --pending

좌석 배분 전략:
  전체 좌석을 절반으로 분리
    선점 좌석 (50%) → SeatBooking 생성에 사용
    잔여 좌석 (50%) → K6에서 좌석 조회 API로 직접 확인 후 예약 시도

사용법:
    pip install pymysql
    python test/db-scripts/generate_seat_conflict_data.py           # 생성
    python test/db-scripts/generate_seat_conflict_data.py --cleanup # 삭제

전제 조건:
    - generate_members.py 먼저 실행 (member 데이터 필요)
    - Train, TrainSchedule, ScheduleStop, Seat 데이터 필요
"""

import pymysql
import argparse
import os
import random
import sys
import uuid
from datetime import datetime, timedelta
from decimal import Decimal

# ──────────────────────────────────────────────────────
# 상수
# ──────────────────────────────────────────────────────

FARE = Decimal("59800")
PASSENGER_TYPES = ["ADULT", "CHILD", "SENIOR"]
CHUNK_SIZE = 10_000          # 메모리 절약: 청크 단위로 처리


# ──────────────────────────────────────────────────────
# 유틸
# ──────────────────────────────────────────────────────

def short_code():
    return uuid.uuid4().hex[:16].upper()


def make_payment_key(phase, i):
    return f"{phase}_{i:08d}"


# ──────────────────────────────────────────────────────
# DB 사전 조회
# ──────────────────────────────────────────────────────

def load_members(cursor):
    cursor.execute("SELECT id FROM member WHERE is_deleted = 0 ORDER BY id")
    return [r[0] for r in cursor.fetchall()]


def load_trips(cursor):
    """
    모든 인접 정차 구간 조회 (스케줄당 여러 구간)
    반환: [(schedule_id, dep_stop_id, arr_stop_id, dep_order, arr_order, dep_station_id, arr_station_id), ...]
    """
    cursor.execute("""
        SELECT s1.train_schedule_id,
               s1.schedule_stop_id, s2.schedule_stop_id,
               s1.stop_order,       s2.stop_order,
               s1.station_id,       s2.station_id
        FROM schedule_stop s1
        JOIN schedule_stop s2
          ON s1.train_schedule_id = s2.train_schedule_id
         AND s2.stop_order = s1.stop_order + 1
    """)
    return cursor.fetchall()  # 모든 인접 구간 반환


def load_all_seats(cursor):
    """
    전체 좌석 목록 (스케줄 조인 없이 단순 조회)
    반환: [(seat_id, car_type), ...]
    """
    cursor.execute("""
        SELECT s.seat_id, tc.car_type
        FROM seat s
        JOIN train_car tc ON s.train_car_id = tc.train_car_id
        ORDER BY s.seat_id
    """)
    return cursor.fetchall()


# ──────────────────────────────────────────────────────
# 좌석 배분
# ──────────────────────────────────────────────────────

def split_seats(all_seats):
    """
    전체 좌석을 절반으로 분할
    pre_booked : SeatBooking으로 삽입 (선점) → [(seat_id, car_type), ...]
    """
    mid = max(1, len(all_seats) // 2)
    return all_seats[:mid]


# ──────────────────────────────────────────────────────
# 배치 삽입 / ID 조회 헬퍼
# ──────────────────────────────────────────────────────

def batch_insert(cursor, conn, sql, rows, label):
    total = len(rows)
    for start in range(0, total, CHUNK_SIZE):
        cursor.executemany(sql, rows[start:start + CHUNK_SIZE])
        conn.commit()
        print(f"    {min(start + CHUNK_SIZE, total):,} / {total:,}")
    print(f"  [{label}] 완료")


def fetch_last_ids(cursor, table, pk_col, count):
    """직전에 삽입한 레코드 ID를 삽입 순서대로 반환"""
    cursor.execute(f"SELECT {pk_col} FROM {table} ORDER BY {pk_col} DESC LIMIT {count}")
    ids = [r[0] for r in cursor.fetchall()]
    ids.reverse()
    return ids


# ──────────────────────────────────────────────────────
# Phase 1: 완료된 예약 (ORDERED / PAID / BOOKED)
# ──────────────────────────────────────────────────────

def insert_ordered_phase(cursor, conn, count, members, trips, pre_booked):
    """
    Order(ORDERED) → Payment(PAID) → Booking → SeatBooking
    pre_booked: [(seat_id, car_type), ...]  (전체 좌석의 50%)
    trips: [(schedule_id, dep_stop_id, arr_stop_id, dep_order, arr_order, dep_station_id, arr_station_id), ...]
    """
    if not pre_booked:
        print("[오류] seat 데이터가 없습니다. Seat/TrainCar 테이블을 확인하세요.", file=sys.stderr)
        sys.exit(1)
    if not trips:
        print("[오류] schedule_stop 데이터가 없습니다.", file=sys.stderr)
        sys.exit(1)

    now = datetime.now()

    # ── 1. Order (ORDERED) ─────────────────────────
    print("[Phase 1 - 1/4] Order(ORDERED) 삽입...")
    sql_order = """
        INSERT INTO orders
            (member_id, order_code, order_status, total_amount, expired_at, created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
    """
    order_rows = []
    meta = []    # (member_id, order_code, schedule_id, dep_stop_id, arr_stop_id, dep_order, arr_order, dep_station_id, arr_station_id, seat_id, car_type, created_at)

    for i in range(count):
        row = trips[i % len(trips)]
        sid, dep_stop_id, arr_stop_id, dep_order, arr_order, dep_station_id, arr_station_id = row
        seat_id, car_type = pre_booked[i % len(pre_booked)]
        member_id  = members[i % len(members)]
        order_code = short_code()
        created_at = now - timedelta(days=random.randint(0, 365))

        order_rows.append((member_id, order_code, "ORDERED", FARE, None, created_at, created_at))
        meta.append((member_id, order_code, sid, dep_stop_id, arr_stop_id, dep_order, arr_order, dep_station_id, arr_station_id, seat_id, car_type, created_at))

    batch_insert(cursor, conn, sql_order, order_rows, "Order(ORDERED)")
    order_ids = fetch_last_ids(cursor, "orders", "order_id", count)

    # ── 2. Payment (PAID) ──────────────────────────
    print("[Phase 1 - 2/4] Payment(PAID) 삽입...")
    sql_payment = """
        INSERT INTO payment
            (member_id, order_id, order_code, payment_key, amount,
             payment_method, payment_status, paid_at,
             failed_at, cancelled_at, refunded_at, failure_code, failure_reason)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
    """
    payment_rows = [
        (meta[i][0], order_ids[i], meta[i][1],
         make_payment_key("PAID", i), FARE,
         "CREDIT_CARD", "PAID", meta[i][11],
         None, None, None, None, None)
        for i in range(count)
    ]
    batch_insert(cursor, conn, sql_payment, payment_rows, "Payment(PAID)")

    # ── 3. Booking (BOOKED) ───────────────────────
    print("[Phase 1 - 3/4] Booking(BOOKED) 삽입...")
    sql_booking = """
        INSERT INTO booking
            (member_id, order_id, train_schedule_id,
             departure_stop_id, arrival_stop_id,
             booking_status, booking_code, cancelled_at, created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
    """
    booking_rows = []
    for i in range(count):
        member_id, order_code, sid, dep_stop_id, arr_stop_id, dep_order, arr_order, dep_station_id, arr_station_id, seat_id, car_type, created_at = meta[i]
        booking_rows.append((
            member_id, order_ids[i], sid,
            dep_stop_id, arr_stop_id,
            "BOOKED", short_code(), None, created_at, created_at
        ))
    batch_insert(cursor, conn, sql_booking, booking_rows, "Booking")
    booking_ids = fetch_last_ids(cursor, "booking", "booking_id", count)

    # ── 4. SeatBooking ────────────────────────────
    print("[Phase 1 - 4/4] SeatBooking 삽입...")
    sql_seat_booking = """
        INSERT INTO seat_booking
            (train_schedule_id, seat_id, booking_id,
             passenger_type, car_type,
             departure_station_id, arrival_station_id,
             departure_stop_order, arrival_stop_order,
             created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
    """
    seat_booking_rows = []
    for i in range(count):
        member_id, order_code, sid, dep_stop_id, arr_stop_id, dep_order, arr_order, dep_station_id, arr_station_id, seat_id, car_type, created_at = meta[i]
        seat_booking_rows.append((
            sid, seat_id, booking_ids[i],
            random.choice(PASSENGER_TYPES), car_type,
            dep_station_id, arr_station_id,
            dep_order, arr_order,
            created_at, created_at
        ))
    batch_insert(cursor, conn, sql_seat_booking, seat_booking_rows, "SeatBooking")


# ──────────────────────────────────────────────────────
# Phase 2: 배경 데이터 (PENDING)
# ──────────────────────────────────────────────────────

def insert_pending_phase(cursor, conn, count, members, trips, pre_booked):
    # OrderSeatBooking.seat_id는 일반 컬럼 (FK 아님) → 아무 seat_id나 사용 가능 (배경 데이터)
    any_seat_ids = [seat_id for seat_id, _ in pre_booked]
    now = datetime.now()

    # ── 1. Order (PENDING) ────────────────────────
    print("[Phase 2 - 1/3] Order(PENDING) 삽입...")
    sql_order = """
        INSERT INTO orders
            (member_id, order_code, order_status, total_amount, expired_at, created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
    """
    order_rows = []
    meta = []

    for i in range(count):
        member_id  = members[i % len(members)]
        created_at = now - timedelta(hours=random.randint(1, 48))
        expired_at = created_at + timedelta(minutes=10)   # 10분 만료
        order_rows.append((member_id, short_code(), "PENDING", FARE, expired_at, created_at, created_at))
        meta.append(created_at)

    batch_insert(cursor, conn, sql_order, order_rows, "Order(PENDING)")
    order_ids = fetch_last_ids(cursor, "orders", "order_id", count)

    # ── 2. OrderBooking ───────────────────────────
    print("[Phase 2 - 2/3] OrderBooking 삽입...")
    sql_order_booking = """
        INSERT INTO order_booking
            (order_id, pending_booking_id, train_schedule_id,
             departure_stop_id, arrival_stop_id, total_fare, created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
    """
    order_booking_rows = []
    for i in range(count):
        row = random.choice(trips)
        sid, dep_stop_id, arr_stop_id = row[0], row[1], row[2]
        order_booking_rows.append((
            order_ids[i], str(uuid.uuid4()),
            sid, dep_stop_id, arr_stop_id,
            FARE, meta[i], meta[i]
        ))
    batch_insert(cursor, conn, sql_order_booking, order_booking_rows, "OrderBooking")
    order_booking_ids = fetch_last_ids(cursor, "order_booking", "order_booking_id", count)

    # ── 3. OrderSeatBooking ───────────────────────
    print("[Phase 2 - 3/3] OrderSeatBooking 삽입...")
    sql_order_seat_booking = """
        INSERT INTO order_seat_booking
            (order_booking_id, seat_id, passenger_type, fare, created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s)
    """
    order_seat_booking_rows = [
        (order_booking_ids[i], random.choice(any_seat_ids),
         random.choice(PASSENGER_TYPES), FARE, meta[i], meta[i])
        for i in range(count)
    ]
    batch_insert(cursor, conn, sql_order_seat_booking, order_seat_booking_rows, "OrderSeatBooking")


# ──────────────────────────────────────────────────────
# Cleanup
# ──────────────────────────────────────────────────────

def cleanup(conn):
    """
    테스트 멤버(user%@raillo.com) 기준으로 연관 데이터 삭제
    삭제 순서: Payment → Booking → Order
      - Payment  : NO_CONSTRAINT FK → 수동 삭제 필수
      - Booking  삭제 시 SeatBooking, Ticket CASCADE 자동 삭제
      - Order    삭제 시 OrderBooking, OrderSeatBooking CASCADE 자동 삭제
    """
    cursor = conn.cursor()
    print("[정리 시작]")

    cursor.execute("SELECT id FROM member WHERE email LIKE 'user%@raillo.com'")
    member_ids = [r[0] for r in cursor.fetchall()]
    if not member_ids:
        print("  정리할 테스트 데이터 없음")
        return

    ids_str = ",".join(map(str, member_ids))

    # 1. Payment (NO_CONSTRAINT → 수동 삭제)
    cursor.execute(f"DELETE FROM payment WHERE member_id IN ({ids_str})")
    conn.commit()
    print(f"  Payment 삭제 : {cursor.rowcount:,}건")

    # 2. Booking 삭제 → SeatBooking, Ticket은 DB CASCADE로 자동 삭제
    cursor.execute(f"DELETE FROM booking WHERE member_id IN ({ids_str})")
    conn.commit()
    print(f"  Booking 삭제 : {cursor.rowcount:,}건 (SeatBooking, Ticket CASCADE)")

    # 3. Order 삭제 → OrderBooking, OrderSeatBooking은 DB CASCADE로 자동 삭제
    cursor.execute(f"DELETE FROM orders WHERE member_id IN ({ids_str})")
    conn.commit()
    print(f"  Order 삭제   : {cursor.rowcount:,}건 (OrderBooking, OrderSeatBooking CASCADE)")

    cursor.close()
    print("[정리 완료]")


# ──────────────────────────────────────────────────────
# 메인
# ──────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="좌석 충돌 정합성 테스트 데이터 생성")
    parser.add_argument("--ordered",  type=int, default=200_000, help="완료 예약 수 (기본 200,000)")
    parser.add_argument("--pending",  type=int, default=100_000, help="배경 PENDING 수 (기본 100,000)")
    parser.add_argument("--batch",    type=int, default=500)
    parser.add_argument("--host",     default=os.environ.get("DB_HOST", "localhost"))
    parser.add_argument("--port",     type=int, default=int(os.environ.get("DB_PORT", "3306")))
    parser.add_argument("--db",       default=os.environ.get("DB_NAME", "raillo"))
    parser.add_argument("--user",     default=os.environ.get("DB_USER", "sa"))
    parser.add_argument("--password", default=os.environ.get("DB_PASSWORD", "secret"))
    parser.add_argument("--cleanup",  action="store_true", help="테스트 데이터 삭제")
    args = parser.parse_args()

    try:
        conn = pymysql.connect(
            host=args.host, port=args.port, db=args.db,
            user=args.user, password=args.password, charset="utf8mb4",
        )
    except pymysql.Error as e:
        print(f"[오류] DB 연결 실패: {e}", file=sys.stderr)
        sys.exit(1)

    if args.cleanup:
        cleanup(conn)
        conn.close()
        return

    cursor = conn.cursor()

    # ── 사전 조회 ──────────────────────────────────
    print("[사전 조회] 기존 데이터 로드 중...")
    members   = load_members(cursor)
    trips     = load_trips(cursor)
    all_seats = load_all_seats(cursor)

    if not members:
        print("[오류] member 없음 → generate_members.py 먼저 실행하세요.", file=sys.stderr)
        sys.exit(1)
    if not trips:
        print("[오류] schedule_stop 데이터 없음.", file=sys.stderr)
        sys.exit(1)
    if not all_seats:
        print("[오류] seat 데이터 없음. Seat/TrainCar 테이블을 확인하세요.", file=sys.stderr)
        sys.exit(1)

    trips = list(trips)
    random.shuffle(trips)    # 구간 순서 섞어서 다양한 stop_order 배정

    pre_booked = split_seats(all_seats)

    unique_schedules = len({row[0] for row in trips})
    print(f"  members       : {len(members):,}")
    print(f"  trip 구간 수   : {len(trips):,}  (스케줄 {unique_schedules:,}개)")
    print(f"  전체 좌석      : {len(all_seats):,}")
    print(f"  선점 좌석 (50%): {len(pre_booked):,}")

    # ── Phase 1: 완료된 예약 ──────────────────────
    print(f"\n[Phase 1] 완료 예약 데이터 {args.ordered:,}개 생성")
    insert_ordered_phase(cursor, conn, args.ordered, members, trips, pre_booked)

    # ── Phase 2: 배경 데이터 ──────────────────────
    print(f"\n[Phase 2] 배경 PENDING 데이터 {args.pending:,}개 생성")
    insert_pending_phase(cursor, conn, args.pending, members, trips, pre_booked)

    cursor.close()
    conn.close()
    print(f"\n[완료]")
    print(f"  SeatBooking / Booking / Order(ORDERED) / Payment(PAID) : 각 {args.ordered:,}개")
    print(f"  Order(PENDING) / OrderBooking / OrderSeatBooking        : 각 {args.pending:,}개")


if __name__ == "__main__":
    main()
