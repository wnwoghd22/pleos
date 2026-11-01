"""
고속도로 휴게소·졸음쉼터 위치를 받아 convert_toilets.py가 읽을 수 있는 CSV로 저장한다.

사용법:
    python tools/fetch_highway.py [--ex-key <한국도로공사 API 키>]

만드는 파일 (data/ 아래, 저장소에는 넣지 않음):
    고속도로휴게소.csv  — 한국도로공사 공공데이터 "휴게소 위치정보" API (data.ex.co.kr)
    졸음쉼터.csv        — 공공데이터포털 "전국졸음쉼터표준데이터" (data.go.kr/data/15028203), 화장실 있는 곳만

- 휴게소 API는 키가 필요하다. 기본값 "test"는 data.ex.co.kr이 공개한 시험용 키로, 데이터를 한 번 만드는 용도로는 충분하다.
  계속 쓸 거면 data.ex.co.kr에서 키를 받아 --ex-key로 넣는다.
- 고속도로 휴게소는 방향별로 따로 있다 (예: "서울만남(부산)휴게소"는 부산 방향에서만 들어갈 수 있다).
  어느 쪽이 실제로 빠른지는 앱이 내비 ETA로 판단한다.
"""

import argparse
import csv
import json
import sys
import urllib.parse
import urllib.request
import http.cookiejar
from pathlib import Path

DATA = Path(__file__).resolve().parent.parent / "data"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
HEADER = ["구분", "화장실명", "소재지도로명주소", "위도", "경도", "개방시간"]


def get_json(opener, url):
    with opener.open(url, timeout=60) as r:
        return json.loads(r.read().decode("utf-8"))


def fetch_rest_areas(key: str):
    op = urllib.request.build_opener()
    op.addheaders = [("User-Agent", UA)]
    rows, page = [], 1
    while True:
        q = urllib.parse.urlencode({"key": key, "type": "json", "numOfRows": 99, "pageNo": page})
        res = get_json(op, f"https://data.ex.co.kr/openapi/locationinfo/locationinfoRest?{q}")
        if res.get("code") != "SUCCESS":
            sys.exit(f"휴게소 API 실패: {res.get('message')}")
        items = res.get("list") or []
        rows += items
        if not items or len(rows) >= int(res.get("count", 0)):
            break
        page += 1
    out = []
    for it in rows:
        name = (it.get("unitName") or "").strip()
        if not name or not it.get("xValue") or not it.get("yValue"):
            continue
        route = (it.get("routeName") or "").strip()
        out.append(["휴게소", name, f"{route} 고속도로".strip(), it["yValue"], it["xValue"], "24시간"])
    return out


def fetch_sleep_shelters():
    """data.go.kr 표준데이터는 사이트의 다운로드 버튼과 같은 경로로 받는다 (페이지는 1부터)."""
    pk = "15028203"
    base = "https://www.data.go.kr"
    jar = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    op.addheaders = [("User-Agent", UA), ("X-Requested-With", "XMLHttpRequest"),
                     ("Referer", f"{base}/data/{pk}/standard.do")]
    op.open(f"{base}/data/{pk}/standard.do").read()
    header = get_json(op, f"{base}/download/columList.json?pk={pk}&ext=CSV")
    cols = {c["columNm"]: c["columCode"] for c in header["columList"]}
    params = {"colNmList": header["tableVO"]["colNmList"], "totalCount": header["totalCount"],
              "svcTableNm": header["tableVO"]["svcTableNm"], "perPage": 10000, "page": 1}
    data = get_json(op, f"{base}/download/standard.json?publicDataPk={pk}&{urllib.parse.urlencode(params, doseq=True)}")

    def v(row, kr):
        return str(row.get(cols[kr]) or "").strip()

    out, no_toilet = [], 0
    for r in data:
        if v(r, "화장실유무").upper() != "Y":
            no_toilet += 1
            continue
        name = v(r, "졸음쉼터명")
        if "쉼터" not in name:
            name += " 졸음쉼터"
        direction = v(r, "도로노선방향")
        if direction and direction not in name:
            name += f"({direction})"
        out.append(["졸음쉼터", name, v(r, "소재지도로명주소") or v(r, "도로노선명"), v(r, "위도"), v(r, "경도"), "24시간"])
    print(f"졸음쉼터 {len(data)}곳 중 화장실 없음 {no_toilet}곳 제외")
    return out


def save(name, rows):
    DATA.mkdir(exist_ok=True)
    path = DATA / name
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(HEADER)
        w.writerows(rows)
    print(f"{path.name}: {len(rows)}곳")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ex-key", default="test", help="한국도로공사 공공데이터 API 키 (기본: 공개 시험용 test)")
    args = parser.parse_args()
    save("고속도로휴게소.csv", fetch_rest_areas(args.ex_key))
    save("졸음쉼터.csv", fetch_sleep_shelters())


if __name__ == "__main__":
    main()
