"""
Prepare booking API performance-test data and k6 config.

This script is intentionally branch-aware because v1 and develop use different
reservation schemas. It keeps train/member data intact, clears only booking
and payment related tables, creates 30% pre-occupied confirmed seats, and
writes a k6 config JSON.

Examples:
  python3 qa/db-scripts/booking_perf_prepare.py \
    --branch v1 --schema v1 --confirm-test-db \
    --output qa/k6/config/booking-performance-config.json \
    --seed-report qa/results/booking-performance/local/seed-report-v1.md

  python3 qa/db-scripts/booking_perf_prepare.py \
    --branch develop --schema v2 --confirm-test-db \
    --output qa/k6/config/booking-performance-config.json \
    --seed-report qa/results/booking-performance/local/seed-report-develop.md
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import uuid
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from decimal import Decimal
from pathlib import Path
from typing import Any

try:
    import pymysql
    from pymysql.constants import ER
except ImportError as exc:
    print("[ERROR] pymysql is required. Install with: pip install pymysql", file=sys.stderr)
    raise SystemExit(1) from exc


MEMBER_NO_START = "202603030001"
MEMBER_NO_END = "202603039999"
MEMBER_PASSWORD = "Test1234!"
DEFAULT_FARE = Decimal("10000")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Prepare booking performance-test data")
    parser.add_argument("--branch", choices=["v1", "develop"], required=True)
    parser.add_argument("--schema", help="MySQL schema/database name. Defaults to v1 or v2 by branch.")
    parser.add_argument("--host", default=os.environ.get("DB_HOST", "localhost"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("DB_PORT", "3306")))
    parser.add_argument("--user", default=os.environ.get("DB_USER", "root"))
    parser.add_argument("--password", default=os.environ.get("DB_PASSWORD", "1234"))
    parser.add_argument("--env-from", action="store_true", help="Load DB settings from .env TEST_DB_* keys")
    parser.add_argument("--env-file", default=".env", help="Env file path. Passing a non-default path implies --env-from.")
    parser.add_argument("--occupancy", type=float, default=0.30)
    parser.add_argument("--member-limit", type=int, default=1000)
    parser.add_argument("--schedule-count", type=int, default=1)
    parser.add_argument("--min-seats", type=int, default=100)
    parser.add_argument(
        "--departure-buffer-minutes",
        type=int,
        default=30,
        help="Only select schedules departing after DB NOW() plus this buffer.",
    )
    parser.add_argument("--output", required=True)
    parser.add_argument("--seed-report", required=True)
    parser.add_argument("--no-cleanup", action="store_true", help="Skip booking/payment cleanup")
    parser.add_argument("--no-seed", action="store_true", help="Skip pre-occupied booking inserts")
    parser.add_argument(
        "--confirm-test-db",
        action="store_true",
        help="Required for cleanup/seed. Confirms this points to a disposable test schema.",
    )
    args = parser.parse_args()

    if args.env_from or args.env_file != ".env":
        env = load_env_file(args.env_file)
        args.host = env.get("host", args.host)
        args.port = env.get("port", args.port)
        args.schema = env.get("db", args.schema)
        args.user = env.get("user", args.user)
        args.password = env.get("password", args.password)

    if not args.schema:
        args.schema = "v1" if args.branch == "v1" else "v2"

    if not 0 < args.occupancy < 1:
        raise SystemExit("[ERROR] --occupancy must be between 0 and 1")

    if args.departure_buffer_minutes < 0:
        raise SystemExit("[ERROR] --departure-buffer-minutes must be greater than or equal to 0")

    if (not args.no_cleanup or not args.no_seed) and not args.confirm_test_db:
        raise SystemExit("[ERROR] Add --confirm-test-db to allow cleanup/seed writes.")

    return args


def load_env_file(env_path: str) -> dict[str, Any]:
    path = Path(env_path)
    if not path.is_file():
        raise SystemExit(f"[ERROR] .env file not found: {env_path}")

    env_vars: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        env_vars[key.strip()] = value.strip().strip("'\"")

    result: dict[str, Any] = {}
    db_url = env_vars.get("TEST_DB_URL", "")
    if db_url:
        after_protocol = db_url.split("://", 1)[-1]
        host_port_db = after_protocol.split("?", 1)[0]
        host_port, _, db_name = host_port_db.partition("/")
        if ":" in host_port:
            host, port = host_port.split(":", 1)
            result["host"] = "localhost" if host == "host.docker.internal" else host
            result["port"] = int(port)
        else:
            result["host"] = "localhost" if host_port == "host.docker.internal" else host_port
        if db_name:
            result["db"] = db_name

    if "TEST_DB_USERNAME" in env_vars:
        result["user"] = env_vars["TEST_DB_USERNAME"]
    if "TEST_DB_PW" in env_vars:
        result["password"] = env_vars["TEST_DB_PW"]
    return result


def connect(args: argparse.Namespace):
    return pymysql.connect(
        host=args.host,
        port=args.port,
        db=args.schema,
        user=args.user,
        password=args.password,
        charset="utf8mb4",
        autocommit=False,
        cursorclass=pymysql.cursors.DictCursor,
    )


def delete_if_exists(cursor, table: str) -> int:
    try:
        cursor.execute(f"DELETE FROM {table}")
        return int(cursor.rowcount)
    except pymysql.err.ProgrammingError as exc:
        if exc.args and exc.args[0] == ER.NO_SUCH_TABLE:
            print(f"[WARN] table not found, skip cleanup: {table}")
            return 0
        raise


def cleanup(conn, branch: str) -> dict[str, int]:
    tables = (
        ["payment", "cart_reservation", "ticket", "seat_reservation", "reservation", "qr"]
        if branch == "v1"
        else ["ticket", "seat_booking", "order_seat_booking", "order_booking", "booking", "payment", "orders"]
    )
    deleted: dict[str, int] = {}
    with conn.cursor() as cursor:
        for table in tables:
            deleted[table] = delete_if_exists(cursor, table)
    conn.commit()
    return deleted


def load_members(conn, limit: int) -> list[dict[str, Any]]:
    sql = """
        SELECT id, member_no
        FROM member
        WHERE member_no BETWEEN %s AND %s
          AND is_deleted = 0
        ORDER BY member_no
        LIMIT %s
    """
    with conn.cursor() as cursor:
        cursor.execute(sql, (MEMBER_NO_START, MEMBER_NO_END, limit))
        members = list(cursor.fetchall())
    if len(members) < limit:
        raise SystemExit(f"[ERROR] expected {limit} members, found {len(members)}")
    return members


def validate_members(conn, branch: str) -> None:
    if branch != "v1":
        return

    sql = """
        SELECT COUNT(*) AS invalid_count
        FROM member
        WHERE member_no BETWEEN %s AND %s
          AND is_deleted = 0
          AND (is_locked IS NULL OR lock_count IS NULL)
    """
    with conn.cursor() as cursor:
        cursor.execute(sql, (MEMBER_NO_START, MEMBER_NO_END))
        invalid_count = int(cursor.fetchone()["invalid_count"])

    if invalid_count > 0:
        raise SystemExit(
            "[ERROR] v1 member rows require non-null is_locked and lock_count. "
            f"invalid_count={invalid_count}. "
            "Run: UPDATE member SET is_locked = 0, lock_count = 0 "
            f"WHERE member_no BETWEEN '{MEMBER_NO_START}' AND '{MEMBER_NO_END}' AND is_deleted = 0;"
        )


def load_database_now(conn) -> datetime:
    with conn.cursor() as cursor:
        cursor.execute("SELECT NOW() AS now")
        return cursor.fetchone()["now"]


def format_mysql_time(value: Any) -> str:
    if isinstance(value, timedelta):
        total_seconds = int(value.total_seconds())
        hours, remainder = divmod(total_seconds, 3600)
        minutes, seconds = divmod(remainder, 60)
        return f"{hours:02d}:{minutes:02d}:{seconds:02d}"
    if hasattr(value, "isoformat"):
        return value.isoformat()
    return str(value)


def format_mysql_date(value: Any) -> str:
    if hasattr(value, "isoformat"):
        return value.isoformat()
    return str(value)


def load_schedule_candidates(conn, min_seats: int, limit: int, departure_cutoff: datetime) -> list[dict[str, Any]]:
    sql = """
        SELECT
            ts.train_schedule_id AS schedule_id,
            ts.train_id AS train_id,
            ts.operation_date AS operation_date,
            ts.departure_time AS departure_time,
            ts.operation_status AS operation_status,
            COUNT(DISTINCT s.seat_id) AS total_seats,
            MIN(s.seat_id) AS seat_start,
            MAX(s.seat_id) AS seat_end
        FROM train_schedule ts
        JOIN train_car tc ON tc.train_id = ts.train_id
        JOIN seat s ON s.train_car_id = tc.train_car_id
        WHERE ts.operation_status IN ('ACTIVE', 'DELAYED')
          AND (
              ts.operation_date > %s
              OR (ts.operation_date = %s AND ts.departure_time > %s)
          )
        GROUP BY ts.train_schedule_id, ts.train_id, ts.operation_date, ts.departure_time, ts.operation_status
        HAVING COUNT(DISTINCT s.seat_id) >= %s
        ORDER BY ts.operation_date, ts.departure_time, ts.train_schedule_id
        LIMIT %s
    """
    with conn.cursor() as cursor:
        cursor.execute(
            sql,
            (
                departure_cutoff.date(),
                departure_cutoff.date(),
                departure_cutoff.time(),
                min_seats,
                limit * 50,
            ),
        )
        rows = list(cursor.fetchall())

    schedules: list[dict[str, Any]] = []
    for row in rows:
        stops = load_stops(conn, row["schedule_id"])
        if len(stops) < 3:
            continue
        seats = load_seats(conn, row["train_id"])
        selected_seats = select_seat_pool(seats)
        if len(selected_seats) < min_seats:
            continue
        schedules.append({**row, "stops": stops, "seats": selected_seats})
        if len(schedules) == limit:
            break

    if not schedules:
        raise SystemExit(
            "[ERROR] no future schedule candidates found. "
            f"departure_cutoff={departure_cutoff.isoformat(timespec='seconds')}, "
            f"min_seats={min_seats}"
        )
    return schedules


def load_stops(conn, schedule_id: int) -> list[dict[str, Any]]:
    sql = """
        SELECT schedule_stop_id, station_id, stop_order
        FROM schedule_stop
        WHERE train_schedule_id = %s
        ORDER BY stop_order
    """
    with conn.cursor() as cursor:
        cursor.execute(sql, (schedule_id,))
        return list(cursor.fetchall())


def load_seats(conn, train_id: int) -> list[dict[str, Any]]:
    sql = """
        SELECT
            s.seat_id,
            tc.train_car_id,
            tc.car_type
        FROM seat s
        JOIN train_car tc ON tc.train_car_id = s.train_car_id
        WHERE tc.train_id = %s
        ORDER BY tc.train_car_id, s.seat_id
    """
    with conn.cursor() as cursor:
        cursor.execute(sql, (train_id,))
        return list(cursor.fetchall())


def select_seat_pool(seats: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_type: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for seat in seats:
        by_type[str(seat["car_type"])].append(seat)
    if "STANDARD" in by_type:
        return by_type["STANDARD"]
    if not by_type:
        return []
    largest_type = max(by_type.keys(), key=lambda key: len(by_type[key]))
    return by_type[largest_type]


def stop_trip(stops: list[dict[str, Any]], mode: str) -> tuple[dict[str, Any], dict[str, Any]]:
    first = stops[0]
    mid = stops[1]
    last = stops[-1]
    if mode == "first_half":
        return first, mid
    if mode == "second_half":
        return mid, last
    return first, last


def short_code(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:18]}".upper()


def seat_distribution(seats: list[dict[str, Any]]) -> dict[str, int]:
    return dict(Counter(str(seat["car_type"]) for seat in seats))


def seed_v1(conn, schedule: dict[str, Any], occupied: list[dict[str, Any]], members: list[dict[str, Any]]) -> None:
    reservation_sql = """
        INSERT INTO reservation
            (train_schedule_id, member_id, departure_stop_id, arrival_stop_id,
             reservation_code, trip_type, total_passengers, passenger_summary,
             reservation_status, expires_at, purchase_at, cancelled_at, fare,
             created_at, updated_at)
        VALUES
            (%s, %s, %s, %s,
             %s, 'OW', 1, '[{"passengerType":"ADULT","count":1}]',
             'RESERVED', %s, NULL, NULL, %s,
             NOW(), NOW())
    """
    seat_reservation_sql = """
        INSERT INTO seat_reservation
            (train_schedule_id, seat_id, reservation_id, passenger_type, created_at, updated_at)
        VALUES
            (%s, %s, %s, 'ADULT', NOW(), NOW())
    """
    expires_at = datetime.now() + timedelta(minutes=30)
    modes = ["full", "first_half", "second_half"]
    stops = schedule["stops"]

    with conn.cursor() as cursor:
        for index, seat in enumerate(occupied):
            departure, arrival = stop_trip(stops, modes[index % len(modes)])
            member = members[index % len(members)]
            cursor.execute(
                reservation_sql,
                (
                    schedule["schedule_id"],
                    member["id"],
                    departure["schedule_stop_id"],
                    arrival["schedule_stop_id"],
                    short_code("RES"),
                    expires_at,
                    DEFAULT_FARE,
                ),
            )
            reservation_id = cursor.lastrowid
            cursor.execute(
                seat_reservation_sql,
                (schedule["schedule_id"], seat["seat_id"], reservation_id),
            )
    conn.commit()


def seed_develop(conn, schedule: dict[str, Any], occupied: list[dict[str, Any]], members: list[dict[str, Any]]) -> None:
    order_sql = """
        INSERT INTO orders
            (member_id, order_code, order_status, total_amount, expired_at, created_at, updated_at)
        VALUES
            (%s, %s, 'ORDERED', %s, NULL, NOW(), NOW())
    """
    booking_sql = """
        INSERT INTO booking
            (member_id, order_id, train_schedule_id, departure_stop_id, arrival_stop_id,
             booking_status, booking_code, cancelled_at, created_at, updated_at)
        VALUES
            (%s, %s, %s, %s, %s, 'BOOKED', %s, NULL, NOW(), NOW())
    """
    seat_booking_sql = """
        INSERT INTO seat_booking
            (train_schedule_id, seat_id, booking_id, passenger_type, car_type,
             departure_station_id, arrival_station_id, departure_stop_order, arrival_stop_order,
             created_at, updated_at)
        VALUES
            (%s, %s, %s, 'ADULT', %s,
             %s, %s, %s, %s,
             NOW(), NOW())
    """
    modes = ["full", "first_half", "second_half"]
    stops = schedule["stops"]

    with conn.cursor() as cursor:
        for index, seat in enumerate(occupied):
            departure, arrival = stop_trip(stops, modes[index % len(modes)])
            member = members[index % len(members)]
            cursor.execute(order_sql, (member["id"], short_code("ORD"), DEFAULT_FARE))
            order_id = cursor.lastrowid
            cursor.execute(
                booking_sql,
                (
                    member["id"],
                    order_id,
                    schedule["schedule_id"],
                    departure["schedule_stop_id"],
                    arrival["schedule_stop_id"],
                    short_code("BKG"),
                ),
            )
            booking_id = cursor.lastrowid
            cursor.execute(
                seat_booking_sql,
                (
                    schedule["schedule_id"],
                    seat["seat_id"],
                    booking_id,
                    seat["car_type"],
                    departure["station_id"],
                    arrival["station_id"],
                    departure["stop_order"],
                    arrival["stop_order"],
                ),
            )
    conn.commit()


def build_config(branch: str, members: list[dict[str, Any]], schedules: list[dict[str, Any]], occupancy: float) -> dict[str, Any]:
    config_schedules = []
    for schedule in schedules:
        seats = schedule["seats"]
        occupied_count = max(1, int(len(seats) * occupancy))
        occupied = seats[:occupied_count]
        open_seats = seats[occupied_count:]
        stops = schedule["stops"]
        first = stops[0]
        mid = stops[1]
        last = stops[-1]
        config_schedules.append(
            {
                "scheduleId": int(schedule["schedule_id"]),
                "trainId": int(schedule["train_id"]),
                "operationDate": format_mysql_date(schedule["operation_date"]),
                "departureTime": format_mysql_time(schedule["departure_time"]),
                "operationStatus": str(schedule["operation_status"]),
                "departureStationId": int(first["station_id"]),
                "midStationId": int(mid["station_id"]),
                "arrivalStationId": int(last["station_id"]),
                "departureStopOrder": int(first["stop_order"]),
                "midStopOrder": int(mid["stop_order"]),
                "arrivalStopOrder": int(last["stop_order"]),
                "seatIds": [int(seat["seat_id"]) for seat in seats],
                "occupiedSeatIds": [int(seat["seat_id"]) for seat in occupied],
                "openSeatIds": [int(seat["seat_id"]) for seat in open_seats],
                "carType": str(seats[0]["car_type"]) if seats else "",
                "seatDistributionByCarType": seat_distribution(seats),
                "occupancy": round(len(occupied) / len(seats), 4) if seats else 0,
            }
        )

    return {
        "branch": branch,
        "memberPassword": MEMBER_PASSWORD,
        "memberNoStart": MEMBER_NO_START,
        "memberNoEnd": MEMBER_NO_END,
        "members": [str(member["member_no"]) for member in members],
        "schedules": config_schedules,
    }


def write_json(path: str, data: dict[str, Any]) -> None:
    output = Path(path)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")


def write_seed_report(
    path: str,
    args: argparse.Namespace,
    config: dict[str, Any],
    deleted: dict[str, int],
) -> None:
    output = Path(path)
    output.parent.mkdir(parents=True, exist_ok=True)

    lines = [
        "# Booking Performance Seed Report",
        "",
        f"- Branch: `{args.branch}`",
        f"- Schema: `{args.schema}`",
        f"- Generated at: `{datetime.now().isoformat(timespec='seconds')}`",
        f"- Member count: `{len(config['members'])}`",
        f"- Member password: `{MEMBER_PASSWORD}`",
        f"- Cleanup skipped: `{args.no_cleanup}`",
        f"- Seed skipped: `{args.no_seed}`",
        "",
        "## Deleted Rows",
        "",
    ]
    if deleted:
        lines.extend(f"- `{table}`: `{count}`" for table, count in deleted.items())
    else:
        lines.append("- No cleanup executed.")

    lines.extend(["", "## Schedules", ""])
    for schedule in config["schedules"]:
        lines.extend(
            [
                f"### Schedule `{schedule['scheduleId']}`",
                "",
                f"- Train ID: `{schedule['trainId']}`",
                f"- Operation date: `{schedule['operationDate']}`",
                f"- Departure time: `{schedule['departureTime']}`",
                f"- Operation status: `{schedule['operationStatus']}`",
                f"- Car type: `{schedule['carType']}`",
                f"- Selected seats: `{len(schedule['seatIds'])}`",
                f"- Occupied seats: `{len(schedule['occupiedSeatIds'])}`",
                f"- Open seats: `{len(schedule['openSeatIds'])}`",
                f"- Occupancy: `{schedule['occupancy']}`",
                f"- Departure station: `{schedule['departureStationId']}`",
                f"- Mid station: `{schedule['midStationId']}`",
                f"- Arrival station: `{schedule['arrivalStationId']}`",
                f"- Seat distribution by car type: `{schedule['seatDistributionByCarType']}`",
                "",
            ]
        )

    output.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    args = parse_args()
    print(f"[INFO] branch={args.branch} schema={args.schema} host={args.host}:{args.port}")
    conn = connect(args)
    deleted: dict[str, int] = {}
    try:
        if not args.no_cleanup:
            deleted = cleanup(conn, args.branch)
            print(f"[OK] cleanup tables={len(deleted)}")

        members = load_members(conn, args.member_limit)
        validate_members(conn, args.branch)
        print(f"[OK] members={len(members)}")

        database_now = load_database_now(conn)
        departure_cutoff = database_now + timedelta(minutes=args.departure_buffer_minutes)
        schedules = load_schedule_candidates(conn, args.min_seats, args.schedule_count, departure_cutoff)
        print(
            "[OK] selected schedules={} departure_cutoff={}".format(
                len(schedules),
                departure_cutoff.isoformat(timespec="seconds"),
            )
        )

        if not args.no_seed:
            for schedule in schedules:
                seats = schedule["seats"]
                occupied_count = max(1, int(len(seats) * args.occupancy))
                occupied = seats[:occupied_count]
                if args.branch == "v1":
                    seed_v1(conn, schedule, occupied, members)
                else:
                    seed_develop(conn, schedule, occupied, members)
                print(
                    "[OK] seeded schedule={} occupied={} total={} ratio={:.2f}".format(
                        schedule["schedule_id"],
                        occupied_count,
                        len(seats),
                        occupied_count / len(seats),
                    )
                )

        config = build_config(args.branch, members, schedules, args.occupancy)
        write_json(args.output, config)
        write_seed_report(args.seed_report, args, config, deleted)
        print(f"[OK] wrote {args.output}")
        print(f"[OK] wrote {args.seed_report}")
    finally:
        conn.close()


if __name__ == "__main__":
    main()
