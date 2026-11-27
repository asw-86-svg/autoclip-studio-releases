# 대리내비 광고 넣는 법

`ads.json` 파일을 고치면 앱 홈 화면 아래 광고 칸이 바뀝니다 (앱은 1시간마다 확인).
광고가 하나도 없으면(`"ads": []`) 광고 칸이 보이지 않습니다.

## 예시

```json
{
  "ads": [
    {
      "id": "insure-1",
      "title": "○○ 대리기사 보험",
      "text": "하루 3,000원부터 · 24시간 상담",
      "link": "tel:01012345678",
      "button": "📞 전화",
      "from": "2026-12-01",
      "until": "2026-12-31"
    },
    {
      "id": "shop-1",
      "image": "https://raw.githubusercontent.com/asw-86-svg/autoclip-studio-releases/claude/proxy-driving-app-yqzrsn/daeri-app/ads/shop-1.png",
      "link": "https://example.com",
      "weight": 2
    }
  ]
}
```

| 항목 | 뜻 |
|---|---|
| id | 광고 이름 (아무거나, 겹치지 않게) |
| title | 큰 글씨 (업체 이름) |
| text | 작은 설명 글씨 |
| link | 누르면 열 곳 — `tel:전화번호` 전화, `https://…` 사이트, `sms:번호` 문자 |
| button | 오른쪽 버튼 글자 (안 쓰면 전화는 "📞 전화", 그 외 "보기 ›") |
| image | 배너 그림 주소 (가로로 긴 그림, 예: 1080×300). 있으면 글자 대신 그림으로 |
| bg | 글자 광고 배경색 (예: `#1F3354`) |
| from / until | 광고 기간 (이 날짜 사이에만 보임) |
| weight | 광고가 여러 개일 때 보여주는 비율 (2면 2배 자주) |
| off | `true` 면 잠깐 끄기 |
