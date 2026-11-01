"""
공공데이터 화장실 CSV → 앱 내장 DB(app/src/main/assets/toilets.json) 변환.

사용법:
    python tools/convert_toilets.py <CSV 파일> [<CSV 파일> ...] [--type PUBLIC|REST_AREA|GAS_STATION]
                                    [--hours-from <좌표 없는 최신 CSV>]

- 좌표가 있는 공공데이터포털 "전국공중화장실표준데이터"(data.go.kr/data/15012892) CSV를 기준으로 한다.
  (컬럼: 구분, 화장실명, 소재지도로명주소, 소재지지번주소, 개방시간, 위도, 경도 ...)
- --hours-from: 행정안전부 "공중화장실정보"처럼 좌표는 없지만 더 최신인 CSV.
  주소가 같은 곳은 그 파일의 개방시간으로 바꾸고, "미개방"이면 뺀다.
- 이동화장실(행사용 임시 화장실)은 뺀다.
- 지자체마다 컬럼 이름이 조금씩 달라서 아래 COLUMN_CANDIDATES의 후보 중 먼저 찾은 것을 쓴다.
- 인코딩은 UTF-8(BOM 포함)과 CP949를 자동으로 시도한다.
- 여러 파일을 넣으면 합치고, 같은 이름·좌표(소수점 5자리)는 하나만 남긴다.
- 기존 toilets.json은 덮어쓴다 (샘플 데이터도 사라짐).
- 파일마다 종류를 붙이려면 "경로@종류"로 쓴다. 예: data/고속도로휴게소.csv@REST_AREA
  (고속도로 휴게소·졸음쉼터는 tools/fetch_highway.py로 받는다)
- 종류가 PUBLIC인 파일도 이름에 "주유소"·"충전소"가 있으면 GAS_STATION, "휴게소"가 있으면 REST_AREA로 바꾼다.
  공중화장실 데이터에 개방화장실로 등록된 주유소가 많다.
- REST_AREA 파일의 지점 300m 안에 이름에 "휴게소"가 든 기존 항목이 있으면 같은 곳으로 보고 기존 것을 뺀다
  (도로공사 데이터가 방향 정보가 있어 더 정확하다).

전체 예:
    python tools/fetch_highway.py
    python tools/convert_toilets.py data/전국공중화장실표준데이터.csv data/고속도로휴게소.csv@REST_AREA \
        data/졸음쉼터.csv@REST_AREA --hours-from 공중화장실정보.csv
"""

import argparse
import csv
import json
import math
import re
import sys
from collections import defaultdict
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "toilets.json"

COLUMN_CANDIDATES = {
    "name": ["화장실명", "시설명", "휴게소명", "명칭"],
    "road_address": ["소재지도로명주소", "도로명주소", "주소"],
    "lot_address": ["소재지지번주소", "지번주소"],
    "open": ["개방시간", "개방시간상세", "운영시간"],
    "lat": ["WGS84위도", "위도", "LAT", "lat", "Y좌표"],
    "lng": ["WGS84경도", "경도", "LNG", "lng", "LON", "X좌표"],
    "kind": ["구분", "구분명"],
}

EXCLUDED_KINDS = {"이동화장실"}
# 정보가 없다는 뜻인 개방시간 값
EMPTY_HOURS = {"-", ":~:", "데이터없음", "없음", "주"}

# 시도 이름 → 짧은 이름. 데이터마다 "서울특별시"/"서울", "전라남도"/"전남광주통합특별시" 식으로 달라서 맞춘다.
SIDO = [
    ("서울", "서울"), ("부산", "부산"), ("대구", "대구"), ("인천", "인천"), ("광주광역시", "광주"),
    ("대전", "대전"), ("울산", "울산"), ("세종", "세종"), ("경기", "경기"), ("강원", "강원"),
    ("충청북", "충북"), ("충북", "충북"), ("충청남", "충남"), ("충남", "충남"),
    ("전라북", "전북"), ("전북", "전북"), ("전라남", "전남"), ("전남", "전남"),
    ("경상북", "경북"), ("경북", "경북"), ("경상남", "경남"), ("경남", "경남"), ("제주", "제주"),
]


def address_key(address: str) -> str:
    """주소 비교용 키. 공백을 없애고 시도 이름을 짧게 맞춘다."""
    a = re.sub(r"\s+", "", address or "")
    if not a:
        return ""
    for prefix, short in SIDO:
        if a.startswith(prefix):
            rest = re.sub(r"^[가-힣]*?(특별시|광역시|특별자치도|특별자치시|통합특별시|도)", "", a, count=1)
            return short + rest
    return a


GAS_WORDS = ("주유소", "충전소")
REST_WORDS = ("휴게소",)
SAME_PLACE_METERS = 300


def classify(name: str, default_type: str) -> str:
    """PUBLIC 데이터 안의 주유소·휴게소를 이름으로 골라낸다."""
    if default_type != "PUBLIC":
        return default_type
    if any(w in name for w in GAS_WORDS):
        return "GAS_STATION"
    if any(w in name for w in REST_WORDS):
        return "REST_AREA"
    return default_type


def distance_m(lat1, lng1, lat2, lng2):
    r = 6_371_000
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def drop_duplicate_rest_areas(items, highway):
    """도로공사 지점 근처에 있는 같은 휴게소(이름에 '휴게소')를 기존 목록에서 뺀다."""
    grid = defaultdict(list)
    for h in highway:
        grid[(round(h["lat"], 2), round(h["lng"], 2))].append(h)

    def near_highway(it):
        la, ln = round(it["lat"], 2), round(it["lng"], 2)
        for dla in (-0.01, 0, 0.01):
            for dln in (-0.01, 0, 0.01):
                for h in grid.get((round(la + dla, 2), round(ln + dln, 2)), []):
                    if distance_m(it["lat"], it["lng"], h["lat"], h["lng"]) < SAME_PLACE_METERS:
                        return True
        return False

    # 공중화장실 데이터에서 온 것만 지운다. 도로공사 휴게소끼리(휴게소 옆 졸음쉼터 등)는 둘 다 남긴다.
    kept = [it for it in items
            if not (it["_source"] == "PUBLIC" and "휴게소" in it["name"] and near_highway(it))]
    return kept, len(items) - len(kept)


def clean_hours(value: str) -> str:
    v = (value or "").strip()
    return "" if v in EMPTY_HOURS else v

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


def load_hours(path: Path):
    """좌표 없는 최신 CSV → {주소키: [행, ...]}"""
    by_address = defaultdict(list)
    for row in read_rows(path):
        for col in ("소재지도로명주소", "소재지지번주소"):
            key = address_key(row.get(col, ""))
            if key:
                by_address[key].append(row)
    return by_address


def newer_hours(by_address, name: str, addresses):
    """같은 주소의 최신 개방시간. 못 찾거나 애매하면 None, 닫힌 곳이면 "미개방"."""
    candidates = []
    for a in addresses:
        candidates = by_address.get(address_key(a), [])
        if candidates:
            break
    if not candidates:
        return None
    same_name = [r for r in candidates if re.sub(r"\s+", "", r.get("화장실명", "")) == re.sub(r"\s+", "", name)]
    if same_name:
        row = same_name[0]
    elif len(candidates) == 1:
        row = candidates[0]
    else:
        return None  # 한 건물에 여러 화장실이 있으면 어느 것인지 알 수 없다
    if (row.get("개방시간") or "").strip() == "미개방":
        return "미개방"
    detail = clean_hours(row.get("개방시간상세", ""))
    if detail and detail != "미개방":
        return detail
    if (row.get("개방시간") or "").strip() == "상시":
        return "상시"
    return None


def convert(path: Path, toilet_type: str, hours_by_address=None):
    rows = read_rows(path)
    if not rows:
        return [], 0
    header = rows[0].keys()
    cols = {k: pick(header, k) for k in COLUMN_CANDIDATES}
    missing = [k for k in ("name", "lat", "lng") if cols[k] is None]
    if missing:
        sys.exit(f"{path.name}: 필수 컬럼을 찾지 못함 {missing}. 실제 헤더: {list(header)}")

    out, skipped = [], 0
    stats = {"hours_updated": 0, "closed_removed": 0, "kind_removed": 0}
    for row in rows:
        if cols["kind"] and (row.get(cols["kind"]) or "").strip() in EXCLUDED_KINDS:
            stats["kind_removed"] += 1
            continue
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
        open_hours = clean_hours(row.get(cols["open"])) if cols["open"] else ""
        if hours_by_address is not None:
            addresses = [row.get(cols[k], "") for k in ("road_address", "lot_address") if cols[k]]
            newer = newer_hours(hours_by_address, name, addresses)
            if newer == "미개방":
                stats["closed_removed"] += 1
                continue
            if newer and newer != open_hours:
                open_hours = newer
                stats["hours_updated"] += 1
        out.append({
            "name": name,
            "address": address,
            "lat": round(lat, 6),
            "lng": round(lng, 6),
            "type": classify(name, toilet_type),
            "open": open_hours,
            "_source": toilet_type,  # 중복 제거용, 저장 전에 지운다
        })
    if hours_by_address is not None or stats["kind_removed"]:
        print(f"  개방시간 갱신 {stats['hours_updated']}건, 미개방 제외 {stats['closed_removed']}건, "
              f"이동화장실 제외 {stats['kind_removed']}건")
    return out, skipped


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("csv", nargs="+", help="CSV 경로. 종류를 따로 주려면 경로@REST_AREA 처럼 쓴다")
    parser.add_argument("--type", default="PUBLIC", choices=["PUBLIC", "REST_AREA", "GAS_STATION"])
    parser.add_argument("--hours-from", type=Path, help="좌표 없는 최신 CSV (개방시간 갱신용)")
    args = parser.parse_args()

    hours = load_hours(args.hours_from) if args.hours_from else None

    merged, seen, total_skipped = [], set(), 0
    for spec in args.csv:
        path_str, typ = spec.rsplit("@", 1) if "@" in spec else (spec, args.type)
        if typ not in ("PUBLIC", "REST_AREA", "GAS_STATION"):
            sys.exit(f"알 수 없는 종류: {typ}")
        path = Path(path_str)
        # 최신 개방시간 보강은 공중화장실 데이터에만 (휴게소·졸음쉼터는 24시간)
        items, skipped = convert(path, typ, hours if typ == "PUBLIC" else None)
        total_skipped += skipped
        if typ == "REST_AREA":
            merged, dropped = drop_duplicate_rest_areas(merged, items)
            if dropped:
                print(f"  기존 목록에서 같은 휴게소 {dropped}건을 도로공사 데이터로 교체")
        for item in items:
            key = (item["name"], round(item["lat"], 5), round(item["lng"], 5))
            if key in seen:
                continue
            seen.add(key)
            merged.append(item)
        print(f"{path.name}: {len(items)}건 변환, {skipped}건 제외")

    from collections import Counter
    print("종류별:", dict(Counter(i["type"] for i in merged)))
    for i, item in enumerate(merged):
        item.pop("_source", None)
        item["id"] = f"{item['type'].lower()}-{i}"

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(merged, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"총 {len(merged)}건 → {OUT} ({OUT.stat().st_size / 1024:.0f} KB), 제외 {total_skipped}건")


if __name__ == "__main__":
    main()
