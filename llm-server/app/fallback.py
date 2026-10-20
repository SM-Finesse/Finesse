"""heavy 문장 틀 — 대체 문장(v3.4~)과 프롬프트 예시(v4.1~)를 같은 코드로 만든다.

- 대체 문장: LLM 이 정해진 횟수(LLM_HEAVY_ATTEMPTS)만큼 검사를 통과하지 못하면 서버가 data 값으로 만들어 보낸다.
- 예시: 같은 틀에서 숫자를 뺀 문장 (show=False). 입력의 방향·강도와 같은 문장이라 베껴도 틀리지 않는다.

규칙 (「코멘트 작성 가이드라인」 31번, 10/16 개정판)
- 숫자는 data 에 있는 값만, 입력 그대로 괄호에 쓴다. %·%p 로 바꾸지 않는다 (1.2-1, D2).
- 상대 비교 챕터(playstyle·attack·defense)는 1.3절 비교 표현 5개 중 하나를 문장마다 쓴다.
- 서술어: 우열(attack) 앞섭니다/밀립니다, 성향(playstyle·defense) 짙게/옅게·큰/작은 편 (1.3절).
- 강도: 보통 범위(2.3절 Q1~Q3) 안의 값은 "조금 … 차이는 크지 않습니다"로 과장하지 않는다 (2.2·2.3절).
- 전체 유저 분포는 문장에 섞지 않는다 — "보통 수준", "드문 경우" 같은 말을 쓰지 않는다 (1.3절).
- tr_trend·strength_split·session_vs_slope 에는 "상대보다"를 쓰지 않는다 (1.2-7).
- 라이벌은 경기 수·승·패만 (승률 계산 금지, 3.2절).
- 만든 문장은 테스트에서 process_heavy_output 검사를 통과해야 한다.
"""
import random

from app.prompt import (
    COMPARE_PHRASES,
    PLAYSTYLE_NAMES,
    _rival_badge,
    heavy_zone,
    strength_level,
)

NO_DATA = "이 챕터를 설명할 값이 아직 충분하지 않습니다."


def _num(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def _g(v: float) -> str:
    return f"{abs(v):g}"


def _s(v: float, nd: int = 2) -> str:
    """부호 있는 숫자. 10/20 백엔드 요청: 원값(-10.128) 대신 소수 둘째 자리(Cheese Index 는 첫째, 31번 2.1절).
    반올림하면 0 이 되는 작은 값(-0.004)은 0 으로 보이지 않게 한 자리 더 쓴다."""
    r = round(v, nd)
    while r == 0 and v != 0 and nd < 4:
        nd += 1
        r = round(v, nd)
    return f"{r:+.{nd}f}" if v != 0 else "0"


def _nd(key: str | None) -> int:
    return 1 if key == "delta_cheese_index" else 2


def _rate(x: float) -> str:
    """역전승률 등 0~1 비율 — 소수 둘째 자리."""
    return _s(x).lstrip("+")


def _pick(options: list[str], rng: random.Random) -> str:
    return rng.choice(options)


def _topic(name: str) -> str:
    """은/는 — 괄호 앞 한글 글자의 받침으로 고른다. 한글이 없으면(Weighted APP) '는'."""
    word = name.split("(")[0].strip()
    last = word[-1] if word else ""
    if "가" <= last <= "힣":
        return "은" if (ord(last) - 0xAC00) % 28 else "는"
    return "는"


def _subject(name: str) -> str:
    """이/가."""
    word = name.split("(")[0].strip()
    last = word[-1] if word else ""
    if "가" <= last <= "힣":
        return "이" if (ord(last) - 0xAC00) % 28 else "가"
    return "가"


def _and(name: str) -> str:
    """과/와."""
    return "과" if _subject(name) == "이" else "와"


class _V:
    """괄호 숫자. show=False 면 빈 문자열 (예시용)."""

    def __init__(self, show: bool):
        self.show = show

    def __call__(self, *values: float, keys: tuple = ()) -> str:
        if not self.show:
            return ""
        keys = tuple(keys) + (None,) * (len(values) - len(keys))
        return f"({', '.join(_s(v, _nd(k)) for v, k in zip(values, keys))})"


def _tr_trend(d, rng, base, show):
    v = d.get("tr_trend_delta")
    if not _num(v):
        return None
    r = round(abs(v))                                   # TR 은 정수 점수 (10/20 백엔드 요청)
    pts = (f" {r}점" if r else f" {abs(v):.1f}점") if show else ""
    if v > 0:
        return _pick([f"최근 구간 TR이 전체 평균보다{pts} 높습니다. 최근 들어 점수가 오르는 흐름입니다.",
                      f"최근 구간 TR이 전체 평균보다{pts} 높아, 오르는 흐름을 보입니다."], rng)
    if v < 0:
        return _pick([f"최근 구간 TR이 전체 평균보다{pts} 낮습니다. 최근 들어 점수가 내려가는 흐름입니다.",
                      f"최근 구간 TR이 전체 평균보다{pts} 낮아, 내려가는 흐름을 보입니다."], rng)
    return "최근 구간 TR이 전체 평균과 같아, 뚜렷한 오르내림이 없습니다."


def _playstyle(d, rng, base, show):
    vals = {(k, n): d.get(k) for k, n in PLAYSTYLE_NAMES.items() if _num(d.get(k))}
    if not vals:
        return None
    v_ = _V(show)
    fmt = (lambda items: ", ".join(f"{n}{v_(v, keys=(k,))}" for (k, n), v in items))
    high = [(kn, v) for kn, v in vals.items() if heavy_zone(kn[0], v) == "high"]
    low = [(kn, v) for kn, v in vals.items() if heavy_zone(kn[0], v) == "low"]
    mid = [(kn, v) for kn, v in vals.items() if heavy_zone(kn[0], v) not in ("high", "low")]
    if not show:  # 예시는 이름을 하나씩만 — 묶으면 모델이 둘째 이름을 반대쪽으로 옮겨 씀 (10/12)
        high, low, mid = high[:1], low[:1], mid[:1]
    sentences = []
    if high:
        sentences.append(f"{base} {fmt(high)} 성향이 짙게 나타납니다.")
    if low:
        sentences.append(f"반면 {fmt(low)} 성향은 상대보다 옅게 나타납니다." if sentences
                         else f"{base} {fmt(low)} 성향이 옅게 나타납니다.")
    if mid and len(sentences) < 2:
        if sentences:
            sentences.append(f"{fmt(mid)} 성향은 상대와 차이가 크지 않습니다.")
        else:
            sentences.append(f"{base} {fmt(mid)} 성향은 차이가 크지 않습니다.")
    return " ".join(sentences)


# 두 값 챕터의 서술어: (키, 영역) → 동사구. 영역 mid+/mid- 는 보통 범위 안 (2.2절 '보통': 과장하지 않음)
_ATTACK_VERB = {"high": "앞섭니다", "low": "밀립니다",
                "mid+": "조금 앞서지만 차이는 크지 않습니다", "mid-": "조금 밀리지만 차이는 크지 않습니다"}
_VS_VERB = {"high": "큰 편입니다", "low": "작은 편입니다",
            "mid+": "약간 큰 편이지만 차이는 크지 않습니다", "mid-": "약간 작은 편이지만 차이는 크지 않습니다"}
_CHEESE_VERB = {"high": "짙게 나타납니다", "low": "옅게 나타납니다",
                "mid+": "약간 짙지만 차이는 크지 않습니다", "mid-": "약간 옅지만 차이는 크지 않습니다"}


def _zone_key(key: str, v: float) -> str:
    z = heavy_zone(key, v)
    if z in ("high", "low"):
        return z
    return "mid+" if v > 0 else "mid-"


def _pair(d, chapter, rng, base, show):
    """두 값 챕터 (attack·defense). 방향과 강도가 같으면 한 문장으로 묶는다 (1.3절 "절이 둘 이상이면")."""
    v_ = _V(show)
    (k1, n1, verbs1), (k2, n2, verbs2) = _PAIRS[chapter]
    a, b = d.get(k1), d.get(k2)
    if _num(a) and _num(b) and a and b:
        za, zb = _zone_key(k1, a), _zone_key(k2, b)
        if za.startswith("mid") and zb.startswith("mid"):  # 둘 다 보통 범위 안 → 묶어서 약하게 (2.2절)
            return f"{base} {n1}{_and(n1)} {n2} 모두 차이가 크지 않습니다{v_(a, b, keys=(k1, k2))}."
        if za == zb and za in ("high", "low"):
            if chapter == "attack":
                return f"{base} {n1}{_and(n1)} {n2} 모두에서 {_ATTACK_VERB[za]}{v_(a, b, keys=(k1, k2))}."
            size = "큰" if za == "high" else "작은"
            return f"{base} {n1}{_and(n1)} {n2}{_subject(n2)} 모두 {size} 편입니다{v_(a, b, keys=(k1, k2))}."
    parts = []
    for k, n, verbs, v in ((k1, n1, verbs1, a), (k2, n2, verbs2, b)):
        if not _num(v):
            continue
        if v == 0:
            parts.append((f"{base} " if not parts else "") + f"{n}{_topic(n)} 상대와 같습니다.")
            continue
        verb = verbs[_zone_key(k, v)]
        if chapter == "attack":
            subj = f"{n}에서는"
        else:
            subj = f"{n}{_subject(n) if not parts else _topic(n)}"
        if not parts:
            parts.append(f"{base} {subj} {verb}{v_(v, keys=(k,))}.")
        else:
            same = _num(a) and _num(b) and a * b > 0  # 방향이 같으면 '반면'이 아니라 '또'
            joiner = "또" if same else _pick(["반면", "하지만"], rng)
            parts.append(f"{joiner} {subj} 상대보다 {verb}{v_(v, keys=(k,))}.")
    return " ".join(parts) or None


_PAIRS = {
    "attack": (("delta_app", "블록당 공격량(APP)", _ATTACK_VERB), ("delta_weighted_app", "Weighted APP", _ATTACK_VERB)),
    "defense": (("delta_vs_apm", "수비 비중(VS/APM)", _VS_VERB),
                ("delta_cheese_index", "가비지 처리 성향(Cheese Index)", _CHEESE_VERB)),
}


def _attack(d, rng, base, show):
    return _pair(d, "attack", rng, base, show)


def _defense(d, rng, base, show):
    return _pair(d, "defense", rng, base, show)


def _strength_split(d, rng, base, show):
    v = d.get("strength_split")
    if not _num(v):
        return None
    p = f"({_s(v)})" if show else ""
    head = "강한 상대 구간의 승률이 약한 상대 구간보다"
    # 2.3절: −0.395(Q1)보다 낮을 때만 "크게 떨어진다". 범위 안(−0.2 등)은 평범한 값이라 강조하지 않는다.
    return {
        "pos": f"{head} 오히려 높습니다{p}. 강한 상대를 만나도 잘 이기는 편입니다.",
        "zero": "강한 상대 구간과 약한 상대 구간의 승률 차이가 없어, 상대 강도와 상관없이 고르게 이기는 편입니다.",
        "low": f"{head} 크게 낮습니다{p}. 강한 상대를 만나면 승률이 크게 떨어지는 편입니다.",
        "mid": _pick([f"{head} 낮습니다{p}. 강한 상대를 만나면 승률이 떨어지는 편입니다.",
                      f"강한 상대를 만나면 약한 상대를 만날 때보다 승률이 떨어집니다{p}."], rng),
        "high": f"{head} 조금 낮습니다{p}. 차이가 크지 않아 강한 상대를 만나도 잘 버티는 편입니다.",
    }[strength_level(v)]


def _comeback(d, rng, base, show):
    a, b = d.get("comeback_rate"), d.get("comeback_rate_against")
    opp, won = d.get("comeback_opportunities"), d.get("comeback_won")
    if not (_num(a) and _num(b)):
        if _num(a):
            return f"역전승률은 {_rate(a)}입니다." if show else None
        return None
    # 0 은 괄호로 쓰지 않는다 — "역전승률(0)" 이 어색함 (10/20 백엔드 요청)
    pa = f"({_rate(a)})" if show and a else ""
    pb = f"({_rate(b)})" if show and b else ""
    if a > b:
        lead = f"역전승률{pa}이 역전 허용률{pb}보다 높습니다."
        tail = "불리한 경기를 뒤집는 힘이 유리한 경기를 내주는 경향보다 강합니다."
    elif a < b:
        lead = f"역전승률{pa}이 역전 허용률{pb}보다 낮습니다."
        tail = "불리한 경기를 뒤집는 힘이 유리한 경기를 내주는 경향보다 약합니다."
    else:
        lead = (f"역전승률과 역전 허용률이 {_rate(a)}로 같습니다." if show and a
                else "역전승률과 역전 허용률이 같습니다.")
        tail = "뒤진 경기를 뒤집는 힘과 앞선 경기를 내주는 경향이 비슷합니다."
    if show and _num(opp) and _num(won) and opp:  # 표본 수 인용 가능 (3.1절)
        count = (f"점수가 뒤졌던 {opp:g}경기를 한 번도 뒤집지 못했습니다." if not won
                 else f"점수가 뒤졌던 {opp:g}경기 중 {won:g}경기를 뒤집었습니다.")
        tail = _pick([tail, count], rng)
    return f"{lead} {tail}"


def _session(d, rng, base, show):
    v = d.get("session_vs_slope")
    if not _num(v):
        return None
    per = f" 라운드당 약 {_s(v, 1).lstrip('+-')}씩" if show else ""   # 약 6.55 → 약 6.6 (10/20)
    z = heavy_zone("session_vs_slope", v)
    # 2.3절: 보통 범위가 거의 양수(중앙값 +0.557) — +0.5 정도는 평범한 값이라 강조하지 않는다
    if v == 0:
        return "라운드가 진행되어도 VS에 뚜렷한 추세가 없습니다."
    if z == "high":
        return f"라운드가 진행될수록 VS가{per} 크게 오릅니다. 경기가 길어질수록 강해지는 흐름입니다."
    if z == "low":
        return _pick([f"라운드가 진행될수록 VS가{per} 내립니다. 경기가 길어질수록 처지는 흐름입니다.",
                      f"한 매치 안에서 VS가{per} 내려, 경기 후반으로 갈수록 처집니다."], rng)
    p = f"({_s(v)})" if show else ""
    if v > 0:
        return f"라운드가 진행될수록 VS가 조금 오르지만{p}, 변화는 크지 않습니다."
    return f"라운드가 진행될수록 VS가 조금 내리지만{p}, 변화는 크지 않습니다."


def _rivals(d, rng, base, show):
    rivals = [r for r in (d.get("rivals") or []) if r.get("nickname_masked")]
    if not rivals:
        return "5경기 이상 만난 상대가 아직 없습니다."
    sentences = []
    for r in rivals[:2]:  # 각주는 1–2문장 (v1.2 5.3절)
        n = r["nickname_masked"]
        m, w, lo = r.get("matches") or 0, r.get("wins") or 0, r.get("losses") or 0
        rec = f"{m}경기 {w}승 {lo}패로 " if show else ""  # 승률은 쓰지 않는다 (3.2절)
        badge = _rival_badge(w / m if m else 0.0)
        sentences.append({
            "천적": f"{n}에게는 {rec}내가 자주 져서 천적입니다.",
            "우세": f"{n}에게는 {rec}내가 자주 이겨 우세합니다.",
            "접전": f"{n}와는 {rec}승패가 비슷한 접전입니다.",
        }.get(badge, f"{n}와는 {m}경기 {w}승 {lo}패입니다." if show
              else f"{n}와는 {'이긴' if w > lo else '진'} 경기가 조금 더 많습니다."))
    return " ".join(sentences)


_BUILDERS = {
    "tr_trend": _tr_trend, "playstyle": _playstyle, "attack": _attack, "defense": _defense,
    "strength_split": _strength_split, "comeback_rate": _comeback, "session_vs_slope": _session,
    "rivals": _rivals,
}


def build_heavy_text(chapter_id: str, data: dict, rng: random.Random | None = None,
                     base: str | None = None, show: bool = True) -> str | None:
    """문장 틀. show=False 면 숫자 없는 예시. 값이 없으면 None."""
    rng = rng or random.Random()
    base = base or _pick(list(COMPARE_PHRASES), rng)
    try:
        return _BUILDERS[chapter_id](data or {}, rng, base, show)
    except (TypeError, ValueError, ZeroDivisionError):
        return None


def build_fallback(chapter_id: str, data: dict, rng: random.Random | None = None) -> str:
    """대체 각주. 값이 없으면 NO_DATA."""
    return build_heavy_text(chapter_id, data, rng) or NO_DATA
