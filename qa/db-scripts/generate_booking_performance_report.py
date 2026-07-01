"""
Compare v1/develop k6 summary exports and write a markdown report.

Example:
  python3 qa/db-scripts/generate_booking_performance_report.py \
    --environment local \
    --v1 qa/results/booking-performance/local/v1-run-1.json qa/results/booking-performance/local/v1-run-2.json qa/results/booking-performance/local/v1-run-3.json \
    --develop qa/results/booking-performance/local/develop-run-1.json qa/results/booking-performance/local/develop-run-2.json qa/results/booking-performance/local/develop-run-3.json \
    --seed-v1 qa/results/booking-performance/local/seed-report-v1.md \
    --seed-develop qa/results/booking-performance/local/seed-report-develop.md \
    --output qa/results/booking-performance/local/comparison.md
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from statistics import median
from typing import Any


METRICS = {
    "requests": (("http_reqs",), "count"),
    "iterations_per_second": (("iterations",), "rate"),
    "duration_avg_ms": (("booking_duration", "http_req_duration"), "avg"),
    "duration_p90_ms": (("booking_duration", "http_req_duration"), "p(90)"),
    "duration_p95_ms": (("booking_duration", "http_req_duration"), "p(95)"),
    "duration_p99_ms": (("booking_duration", "http_req_duration"), "p(99)"),
    "duration_max_ms": (("booking_duration", "http_req_duration"), "max"),
    "booking_success": (("booking_success",), "count"),
    "booking_conflict": (("booking_conflict",), "count"),
    "booking_system_error": (("booking_system_error",), "count"),
    "login_failure": (("login_failure",), "count"),
}
SCRIPT_PATH = Path(__file__).resolve()
QA_ROOT = SCRIPT_PATH.parents[1]
PROJECT_ROOT = SCRIPT_PATH.parents[2]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Compare booking performance k6 summaries")
    parser.add_argument("--environment", default="local", help="Result environment label: local or eks")
    parser.add_argument("--v1", nargs="+", required=True, help="v1 k6 summary JSON files")
    parser.add_argument("--develop", nargs="+", required=True, help="develop/v2 k6 summary JSON files")
    parser.add_argument("--seed-v1", help="v1 seed report markdown")
    parser.add_argument("--seed-develop", help="develop seed report markdown")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    args.v1 = [resolve_input_path(path) for path in args.v1]
    args.develop = [resolve_input_path(path) for path in args.develop]
    args.seed_v1 = resolve_input_path(args.seed_v1) if args.seed_v1 else None
    args.seed_develop = resolve_input_path(args.seed_develop) if args.seed_develop else None
    args.output = resolve_output_path(args.output)
    return args


def resolve_input_path(path_value: str) -> Path:
    path = Path(path_value).expanduser()
    if path.is_absolute():
        return path

    candidates = [Path.cwd() / path]
    if path.parts and path.parts[0] == "qa":
        candidates.append(PROJECT_ROOT / path)
    else:
        candidates.extend([QA_ROOT / path, PROJECT_ROOT / path])

    for candidate in candidates:
        if candidate.is_file():
            return candidate

    return PROJECT_ROOT / path if path.parts and path.parts[0] == "qa" else Path.cwd() / path


def resolve_output_path(path_value: str) -> Path:
    path = Path(path_value).expanduser()
    if path.is_absolute():
        return path

    if path.parts and path.parts[0] == "qa":
        return PROJECT_ROOT / path

    try:
        Path.cwd().resolve().relative_to(QA_ROOT)
        return QA_ROOT / path
    except ValueError:
        return Path.cwd() / path


def read_summary(path: Path) -> dict[str, Any]:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    return extract_metrics(data, str(path))


def extract_metrics(data: dict[str, Any], path: str) -> dict[str, float]:
    metrics = data.get("metrics", {})
    result: dict[str, float] = {"source": path}
    for output_name, (metric_names, value_name) in METRICS.items():
        result[output_name] = first_metric_value(metrics, metric_names, value_name)
    return result


def first_metric_value(metrics: dict[str, Any], metric_names: tuple[str, ...], value_name: str) -> float:
    for metric_name in metric_names:
        if metric_name in metrics:
            return metric_value(metrics.get(metric_name, {}), value_name)
    return 0.0


def metric_value(metric: dict[str, Any], value_name: str) -> float:
    if value_name in metric:
        return float(metric.get(value_name, 0))

    values = metric.get("values", {})
    if value_name in values:
        return float(values.get(value_name, 0))

    return 0.0


def summarize(paths: list[Path]) -> dict[str, float]:
    runs = [read_summary(path) for path in paths]
    summary: dict[str, float] = {"run_count": float(len(runs))}
    for key in METRICS.keys():
        summary[key] = float(median(run[key] for run in runs))
    return summary


def percent_change(base: float, new: float, higher_is_better: bool) -> float:
    if base == 0:
        return 0.0
    if higher_is_better:
        return ((new - base) / base) * 100
    return ((base - new) / base) * 100


def read_optional(path: Path | None) -> str:
    if not path:
        return ""
    file = Path(path)
    if not file.exists():
        return ""
    return file.read_text(encoding="utf-8").strip()


def fmt(value: float, digits: int = 2) -> str:
    return f"{value:,.{digits}f}"


def build_report(args: argparse.Namespace, v1: dict[str, float], develop: dict[str, float]) -> str:
    p90_reduction = percent_change(v1["duration_p90_ms"], develop["duration_p90_ms"], higher_is_better=False)
    p95_reduction = percent_change(v1["duration_p95_ms"], develop["duration_p95_ms"], higher_is_better=False)
    p99_reduction = percent_change(v1["duration_p99_ms"], develop["duration_p99_ms"], higher_is_better=False)
    throughput_increase = percent_change(
        v1["iterations_per_second"], develop["iterations_per_second"], higher_is_better=True
    )
    avg_reduction = percent_change(v1["duration_avg_ms"], develop["duration_avg_ms"], higher_is_better=False)

    seed_v1 = read_optional(args.seed_v1)
    seed_develop = read_optional(args.seed_develop)

    lines = [
        "# 예약 API 성능 비교 결과",
        "",
        "## 측정 조건",
        "",
        f"- Environment: `{args.environment}`",
        "- 비교 대상: 결제 전 좌석 선택 사용자 행동",
        "- v1 API: `POST /api/v1/booking/reservation`",
        "- develop API: `POST /api/v1/pending-bookings`",
        "- 기존 예약 데이터: 대상 좌석 30% 선점",
        "- 반복 측정 방식: 각 run 직전 MySQL 예약/결제 데이터 reset + Redis flush",
        "- 대표값: 각 브랜치 3회 실행 중앙값",
        "- 충돌 응답: 서버 오류가 아닌 도메인 결과로 별도 집계",
        "",
        "## 결과 표",
        "",
        "| 항목 | v1 | develop | 변화 |",
        "|---|---:|---:|---:|",
        f"| 초당 처리 요청 수 | {fmt(v1['iterations_per_second'])}건/초 | {fmt(develop['iterations_per_second'])}건/초 | {fmt(throughput_increase)}% 증가 |",
        f"| 평균 응답 시간 | {fmt(v1['duration_avg_ms'])}ms | {fmt(develop['duration_avg_ms'])}ms | {fmt(avg_reduction)}% 감소 |",
        f"| p90 응답 시간 | {fmt(v1['duration_p90_ms'])}ms | {fmt(develop['duration_p90_ms'])}ms | {fmt(p90_reduction)}% 감소 |",
        f"| p95 응답 시간 | {fmt(v1['duration_p95_ms'])}ms | {fmt(develop['duration_p95_ms'])}ms | {fmt(p95_reduction)}% 감소 |",
        f"| p99 응답 시간 | {fmt(v1['duration_p99_ms'])}ms | {fmt(develop['duration_p99_ms'])}ms | {fmt(p99_reduction)}% 감소 |",
        f"| 예약 성공 수 | {fmt(v1['booking_success'], 0)}건 | {fmt(develop['booking_success'], 0)}건 | - |",
        f"| 좌석 충돌 수 | {fmt(v1['booking_conflict'], 0)}건 | {fmt(develop['booking_conflict'], 0)}건 | - |",
        "",
        "## 개선율",
        "",
        f"- p90 응답 시간 감소율: `{fmt(p90_reduction)}%`",
        f"- p95 응답 시간 감소율: `{fmt(p95_reduction)}%`",
        f"- p99 응답 시간 감소율: `{fmt(p99_reduction)}%`",
        f"- 초당 처리 요청 수 증가율: `{fmt(throughput_increase)}%`",
        "",
        "## 해석",
        "",
        "이번 벤치마크는 동일한 결제 전 좌석 선택 행동을 기준으로 v1의 예약 생성 API와 develop의 PendingBooking 생성 API를 비교했다. "
        "두 버전은 API 스펙과 DB 스키마가 다르므로 동일 구현의 A/B 테스트가 아니라, 같은 도메인 문제를 다른 아키텍처로 구현했을 때의 차이를 측정한 것이다.",
        "",
        "기존 예약 30% 선점 조건에서 v1은 물리 좌석 기준 비관적 락과 기존 예약 조회 비용이 p90, p95, p99 응답 시간에 반영될 수 있다. "
        "develop은 결제 전 임시 선점을 Redis TTL + Lua Script로 분리해 MySQL의 역할을 확정 예매 검증으로 줄였다.",
        "",
        "## 주의사항",
        "",
        "- 이 결과는 지정한 환경과 데이터 분포에서의 결과다.",
        "- 409 또는 이에 준하는 좌석 충돌 응답은 도메인 결과이며 서버 실패로 보지 않는다.",
        "- Redis가 항상 MySQL보다 빠르다는 주장이 아니라, 임시 좌석 선점 책임에는 Redis TTL + Lua가 더 적합하다는 해석이다.",
    ]

    if seed_v1 or seed_develop:
        lines.extend(["", "## Seed 요약", ""])
        if seed_v1:
            lines.extend(["### v1", "", seed_v1, ""])
        if seed_develop:
            lines.extend(["### develop", "", seed_develop, ""])

    return "\n".join(lines).rstrip() + "\n"


def main() -> None:
    args = parse_args()
    v1 = summarize(args.v1)
    develop = summarize(args.develop)
    report = build_report(args, v1, develop)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(report, encoding="utf-8")
    print(f"[OK] wrote {args.output}")


if __name__ == "__main__":
    main()
