#!/bin/bash
# 버전 올리기 전 전체 검사. 하나라도 실패하면 올리지 않는다.
#   bash tools/check.sh
set -o pipefail
cd "$(dirname "$0")/.."
OUT=app/build/check; rm -rf "$OUT" app/build/screens; mkdir -p "$OUT"
fail=0; ok(){ echo "  ✅ $1"; }; bad(){ echo "  ❌ $1"; fail=1; }

echo "1) 단위 검사 + 화면 그리기 + 앱 빌드"
if SCREENSHOTS=1 gradle --no-daemon -q cleanTestDebugUnitTest testDebugUnitTest assembleRelease > "$OUT/gradle.log" 2>&1; then ok "빌드 성공"; else bad "빌드/검사 실패 (app/build/check/gradle.log)"; grep -E "^e: |FAILED|Test.*>" "$OUT/gradle.log" | head -20; fi
python3 - <<'PY' || fail=1
import glob,re,sys
t=f=s=0; names=[]
for x in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    h=open(x).read(); m=re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"',h)
    t+=int(m[1]); s+=int(m[2]); f+=int(m[3])+int(m[4])
    names+=re.findall(r'<testcase name="([^"]+)" classname="[^"]+"[^>]*>\s*<failure',h)
print(f"  {'✅' if f==0 and s==0 and t>0 else '❌'} 검사 {t}개 · 실패 {f} · 건너뜀 {s}")
for n in names: print("     실패:", n)
sys.exit(1 if f or s or t == 0 else 0)
PY

echo "2) 화면 모음 (눈으로 확인)"
python3 - <<'PY'
from PIL import Image
import glob,os
fs=sorted(glob.glob('app/build/screens/*.png'))
ims=[Image.open(f) for f in fs]; w=360
th=[im.resize((w,int(im.height*w/im.width))) for im in ims]
H=max(i.height for i in th); cols=6; rows=(len(th)+cols-1)//cols
sheet=Image.new('RGB',(cols*(w+10),rows*(H+10)),(60,60,60))
for k,i in enumerate(th): sheet.paste(i,((k%cols)*(w+10),(k//cols)*(H+10)))
sheet.save('app/build/check/screens.png'); print("  ✅ 화면",len(fs),"장 → app/build/check/screens.png")
PY
[ "$(ls app/build/screens/*.png 2>/dev/null | wc -l)" -ge 10 ] || bad "화면 그림이 안 만들어짐 (검사가 실제로 안 돌았음)"

echo "3) 콜 상세 지도 (키 없음 / 잘못된 키 / 내장 카카오 키)"
JS=$(python3 - <<'PY'
import re
s=open('app/src/main/java/com/daeri/navi/BuiltIn.kt').read()
M=[int(x,16) for x in re.search(r'M = intArrayOf\(([^)]*)\)',s)[1].replace('0x','').split(',')]
a=[int(x) for x in re.search(r'val js: String get\(\) = d\(intArrayOf\(([^)]*)\)',s)[1].split(',')]
print(''.join(chr(v^M[i%len(M)]) for i,v in enumerate(a)))
PY
)
all=$(node tools/map_check.js "" "$OUT/map_osm.png" 2>&1); r=$(echo "$all" | grep '^{' | head -1); echo "$r" | grep -q '"pins":2' && ok "기본 지도 핀 2개" || bad "기본 지도: $r"
echo "$all" | grep -q 'me-dot: 1' && ok "내 위치 점" || bad "내 위치 점 안 보임"
echo "$all" | grep -q 'focus-callout: 1' && ok "⌖ 확대 + 주소 말풍선" || bad "⌖ 확대 말풍선 안 보임"
r=$(node tools/map_check.js "BADKEY" "$OUT/map_bad.png" 2>&1 | grep '^{' | head -1); echo "$r" | grep -q '"leaflet":true' && echo "$r" | grep -q '"pins":2' && ok "잘못된 키 → 기본 지도로 바뀜" || bad "잘못된 키: $r"
r=$(node tools/map_check.js "$JS" "$OUT/map_kakao.png" 2>&1 | grep '^{' | head -1); echo "$r" | grep -q '"kakao":true' && echo "$r" | grep -q '"pins":2' && ok "카카오 지도 + 핀 2개" || bad "카카오 지도: $r (인터넷 막힘이면 휴대폰에서 확인)"

echo "4) 카카오 주소 찾기 (내장 REST 키)"
REST=$(python3 - <<'PY'
import re
s=open('app/src/main/java/com/daeri/navi/BuiltIn.kt').read()
M=[int(x,16) for x in re.search(r'M = intArrayOf\(([^)]*)\)',s)[1].replace('0x','').split(',')]
a=[int(x) for x in re.search(r'val rest: String get\(\) = d\(intArrayOf\(([^)]*)\)',s)[1].split(',')]
print(''.join(chr(v^M[i%len(M)]) for i,v in enumerate(a)))
PY
)
r=$(curl -s -m 10 -G "https://dapi.kakao.com/v2/local/search/keyword.json" --data-urlencode "query=가락동 다농마트" --data-urlencode size=1 -H "Authorization: KakaoAK $REST")
echo "$r" | grep -q '"place_name"' && ok "가락동 다농마트 → $(echo "$r" | python3 -c 'import sys,json;d=json.load(sys.stdin)["documents"][0];print(d["place_name"],d["address_name"])')" || bad "카카오 검색: ${r:0:120}"

r=$(curl -s -m 10 "https://dapi.kakao.com/v2/local/geo/coord2regioncode.json?x=127.0786&y=37.2507" -H "Authorization: KakaoAK $REST")
echo "$r" | grep -q '영통동' && ok "좌표 → 동네 이름 (대기위치): 영통동" || bad "동네 이름 찾기: ${r:0:120}"

echo "5) APK"
ls -la app/build/outputs/apk/release/app-release.apk >/dev/null 2>&1 && ok "APK $(stat -c%s app/build/outputs/apk/release/app-release.apk) bytes" || bad "APK 없음"

[ $fail = 0 ] && echo "=== 전체 통과 ===" || { echo "=== 실패 있음: 올리지 말 것 ==="; exit 1; }
