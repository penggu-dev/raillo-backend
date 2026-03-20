"""
k6 테스트용 스케줄 설정 자동 생성 스크립트

train search API를 호출해서 사용 가능한 스케줄을 찾고,
DB에서 좌석 범위를 조회하여 k6가 읽을 JSON 파일을 생성합니다.

사용법:
    pip install pymysql requests  (패키지 설치 필요)
    python3 test/db-scripts/generate_k6_schedule_config.py

옵션:
    --api-url          서버 URL (기본값: http://localhost:8080)
    --departure-station 출발역 ID (기본값: 2)
    --arrival-station   도착역 ID (기본값: 18)
    --date             운행 날짜 (기본값: 내일, YYYY-MM-DD)
    --hour             출발 시각 (기본값: 11)
    --count            선택할 스케줄 수 (기본값: 3)
    --host             DB 호스트 (기본값: localhost)
    --port             DB 포트 (기본값: 3306)
    --db               DB 이름 (기본값: raillo)
    --user             DB 유저 (기본값: root)
    --password         DB 비밀번호 (기본값: 1234)
    --output           출력 파일 경로 (기본값: k6/schedule-config.json)
    --cleanup          테스트 결제/예약 데이터 삭제 후 실행 (기본값: false)
    --env-from         .env 파일에서 DB 설정 로드 (기본값: false)
"""

import argparse
import json
import os
import sys
from datetime import date, timedelta

import pymysql
import requests


def load_env_file(env_path=".env"):
    """
    .env 파일을 읽어서 DB 접속 정보를 dict로 반환

    .env의 TEST_DB_URL (JDBC URL)에서 host, port, db를 파싱하고,
    TEST_DB_USERNAME, TEST_DB_PW에서 user, password를 읽는다.
    """
    if not os.path.isfile(env_path):
        print(f"[오류] .env 파일을 찾을 수 없습니다: {env_path}", file=sys.stderr)
        sys.exit(1)

    env_vars = {}
    with open(env_path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                continue
            key, _, value = line.partition("=")
            env_vars[key.strip()] = value.strip().strip("'\"")

    result = {}

    # TEST_DB_URL 파싱: jdbc:mysql://host:port/dbname?...
    db_url = env_vars.get("TEST_DB_URL", "")
    if db_url:
        # "jdbc:mysql://" 이후 부분에서 host, port, db 추출
        after_protocol = db_url.split("://", 1)[-1]       # host:port/dbname?...
        host_port_db = after_protocol.split("?", 1)[0]    # host:port/dbname
        host_port, _, db_name = host_port_db.partition("/")
        if ":" in host_port:
            result["host"], port_str = host_port.split(":", 1)
            result["port"] = int(port_str)
        else:
            result["host"] = host_port
        if db_name:
            result["db"] = db_name

    if "TEST_DB_USERNAME" in env_vars:
        result["user"] = env_vars["TEST_DB_USERNAME"]
    if "TEST_DB_PW" in env_vars:
        result["password"] = env_vars["TEST_DB_PW"]

    print(f"[설정] .env 파일 로드 완료: {env_path}")
    return result


def cleanup_test_data(conn):
    """
    이전 테스트에서 생성된 결제/예약 데이터를 삭제 (FK 순서 준수)

    Redis 티켓 카운터(ticketSeq:MMdd)가 compose 재시작 시 초기화되면서
    기존 DB의 ticket 레코드와 번호가 충돌하는 문제를 방지하기 위해,
    테스트 재실행 전에 결제/예약 관련 테이블을 비워준다.

    삭제 순서는 FK 의존관계의 역순:
      ticket → seat_booking → order_seat_booking → order_booking
      → booking → payment → orders

    사용법:
      python3 test/db-scripts/generate_k6_schedule_config.py --cleanup
    """
    cursor = conn.cursor()
    # FK 의존관계 역순으로 삭제해야 제약조건 위반이 발생하지 않음
    tables = [
        "ticket",              # booking → ticket
        "seat_booking",        # booking → seat_booking
        "order_seat_booking",  # order_booking → order_seat_booking
        "order_booking",       # orders → order_booking
        "booking",             # member → booking (ticket, seat_booking 먼저 삭제)
        "payment",             # orders → payment
        "orders",              # 최상위 (order_booking, payment 먼저 삭제)
    ]

    print("[정리] 테스트 결제/예약 데이터 삭제 시작")
    for table in tables:
        cursor.execute(f"DELETE FROM {table}")
        print(f"  {table}: {cursor.rowcount}건 삭제")
    conn.commit()
    cursor.close()
    print("[정리] 완료")


def search_schedules(api_url, departure_station, arrival_station, operation_date, hour):
    """train search API를 호출하여 사용 가능한 스케줄 목록을 조회"""
    url = f"{api_url}/api/v1/trains/search?page=0&size=10"
    payload = {
        "departureStationId": departure_station,
        "arrivalStationId": arrival_station,
        "operationDate": operation_date,
        "passengerCount": 1,
        "departureHour": hour,
    }

    print(f"[API] {url}")
    print(f"  출발역: {departure_station}, 도착역: {arrival_station}")
    print(f"  날짜: {operation_date}, 시각: {hour}시 이후")

    try:
        res = requests.post(url, json=payload, timeout=10)
    except requests.exceptions.ConnectionError:
        print(f"[오류] 서버 연결 실패: {api_url}", file=sys.stderr)
        sys.exit(1)

    if res.status_code != 200:
        print(f"[오류] API 응답 실패({res.status_code}): {res.text}", file=sys.stderr)
        sys.exit(1)

    body = res.json()
    # SuccessResponse 래핑 처리: result 또는 data 키 확인
    content = body.get("result", body.get("data", body))
    if isinstance(content, dict) and "content" in content:
        schedules = content["content"]
    else:
        schedules = content if isinstance(content, list) else []

    print(f"  → {len(schedules)}개 스케줄 조회됨")
    return schedules


def get_seat_ranges(conn, schedule_ids):
    """DB에서 각 스케줄의 좌석 범위를 조회"""
    if not schedule_ids:
        return []

    cursor = conn.cursor(pymysql.cursors.DictCursor)
    placeholders = ",".join(["%s"] * len(schedule_ids))

    sql = f"""
        SELECT
            ts.train_schedule_id AS schedule_id,
            MIN(s.seat_id) AS seat_start,
            MAX(s.seat_id) AS seat_end,
            COUNT(DISTINCT s.seat_id) AS total_seats
        FROM train_schedule ts
        JOIN train_car tc ON tc.train_id = ts.train_id
        JOIN seat s ON s.train_car_id = tc.train_car_id
        WHERE ts.train_schedule_id IN ({placeholders})
        GROUP BY ts.train_schedule_id
    """

    cursor.execute(sql, schedule_ids)
    results = cursor.fetchall()
    cursor.close()

    seat_map = {}
    for row in results:
        seat_map[row["schedule_id"]] = {
            "seatStart": row["seat_start"],
            "seatEnd": row["seat_end"],
            "totalSeats": row["total_seats"],
        }
    return seat_map


def get_schedule_stops(conn, schedule_ids):
    """DB에서 각 스케줄의 첫 번째/마지막 경유역을 조회"""
    if not schedule_ids:
        return {}

    cursor = conn.cursor(pymysql.cursors.DictCursor)
    placeholders = ",".join(["%s"] * len(schedule_ids))

    sql = f"""
        SELECT
            ss.train_schedule_id AS schedule_id,
            MIN(ss.stop_order) AS first_order,
            MAX(ss.stop_order) AS last_order
        FROM schedule_stop ss
        WHERE ss.train_schedule_id IN ({placeholders})
        GROUP BY ss.train_schedule_id
    """
    cursor.execute(sql, schedule_ids)
    order_map = {row["schedule_id"]: (row["first_order"], row["last_order"]) for row in cursor.fetchall()}

    # 첫 번째/마지막 stop의 station_id 조회
    stop_map = {}
    for sid in schedule_ids:
        if sid not in order_map:
            continue
        first_order, last_order = order_map[sid]

        cursor.execute(
            "SELECT station_id FROM schedule_stop WHERE train_schedule_id = %s AND stop_order = %s",
            (sid, first_order),
        )
        dep = cursor.fetchone()

        cursor.execute(
            "SELECT station_id FROM schedule_stop WHERE train_schedule_id = %s AND stop_order = %s",
            (sid, last_order),
        )
        arr = cursor.fetchone()

        if dep and arr:
            stop_map[sid] = {
                "departureStation": dep["station_id"],
                "arrivalStation": arr["station_id"],
            }

    cursor.close()
    return stop_map


def main():
    parser = argparse.ArgumentParser(description="k6 테스트용 스케줄 설정 자동 생성")
    parser.add_argument("--api-url", default=os.environ.get("API_URL", "http://localhost:8080"))
    parser.add_argument("--departure-station", type=int, default=2)
    parser.add_argument("--arrival-station", type=int, default=18)
    parser.add_argument("--date", default=None, help="운행 날짜 (기본값: 내일, YYYY-MM-DD)")
    parser.add_argument("--hour", default="11")
    parser.add_argument("--count", type=int, default=3, help="선택할 스케줄 수")
    parser.add_argument("--host", default=os.environ.get("DB_HOST", "localhost"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("DB_PORT", "3306")))
    parser.add_argument("--db", default=os.environ.get("DB_NAME", "raillo"))
    parser.add_argument("--user", default=os.environ.get("DB_USER", "root"))
    parser.add_argument("--password", default=os.environ.get("DB_PASSWORD", "1234"))
    parser.add_argument("--output", default="k6/schedule-config.json")
    parser.add_argument("--cleanup", action="store_true", default=False,
                        help="테스트 결제/예약 데이터 삭제 후 실행 (기본값: false)")
    parser.add_argument("--env-from", action="store_true", default=False,
                        help=".env 파일에서 DB 설정 로드 (기본값: false)")
    args = parser.parse_args()

    # --env-from: .env 파일에서 DB 설정 로드
    if args.env_from:
        env_db = load_env_file()
        args.host = env_db.get("host", args.host)
        args.port = env_db.get("port", args.port)
        args.db = env_db.get("db", args.db)
        args.user = env_db.get("user", args.user)
        args.password = env_db.get("password", args.password)

    # 0. --cleanup: 이전 테스트 데이터 정리
    if args.cleanup:
        try:
            conn = pymysql.connect(
                host=args.host, port=args.port, db=args.db,
                user=args.user, password=args.password, charset="utf8mb4",
            )
        except pymysql.Error as e:
            print(f"[오류] DB 연결 실패: {e}", file=sys.stderr)
            sys.exit(1)
        cleanup_test_data(conn)
        conn.close()

    # --date 미지정 시 내일 날짜 사용 (기본 시각 06시는 거의 첫차라 당일 검색 시 대부분 지난 시간)
    if args.date is None:
        args.date = (date.today() + timedelta(days=1)).isoformat()

    # 1. Train Search API 호출
    schedules = search_schedules(
        args.api_url, args.departure_station, args.arrival_station, args.date, args.hour
    )

    if not schedules:
        print("[오류] 조회된 스케줄이 없습니다. 날짜/역 설정을 확인해주세요.", file=sys.stderr)
        sys.exit(1)

    # 2. 스케줄 선택 (앞에서 count개)
    selected = schedules[: args.count]
    schedule_ids = [s["trainScheduleId"] for s in selected]

    print(f"\n[선택된 스케줄] {len(selected)}개:")
    for s in selected:
        print(f"  - ID: {s['trainScheduleId']}, {s.get('trainName', '')} {s.get('trainNumber', '')}, "
              f"{s.get('departureStationName', '')}→{s.get('arrivalStationName', '')}, "
              f"{s.get('departureTime', '')}~{s.get('arrivalTime', '')}")

    # 3. DB 연결 → 좌석 범위 & 경유역 조회
    try:
        conn = pymysql.connect(
            host=args.host, port=args.port, db=args.db,
            user=args.user, password=args.password, charset="utf8mb4",
        )
    except pymysql.Error as e:
        print(f"[오류] DB 연결 실패: {e}", file=sys.stderr)
        sys.exit(1)

    seat_map = get_seat_ranges(conn, schedule_ids)
    stop_map = get_schedule_stops(conn, schedule_ids)
    conn.close()

    # 4. 설정 파일 생성
    config = []
    for s in selected:
        sid = s["trainScheduleId"]
        if sid not in seat_map:
            print(f"  ⚠ 스케줄 {sid}: 좌석 정보 없음 (스킵)")
            continue

        seats = seat_map[sid]
        stops = stop_map.get(sid, {
            "departureStation": args.departure_station,
            "arrivalStation": args.arrival_station,
        })

        entry = {
            "scheduleId": sid,
            "departureStation": stops["departureStation"],
            "arrivalStation": stops["arrivalStation"],
            "seatStart": seats["seatStart"],
            "seatEnd": seats["seatEnd"],
            "totalSeats": seats["totalSeats"],
            "trainName": s.get("trainName", ""),
            "trainNumber": s.get("trainNumber", ""),
        }
        config.append(entry)
        print(f"  ✓ 스케줄 {sid}: 좌석 {seats['seatStart']}~{seats['seatEnd']} ({seats['totalSeats']}석)")

    if not config:
        print("[오류] 유효한 스케줄이 없습니다.", file=sys.stderr)
        sys.exit(1)

    # 5. JSON 파일 저장
    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(config, f, ensure_ascii=False, indent=2)

    print(f"\n[완료] {args.output} 생성됨 ({len(config)}개 스케줄)")
    print(f"  → k6 실행: K6_WEB_DASHBOARD=true k6 run k6/payment-metrics-test.js")


if __name__ == "__main__":
    main()
