"""
특정 스케줄 ID 기준 객차당 20% 좌석 강제 선점 스크립트

지정한 스케줄 ID 목록에 대해 각 Train의 객차(TrainCar)당 20% 좌석을
완료 예약 상태(Order ORDERED / Payment PAID / Booking BOOKED / SeatBooking)로 삽입합니다.

좌석 선정 전략:
  - 객차당 목표 = 전체 좌석 수의 ratio (기본 20%)
  - 이미 선점 수 >= 목표 → 객차 패스 (이미 목표치 달성)
  - 이미 선점 수 <  목표 → 부족한 만큼만 available 좌석에서 추가 선점

생성 데이터 (스케줄당):
  Order    (ORDERED) × 신규 선점 좌석 수
  Payment  (PAID)    × 신규 선점 좌석 수
  Booking  (BOOKED)  × 신규 선점 좌석 수
  SeatBooking        × 신규 선점 좌석 수

사용법:
    pip install pymysql
    python qa/db-scripts/generate_schedule_preoccupy.py --schedule-ids 1 2 3 4 5
    python qa/db-scripts/generate_schedule_preoccupy.py --cleanup

전제 조건:
    - generate_members.py 먼저 실행 (member 데이터 필요)
    - generate_seat_conflict_data.py 실행 후 사용 가능 (기 선점 좌석 회피)
    - Train, TrainSchedule, ScheduleStop, Seat 데이터 필요
"""

import pymysql
import argparse
import os
import random
import sys
import uuid
from collections import defaultdict
from datetime import datetime, timedelta
from decimal import Decimal

# ──────────────────────────────────────────────────────
# 상수
# ──────────────────────────────────────────────────────

FARE = Decimal("59800")
PASSENGER_TYPES = ["ADULT", "CHILD", "SENIOR"]
CHUNK_SIZE = 10_000


# ──────────────────────────────────────────────────────
# 유틸
# ──────────────────────────────────────────────────────

def short_code():
    return uuid.uuid4().hex[:16].upper()


def make_payment_key(seq):
    return f"PREOCCUPY_{seq:010d}_{uuid.uuid4().hex[:8].upper()}"


# ──────────────────────────────────────────────────────
# DB 사전 조회
# ──────────────────────────────────────────────────────

def load_members(cursor):
    cursor.execute("SELECT id FROM member WHERE is_deleted = 0 ORDER BY id")
    return [r[0] for r in cursor.fetchall()]


def load_schedule_data(cursor, schedule_id):
    """
    특정 스케줄의 train 전체 좌석 목록과 인접 구간 조회
    반환: (train_id, train_seats, trips)
      train_seats : [(train_id, seat_id, car_type, train_car_id), ...]
      trips       : [(schedule_id, dep_stop_id, arr_stop_id, dep_order, arr_order,
                      dep_station_id, arr_station_id, train_id), ...]
    """
    cursor.execute(
        "SELECT train_id FROM train_schedule WHERE train_schedule_id = %s",
        (schedule_id,)
    )
    row = cursor.fetchone()
    if not row:
        return None, [], []
    train_id = row[0]

    cursor.execute("""
        SELECT tc.train_id, s.seat_id, tc.car_type, tc.train_car_id
        FROM train_car tc
        JOIN seat s ON s.train_car_id = tc.train_car_id
        WHERE tc.train_id = %s
        ORDER BY tc.train_car_id, s.seat_id
    """, (train_id,))
    train_seats = cursor.fetchall()

    cursor.execute("""
        SELECT s1.train_schedule_id,
               s1.schedule_stop_id, s2.schedule_stop_id,
               s1.stop_order,       s2.stop_order,
               s1.station_id,       s2.station_id,
               ts.train_id
        FROM schedule_stop s1
        JOIN schedule_stop s2
          ON s1.train_schedule_id = s2.train_schedule_id
         AND s2.stop_order = s1.stop_order + 1
        JOIN train_schedule ts ON s1.train_schedule_id = ts.train_schedule_id
        WHERE s1.train_schedule_id = %s
    """, (schedule_id,))
    trips = cursor.fetchall()

    return train_id, train_seats, trips


def load_occupied_seat_ids(cursor, schedule_id, train_id):
    """해당 스케줄에서 이미 seat_booking이 존재하는 seat_id 집합 조회"""
    cursor.execute("""
        SELECT DISTINCT sb.seat_id
        FROM seat_booking sb
        JOIN seat s       ON sb.seat_id = s.seat_id
        JOIN train_car tc ON s.train_car_id = tc.train_car_id
        WHERE sb.train_schedule_id = %s
          AND tc.train_id = %s
    """, (schedule_id, train_id))
    return {r[0] for r in cursor.fetchall()}


# ──────────────────────────────────────────────────────
# 좌석 배분
# ──────────────────────────────────────────────────────

def select_seats_to_preoccupy(train_seats, occupied_seat_ids, ratio=0.2):
    """
    객차(train_car_id)별로 그룹화하여 신규 선점 좌석 결정

    선정 규칙:
      target = max(1, floor(객차 전체 좌석 × ratio))
      이미 선점 수 >= target → 패스
      이미 선점 수 <  target → available 좌석에서 (target - 이미 선점 수)만큼 추가 선점

    반환: (to_insert, skipped_cars)
      to_insert    : [(train_id, seat_id, car_type), ...]
      skipped_cars : 목표 이미 달성으로 패스한 객차 수
    """
    car_groups = defaultdict(list)
    for train_id, seat_id, car_type, train_car_id in train_seats:
        car_groups[train_car_id].append((train_id, seat_id, car_type))

    to_insert = []
    skipped_cars = 0

    for car_id in sorted(car_groups.keys()):
        seats = car_groups[car_id]
        target = max(1, int(len(seats) * ratio))

        already_occupied = sum(1 for _, sid, _ in seats if sid in occupied_seat_ids)

        if already_occupied >= target:
            skipped_cars += 1
            continue

        available = [(tid, sid, ct) for tid, sid, ct in seats if sid not in occupied_seat_ids]
        need = target - already_occupied
        to_insert.extend(available[:need])

    return to_insert, skipped_cars


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
# 선점 데이터 삽입: Order → Payment → Booking → SeatBooking
# ──────────────────────────────────────────────────────

def insert_preoccupy(cursor, conn, schedule_id, to_insert, trips, members, global_seq):
    """
    단일 스케줄에 대한 선점 완료 예약 데이터 삽입
    global_seq: payment_key 전역 중복 방지 오프셋
    """
    count = len(to_insert)
    now = datetime.now()
    trips_list = list(trips)

    # ── 1. Order (ORDERED) ─────────────────────────
    print(f"  [스케줄 {schedule_id} - 1/4] Order(ORDERED) {count:,}개 삽입...")
    sql_order = """
        INSERT INTO orders
            (member_id, order_code, order_status, total_amount, expired_at, created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s)
    """
    order_rows = []
    # meta: (member_id, order_code, trip_row, seat_id, car_type, created_at)
    meta = []

    for i, (train_id, seat_id, car_type) in enumerate(to_insert):
        trip = trips_list[i % len(trips_list)]
        member_id = members[i % len(members)]
        order_code = short_code()
        created_at = now - timedelta(days=random.randint(0, 30))

        order_rows.append((member_id, order_code, "ORDERED", FARE, None, created_at, created_at))
        meta.append((member_id, order_code, trip, seat_id, car_type, created_at))

    batch_insert(cursor, conn, sql_order, order_rows, "Order(ORDERED)")
    order_ids = fetch_last_ids(cursor, "orders", "order_id", count)

    # ── 2. Payment (PAID) ──────────────────────────
    print(f"  [스케줄 {schedule_id} - 2/4] Payment(PAID) {count:,}개 삽입...")
    sql_payment = """
        INSERT INTO payment
            (member_id, order_id, order_code, payment_key, amount,
             payment_method, payment_status, paid_at,
             failed_at, cancelled_at, refunded_at, failure_code, failure_reason)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
    """
    payment_rows = [
        (meta[i][0], order_ids[i], meta[i][1],
         make_payment_key(global_seq + i), FARE,
         "CREDIT_CARD", "PAID", meta[i][5],
         None, None, None, None, None)
        for i in range(count)
    ]
    batch_insert(cursor, conn, sql_payment, payment_rows, "Payment(PAID)")

    # ── 3. Booking (BOOKED) ───────────────────────
    print(f"  [스케줄 {schedule_id} - 3/4] Booking(BOOKED) {count:,}개 삽입...")
    sql_booking = """
        INSERT INTO booking
            (member_id, order_id, train_schedule_id,
             departure_stop_id, arrival_stop_id,
             booking_status, booking_code, cancelled_at, created_at, updated_at)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
    """
    booking_rows = []
    for i in range(count):
        member_id, order_code, trip, seat_id, car_type, created_at = meta[i]
        sid, dep_stop_id, arr_stop_id = trip[0], trip[1], trip[2]
        booking_rows.append((
            member_id, order_ids[i], sid,
            dep_stop_id, arr_stop_id,
            "BOOKED", short_code(), None, created_at, created_at
        ))
    batch_insert(cursor, conn, sql_booking, booking_rows, "Booking")
    booking_ids = fetch_last_ids(cursor, "booking", "booking_id", count)

    # ── 4. SeatBooking ────────────────────────────
    print(f"  [스케줄 {schedule_id} - 4/4] SeatBooking {count:,}개 삽입...")
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
        member_id, order_code, trip, seat_id, car_type, created_at = meta[i]
        sid, dep_stop_id, arr_stop_id, dep_order, arr_order, dep_station_id, arr_station_id, _ = trip
        seat_booking_rows.append((
            sid, seat_id, booking_ids[i],
            random.choice(PASSENGER_TYPES), car_type,
            dep_station_id, arr_station_id,
            dep_order, arr_order,
            created_at, created_at
        ))
    batch_insert(cursor, conn, sql_seat_booking, seat_booking_rows, "SeatBooking")

    return count


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
        cursor.close()
        return

    ids_str = ",".join(map(str, member_ids))

    cursor.execute(f"DELETE FROM payment WHERE member_id IN ({ids_str})")
    conn.commit()
    print(f"  Payment 삭제 : {cursor.rowcount:,}건")

    cursor.execute(f"DELETE FROM booking WHERE member_id IN ({ids_str})")
    conn.commit()
    print(f"  Booking 삭제 : {cursor.rowcount:,}건 (SeatBooking, Ticket CASCADE)")

    cursor.execute(f"DELETE FROM orders WHERE member_id IN ({ids_str})")
    conn.commit()
    print(f"  Order 삭제   : {cursor.rowcount:,}건 (OrderBooking, OrderSeatBooking CASCADE)")

    cursor.close()
    print("[정리 완료]")


# ──────────────────────────────────────────────────────
# 메인
# ──────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="특정 스케줄 ID 기준 객차당 20% 좌석 강제 선점")
    parser.add_argument("--schedule-ids", type=int, nargs="*", default=None,
                        help="선점할 스케줄 ID 목록 (공백 구분). --cleanup 없이 사용 시 필수")
    parser.add_argument("--ratio",    type=float, default=0.2,  help="객차당 선점 비율 (기본 0.2 = 20%%)")
    parser.add_argument("--host",     default=os.environ.get("DB_HOST",     "localhost"))
    parser.add_argument("--port",     type=int, default=int(os.environ.get("DB_PORT", "3306")))
    parser.add_argument("--db",       default=os.environ.get("DB_NAME",     "raillo"))
    parser.add_argument("--user",     default=os.environ.get("DB_USER",     "sa"))
    parser.add_argument("--password", default=os.environ.get("DB_PASSWORD", "secret"))
    parser.add_argument("--cleanup",  action="store_true", help="테스트 멤버 기준 연관 데이터 전체 삭제")
    args = parser.parse_args()

    if not args.cleanup and not args.schedule_ids:
        print("[오류] --schedule-ids 를 지정하거나 --cleanup 을 사용하세요.", file=sys.stderr)
        sys.exit(1)

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

    members = load_members(cursor)
    if not members:
        print("[오류] member 없음 → generate_members.py 먼저 실행하세요.", file=sys.stderr)
        sys.exit(1)

    print(f"[사전 조회] member {len(members):,}명 로드 완료")
    print(f"[대상 스케줄] {args.schedule_ids}")

    total_inserted = 0
    global_seq = 0

    for schedule_id in args.schedule_ids:
        print(f"\n[스케줄 {schedule_id}] 데이터 로드 중...")

        train_id, train_seats, trips = load_schedule_data(cursor, schedule_id)

        if train_id is None:
            print(f"  [경고] 스케줄 {schedule_id} 를 찾을 수 없습니다. 건너뜁니다.")
            continue
        if not train_seats:
            print(f"  [경고] 스케줄 {schedule_id} 의 좌석 데이터 없음. 건너뜁니다.")
            continue
        if not trips:
            print(f"  [경고] 스케줄 {schedule_id} 의 구간(schedule_stop) 데이터 없음. 건너뜁니다.")
            continue

        occupied_seat_ids = load_occupied_seat_ids(cursor, schedule_id, train_id)
        to_insert, skipped_cars = select_seats_to_preoccupy(
            train_seats, occupied_seat_ids, ratio=args.ratio
        )

        total_cars = len({tc_id for _, _, _, tc_id in train_seats})
        print(f"  전체 좌석: {len(train_seats):,}석 | 객차: {total_cars}개 | 이미 선점: {len(occupied_seat_ids):,}석")
        print(f"  신규 선점 대상: {len(to_insert):,}석 | 목표 달성으로 패스: {skipped_cars}개 객차")

        if not to_insert:
            print(f"  [스케줄 {schedule_id}] 모든 객차가 이미 목표 선점률 달성 → 건너뜁니다.")
            continue

        inserted = insert_preoccupy(
            cursor, conn, schedule_id, to_insert, trips, members, global_seq
        )
        total_inserted += inserted
        global_seq += inserted

    cursor.close()
    conn.close()
    print(f"\n[완료] 총 {total_inserted:,}개 SeatBooking 선점 생성")
    print(f"  (Order / Payment / Booking / SeatBooking 각 {total_inserted:,}개)")


if __name__ == "__main__":
    main()
