"""heavy 대체 문장 (v3.4).

LLM 이 정해진 횟수(LLM_HEAVY_ATTEMPTS)만큼 검사를 통과하지 못하면, 서버가 data 값으로 정해진 문장을 만들어
보낸다. 각주가 비거나 틀린 문장이 나가는 것보다 낫다. 문장은 챕터마다 2가지 말투 중 하나를 고른다.

규칙
- 숫자는 data 에 있는 값만 쓴다 (계산은 부호 판단·%p 변환·라이벌 승률뿐 — prompt.py 와 같은 기준).
- 용어·방향 문구는 prompt.py 의 [용어]·[해석]과 같은 문서 근거를 따른다
  (데이터명세서 2절, 05 공식 해설 3·4·5·6·7.3·8절, 04 지표 명세 6.2·8.1절).
- 만든 문장은 테스트에서 process_heavy_output 검사를 통과해야 한다.
"""
import random

from app.prompt import PLAYSTYLE_MEANINGS, PLAYSTYLE_NAMES, _rival_badge

NO_DATA = "이 챕터를 설명할 값이 아직 충분하지 않습니다."


def _num(v) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool)


def _g(v: float) -> str:
    return f"{abs(v):g}"


def _s(v: float) -> str:
    return f"{v:+g}"


def _pick(options: list[str], rng: random.Random) -> str:
    return rng.choice(options)


def _tr_trend(d, rng):
    v = d.get("tr_trend_delta")
    if not _num(v):
        return None
    if v > 0:
        return _pick([f"최근 경기의 TR이 전체 기간 평균보다 {_g(v)}점 높습니다. 최근 들어 점수가 오르는 상승세입니다.",
                      f"최근 TR이 전체 기간 평균보다 {_g(v)}점 높아, 상승세를 보이고 있습니다."], rng)
    if v < 0:
        return _pick([f"최근 경기의 TR이 전체 기간 평균보다 {_g(v)}점 낮습니다. 최근 들어 점수가 내려가는 하락세입니다.",
                      f"최근 TR이 전체 기간 평균보다 {_g(v)}점 낮아, 하락세를 보이고 있습니다."], rng)
    return "최근 TR이 전체 기간 평균과 같아, 뚜렷한 상승세나 하락세가 없습니다."


def _playstyle(d, rng):
    vals = {name: d.get(k) for k, name in PLAYSTYLE_NAMES.items() if _num(d.get(k))}
    strong = [f"{n}({_s(v)})" for n, v in vals.items() if v > 0]
    weak = [f"{n}({_s(v)})" for n, v in vals.items() if v < 0]
    first_strong = next((n for n, v in vals.items() if v > 0), None)
    first_weak = next((n for n, v in vals.items() if v < 0), None)
    if strong and weak:
        lead = _pick([f"상대와 비교하면 {', '.join(strong)} 성향이 강하고 {', '.join(weak)} 성향은 약합니다.",
                      f"상대보다 {', '.join(strong)} 성향이 강하고, {', '.join(weak)} 성향은 약한 편입니다."], rng)
        return f"{lead} 상대보다 {PLAYSTYLE_MEANINGS[first_strong]} 쪽 성향이 뚜렷하다는 뜻입니다."
    if strong:
        return (f"상대와 비교하면 {', '.join(strong)} 성향이 강합니다. "
                f"상대보다 {PLAYSTYLE_MEANINGS[first_strong]} 쪽 성향이 뚜렷하다는 뜻입니다.")
    if weak:
        return (f"상대와 비교하면 {', '.join(weak)} 성향이 약합니다. "
                f"상대보다 {PLAYSTYLE_MEANINGS[first_weak]} 쪽 성향이 덜하다는 뜻입니다.")
    if vals:
        return "상대와 비교해 네 가지 플레이스타일 성향에 차이가 없습니다."
    return None


def _word(v):  # 높/낮/같
    return "높" if v > 0 else "낮" if v < 0 else "같"


def _pair(d, k1, k2, name1, name2, meaning_hi, meaning_lo, rng):
    a, b = d.get(k1), d.get(k2)
    parts = []
    if _num(a):
        parts.append(f"{name1}이 상대보다 {_word(a)}습니다({_s(a)})." if a else f"{name1}은 상대와 같습니다.")
    if _num(b):
        if not b:
            parts.append(f"{name2}는 상대와 같습니다.")
        elif _num(a) and (a > 0) == (b > 0) and a:
            parts.append(f"{name2}도 상대보다 {_word(b)}아, {meaning_hi if b > 0 else meaning_lo}({_s(b)}).")
        else:
            joiner = _pick(["반면", "하지만"], rng) if _num(a) and a else ""
            parts.append(f"{joiner + ' ' if joiner else ''}{name2}는 상대보다 {_word(b)}아, "
                         f"{meaning_hi if b > 0 else meaning_lo}({_s(b)}).")
    return " ".join(parts) or None


def _attack(d, rng):
    return _pair(d, "delta_app", "delta_weighted_app", "블록당 공격량(APP)", "Weighted APP",
                 "Cheese Index로 보정한 공격 효율에서 앞섭니다", "Cheese Index로 보정한 공격 효율에서 밀립니다", rng)


def _defense(d, rng):
    return _pair(d, "delta_vs_apm", "delta_cheese_index", "공격 대비 수비 비율(VS/APM)", "Cheese Index",
                 "가비지 처리 성향이 강합니다", "가비지 처리 성향이 약합니다", rng)


def _strength_split(d, rng):
    v = d.get("strength_split")
    if not _num(v):
        return None
    p = round(abs(v) * 100)
    if v < 0:
        return _pick([f"강한 상대 구간 승률이 약한 상대 구간보다 {p}%p 낮습니다. 강한 상대를 만나면 승률이 떨어지는 편입니다.",
                      f"가장 강한 상대 구간의 승률이 가장 약한 상대 구간보다 {p}%p 낮습니다."], rng)
    if v > 0:
        return _pick([f"강한 상대 구간 승률이 약한 상대 구간보다 {p}%p 높습니다. 강한 상대에게 오히려 승률이 높은 드문 경우입니다.",
                      f"가장 강한 상대 구간의 승률이 가장 약한 상대 구간보다 {p}%p 높습니다."], rng)
    return "강한 상대 구간과 약한 상대 구간의 승률 차이가 없어, 상대 강도와 상관없이 고르게 이기는 편입니다."


def _comeback(d, rng):
    a, b = d.get("comeback_rate"), d.get("comeback_rate_against")
    opp, won = d.get("comeback_opportunities"), d.get("comeback_won")
    if not (_num(a) and _num(b)):
        if _num(a):
            return f"역전승률은 {a:g}입니다."
        return None
    if a > b:
        lead = f"역전승률({a:g})이 역전 허용률({b:g})보다 높습니다."
        tail = "불리한 경기를 뒤집는 힘이 유리한 경기를 내주는 경향보다 강합니다."
    elif a < b:
        lead = f"역전승률({a:g})이 역전 허용률({b:g})보다 낮습니다."
        tail = "불리한 경기를 뒤집는 힘이 유리한 경기를 내주는 경향보다 약합니다."
    else:
        lead = f"역전승률과 역전 허용률이 {a:g}로 같습니다."
        tail = "뒤진 경기를 뒤집는 힘과 앞선 경기를 내주는 경향이 비슷합니다."
    if _num(opp) and _num(won) and opp:
        tail = _pick([tail, f"점수가 뒤졌던 {opp:g}경기 중 {won:g}경기를 뒤집었습니다."], rng)
    return f"{lead} {tail}"


def _session(d, rng):
    v = d.get("session_vs_slope")
    if not _num(v):
        return None
    if v > 0:
        return _pick([f"라운드가 진행될수록 VS가 라운드당 약 {_g(v)}씩 오릅니다. 경기가 길어질수록 강해지는 흐름입니다.",
                      f"한 매치 안에서 VS가 라운드당 약 {_g(v)}씩 올라, 경기 후반으로 갈수록 강해집니다."], rng)
    if v < 0:
        return _pick([f"라운드가 진행될수록 VS가 라운드당 약 {_g(v)}씩 내립니다. 경기가 길어질수록 처지는 흐름입니다.",
                      f"한 매치 안에서 VS가 라운드당 약 {_g(v)}씩 내려, 경기 후반으로 갈수록 처집니다."], rng)
    return "라운드가 진행되어도 VS에 뚜렷한 변화가 없습니다. 경기 내내 비슷한 컨디션을 유지합니다."


def _rivals(d, rng):
    rivals = [r for r in (d.get("rivals") or []) if r.get("nickname_masked")]
    if not rivals:
        return "5경기 이상 만난 상대가 아직 없습니다."
    sentences = []
    for r in rivals[:2]:  # 각주는 1–2문장 (v1.2 5.3절)
        n = r["nickname_masked"]
        m, w, lo = r.get("matches") or 0, r.get("wins") or 0, r.get("losses") or 0
        rate = w / m if m else 0.0
        rec = f"{m}경기 {w}승 {lo}패(승률 {round(rate * 100)}%)"
        badge = _rival_badge(rate)
        sentences.append({
            "천적": f"{n}에게는 {rec}로 내가 자주 져서 천적입니다.",
            "우세": f"{n}에게는 {rec}로 내가 자주 이겨 우세합니다.",
            "접전": f"{n}와는 {rec}로 접전입니다.",
        }.get(badge, f"{n}와는 {rec}입니다."))
    return " ".join(sentences)


_BUILDERS = {
    "tr_trend": _tr_trend, "playstyle": _playstyle, "attack": _attack, "defense": _defense,
    "strength_split": _strength_split, "comeback_rate": _comeback, "session_vs_slope": _session,
    "rivals": _rivals,
}


def build_fallback(chapter_id: str, data: dict, rng: random.Random | None = None) -> str:
    """대체 각주. 값이 없으면 NO_DATA."""
    rng = rng or random.Random()
    try:
        return _BUILDERS[chapter_id](data or {}, rng) or NO_DATA
    except (TypeError, ValueError, ZeroDivisionError):
        return NO_DATA
