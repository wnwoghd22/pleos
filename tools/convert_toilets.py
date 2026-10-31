"""
공공데이터 화장실 CSV → 앱 내장 DB(app/src/main/assets/toilets.json) 변환.

사용법:
    python tools/convert_toilets.py <CSV 파일> [<CSV 파일> ...] [--type PUBLIC|REST_AREA|GAS_STATION]

- 공공데이터포털(data.go.kr)의 "공중화장실 정보" 표준데이터 CSV를 기준으로 한다.
  (컬럼: 화장실명, 소재지도로명주소, 소재지지번주소, 개방시간, 개방시간상세, WGS84위도, WGS84경도 ...)
- 지자체마다 컬럼 이름이 조금씩 달라서 아래 COLUMN_CANDIDATES의 후보 중 먼저 찾은 것을 쓴다.
- 인코딩은 UTF-8(BOM 포함)과 CP949를 자동으로 시도한다.
- 여러 파일을 넣으면 합치고, 같은 이름·좌표(소수점 5자리)는 하나만 남긴다.
- 기존 toilets.json은 덮어쓴다 (샘플 데이터도 사라짐).
"""

import argparse
import csv
import json
import sys
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "toilets.json"

COLUMN_CANDIDATES = {
    "name": ["화장실명", "시설명", "휴게소명", "명칭"],
    "road_address": ["소재지도로명주소", "도로명주소", "주소"],
    "lot_address": ["소재지지번주소", "지번주소"],
    "open": ["개방시간", "개방시간상세", "운영시간"],
    "lat": ["WGS84위도", "위도", "LAT", "lat", "Y좌표"],
    "lng": ["WGS84경도", "경도", "LNG", "lng", "LON", "X좌표"],
}

# 한국 영역 대략 범위. 좌표가 뒤바뀌거나 0인 행을 거르는 용도.
LAT_RANGE = (33.0, 39.0)
LNG_RANGE = (124.5, 132.0)


def read_rows(path: Path):
    for encoding in ("utf-8-sig", "cp949"):
        try:
            with path.open(encoding=encoding, newline="") as f:
                return list(csv.DictReader(f))
        except UnicodeDecodeError:
            continue
    sys.exit(f"인코딩을 알 수 없음: {path}")


def pick(header, key):
    for c in COLUMN_CANDIDATES[key]:
        if c in header:
            return c
    return None


def to_float(value):
    try:
        return float(str(value).strip())
    except (TypeError, ValueError):
        return None


def convert(path: Path, toilet_type: str):
    rows = read_rows(path)
    if not rows:
        return [], 0
    header = rows[0].keys()
    cols = {k: pick(header, k) for k in COLUMN_CANDIDATES}
    missing = [k for k in ("name", "lat", "lng") if cols[k] is None]
    if missing:
        sys.exit(f"{path.name}: 필수 컬럼을 찾지 못함 {missing}. 실제 헤더: {list(header)}")

    out, skipped = [], 0
    for row in rows:
        lat, lng = to_float(row.get(cols["lat"])), to_float(row.get(cols["lng"]))
        if lat is None or lng is None:
            skipped += 1
            continue
        if not (LAT_RANGE[0] <= lat <= LAT_RANGE[1] and LNG_RANGE[0] <= lng <= LNG_RANGE[1]):
            # 위도·경도가 뒤바뀐 데이터 보정
            if LAT_RANGE[0] <= lng <= LAT_RANGE[1] and LNG_RANGE[0] <= lat <= LNG_RANGE[1]:
                lat, lng = lng, lat
            else:
                skipped += 1
                continue
        name = (row.get(cols["name"]) or "").strip()
        if not name:
            skipped += 1
            continue
        address = ""
        for k in ("road_address", "lot_address"):
            if cols[k] and (row.get(cols[k]) or "").strip():
                address = row[cols[k]].strip()
                break
        open_hours = (row.get(cols["open"]) or "").strip() if cols["open"] else ""
        out.append({
            "name": name,
            "address": address,
            "lat": round(lat, 6),
            "lng": round(lng, 6),
            "type": toilet_type,
            "open": open_hours,
        })
    return out, skipped


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("csv", nargs="+", type=Path)
    parser.add_argument("--type", default="PUBLIC", choices=["PUBLIC", "REST_AREA", "GAS_STATION"])
    args = parser.parse_args()

    merged, seen, total_skipped = [], set(), 0
    for path in args.csv:
        items, skipped = convert(path, args.type)
        total_skipped += skipped
        for item in items:
            key = (item["name"], round(item["lat"], 5), round(item["lng"], 5))
            if key in seen:
                continue
            seen.add(key)
            merged.append(item)
        print(f"{path.name}: {len(items)}건 변환, {skipped}건 제외")

    for i, item in enumerate(merged):
        item["id"] = f"{item['type'].lower()}-{i}"

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(merged, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"총 {len(merged)}건 → {OUT} ({OUT.stat().st_size / 1024:.0f} KB), 제외 {total_skipped}건")


if __name__ == "__main__":
    main()
