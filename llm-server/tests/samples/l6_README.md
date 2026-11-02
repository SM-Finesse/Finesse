# light-6call 측정 샘플 (합성, 실제 유저 아님)

보통 범위는 config/light_quartiles.json(31번 2.2절 실제 Q1·Q3) 기준.

- `l6_typical.json` — 일반: 범위 밖 3개(역전 +0.7, Stride +0.3, Weighted APP +0.3 동점→고정 순서)
- `l6_group.json` — 그룹 함정: 1·2위가 같은 공격 그룹(APP +0.9, Weighted +0.8) → 정답 APP·Opener·Cheese
- `l6_nullstyle.json` — 경계 ③⑦: 플레이스타일 4개 null + strength_split 양수
- `l6_nearzero.json` — 경계 ①⑥⑧: 역전 생략 + 0 근처 값 + session 0.0, Weighted 는 범위 밖이지만 +0.0
- `l6_negative.json` — 음수 위주: 역전 −1.1, 강도 −0.9, VS/APM −0.6 …
- `l6_mixed.json` — 부호 섞임: Cheese +0.7, session +0.5, Inf DS +0.4, Plonk −0.2 …
