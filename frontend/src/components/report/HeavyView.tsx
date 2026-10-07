import { useCallback, useState, type ReactNode } from 'react'
import { HEAVY_CHAPTERS, type HeavyChapterId, type StatsResponse } from '../../api/types'
import { footStateOf, type HeavyState } from '../../hooks/useHeavyComment'
import { useI18n } from '../../i18n/context'
import { retryWait } from '../../lib/retry'
import { sortRivals } from '../../lib/rivals'
import { evidenceOf, num, signed, STAT_META, TREND_MARK, type Evidence, type StatKey, type Trend } from '../../lib/stats'
import { RetryButton } from '../RetryButton'
import { Chapter, DataTable, DetailModal, PillInfo, StatBox, Strip, type Chip } from './heavy/Chapter'
import { ColdHeavy } from './heavy/ColdHeavy'
import { CHAPTER_COLORS, EYEBROWS } from './heavy/meta'
import { RivalBoard, RivalPolicy } from './heavy/Rivals'
import { ColumnChart, DivergingBars, Legend, LineChart, type LinePoint } from './heavy/svg'
import { Caption, DeltaPill, Notice } from './parts'
import { ProfilePanel } from './ProfilePanel'

type Detail = 'tr_trend' | 'playstyle' | 'session_vs_slope'

const PLAYSTYLE: StatKey[] = ['delta_opener', 'delta_plonk', 'delta_stride', 'delta_inf_ds']
const PLAYSTYLE_COLORS = ['#4FC3D9', '#D3BE55', '#B76AD0', '#8FC93A']
const STAT_COLOR: Record<Trend, string> = { up: 'var(--color-delta-up)', down: 'var(--color-delta-down)', even: 'var(--color-ink)' }

/** 막대 끝 눈금 — 가장 큰 값보다 조금 넉넉한 1·2·5 단위 */
function niceMax(v: number): number {
  const target = v * 1.1 || 1
  const p = 10 ** Math.floor(Math.log10(target))
  return [1, 2, 5, 10].map((f) => f * p).find((x) => x >= target) ?? 10 * p
}

/** 챕터 차트는 경기 단위 추이를 최대 15점으로 묶은 평균을 쓴다 — 축 글자는 그 구간의 마지막 경기 번호 */
function trBuckets(series: number[]): LinePoint[] {
  const per = Math.ceil(series.length / 15)
  const out: LinePoint[] = []
  for (let a = 0; a < series.length; a += per) {
    const seg = series.slice(a, a + per)
    const b = a + seg.length
    out.push({ label: String(b), head: `GAMES ${a + 1}–${b}`, values: { tr: Math.round(avg(seg)) } })
  }
  return out
}

const avg = (xs: number[]) => xs.reduce((a, b) => a + b, 0) / xs.length
const chipOf = (ev: Evidence, k = ev.meta.code): Chip => ({ k, v: ev.text })
const markText = (ev: Evidence) => (ev.trend ? `${ev.text} ${TREND_MARK[ev.trend]}` : ev.text)

function StreamError({ heavy }: { heavy: HeavyState & { retry: () => void } }) {
  const { t } = useI18n()
  if (heavy.status !== 'error' || !heavy.error) return null
  return (
    <div role="alert" className="panel flex flex-wrap items-center gap-3 bg-surface px-[22px] py-3.5">
      <p className="m-0 flex-1 text-sm text-muted">{heavy.error.code === 'SERVER_BUSY' ? t.statsErrors.busy : t.report.heavy.streamFailed}</p>
      <RetryButton
        wait={retryWait(heavy.error)}
        onRetry={heavy.retry}
        className="h-[34px] rounded border-2 border-[#5E90B0] bg-surface-2 px-3 text-sm text-ink transition-colors hover:bg-line hover:text-white disabled:cursor-wait disabled:border-line disabled:text-faint disabled:hover:bg-surface-2"
      />
    </div>
  )
}

/** 헤비 뷰(FR-03) — 8개 챕터. 챕터마다 차트 + AI 코멘트(SSE로 도착하는 대로) + 근거 칩 */
export function HeavyView({ data, heavy, onLight }: { data: StatsResponse; heavy: HeavyState & { retry: () => void }; onLight: () => void }) {
  const { t, lang } = useI18n()
  const h = t.report.heavy
  const [detail, setDetail] = useState<Detail | null>(null)
  const closeDetail = useCallback(() => setDetail(null), [])

  if (data.cold_start) return <ColdHeavy data={data} onLight={onLight} />

  const d = data.delta_metrics
  const ev = (k: StatKey) => evidenceOf(k, d)
  const nameOf = (k: StatKey) => STAT_META[k].code.replace(/^Δ/, '')
  const subOf = (k: StatKey) => (lang === 'ko' ? STAT_META[k].label.ko : STAT_META[k].code)
  const noData = <Notice title={h.noData} />

  /* 01 — TR 추이 */
  const series = data.fixed_metrics.tr_trend
  const tr = ev('tr_trend_delta')
  const buckets = series.length >= 2 ? trBuckets(series) : []
  const trSeries = [{ key: 'tr', label: h.trend.legendTr, color: '#66C0F4', fill: '#66C0F4', fmt: (v: number) => num(v) }]
  const trChips: Chip[] = []
  if (buckets.length) trChips.push({ k: h.trend.avgOf(series.length), v: num(Math.round(avg(series))) })
  else if (tr) trChips.push(chipOf(tr))

  /* 02 — 플레이스타일 */
  const ps = PLAYSTYLE.map((k, i) => ({ k, e: ev(k), color: PLAYSTYLE_COLORS[i] }))
  const psMax = niceMax(Math.max(...ps.map((p) => Math.abs(p.e?.value ?? 0))))
  const psItems = ps.map((p) => ({ k: nameOf(p.k), kr: subOf(p.k), v: p.e?.value, c: p.color, text: p.e?.text, trend: p.e?.trend ?? undefined }))
  const psTick = (v: number) => String(+v.toPrecision(2))
  const hasPs = ps.some((p) => p.e)

  /* 03·04·05 — 공격 / 수비 / 상대 강도: 스코어 칸 */
  const tile = (k: StatKey, sub: string) => {
    const e = ev(k)
    return <StatBox key={k} k={STAT_META[k].code} v={e ? markText(e) : '—'} s={sub} color={e?.trend ? STAT_COLOR[e.trend] : 'var(--color-muted)'} />
  }
  const app = ev('delta_app')
  const wapp = ev('delta_weighted_app')
  const vsApm = ev('delta_vs_apm')
  const cheese = ev('delta_cheese_index')
  const split = ev('strength_split')

  /* 06 — 역전 */
  const cb = ev('comeback_rate')
  const ca = ev('comeback_rate_against')
  const cbNet = ev('delta_comeback')
  const cbMax = Math.max(cb?.value ?? 0, ca?.value ?? 0) * 100 <= 50 ? 50 : 100
  /* 비율 아래에 "몇 번 중 몇 번" — 표본이 안 왔으면 설명만 */
  const cs = d?.comeback_samples
  const sampled = (sub: string, total?: number, hit?: number) => (total !== undefined && hit !== undefined ? `${sub} · ${h.comeback.of(total, hit)}` : sub)

  /* 07 — 경기 내 컨디션 */
  const vsCurve = data.round_curves.vs
  const ppsCurve = data.round_curves.pps.length === vsCurve.length ? data.round_curves.pps : []
  const slope = ev('session_vs_slope')
  const condData: LinePoint[] = vsCurve.map((v, i) => ({ label: `R${i + 1}`, head: `ROUND ${i + 1}`, values: { vs: v, ...(ppsCurve.length ? { pps: ppsCurve[i] } : {}) } }))
  const condSeries = [
    { key: 'vs', label: h.condition.avgVs, color: '#66C0F4', fill: '#66C0F4', fmt: (v: number) => v.toFixed(2) },
    ...(ppsCurve.length ? [{ key: 'pps', label: h.condition.ppsNorm, color: '#D3BE55', norm: true, dash: true, fmt: (v: number) => v.toFixed(2) }] : []),
  ]
  const condChips: Chip[] =
    vsCurve.length >= 2
      ? [
          { k: h.condition.first, v: vsCurve[0].toFixed(2) },
          { k: h.condition.last(vsCurve.length), v: vsCurve[vsCurve.length - 1].toFixed(2) },
          ...(ppsCurve.length ? [{ k: h.condition.ppsChange, v: signed(ppsCurve[ppsCurve.length - 1] - ppsCurve[0], 2) }] : []),
        ]
      : slope
        ? [chipOf(slope)]
        : []

  /* 08 — 자주 만난 상대 */
  const rivals = sortRivals(data.rivals.items)
  const r = h.rivals

  const present = (xs: (Evidence | null)[]) => xs.filter((e): e is Evidence => e !== null)
  const pill = (e: Evidence | null) => (e?.trend ? <DeltaPill text={e.text} trend={e.trend} /> : undefined)

  const chapters: Record<HeavyChapterId, { right?: ReactNode; detail?: boolean; chips: Chip[]; tail?: ReactNode; body: ReactNode }> = {
    tr_trend: {
      /* 알약을 누르면 이 값이 무엇인지(최근·전체 기준, 계산식) 펼친다 */
      right: tr?.trend ? (
        <PillInfo name={tr.meta.code} info={h.trend.deltaInfo}>
          <DeltaPill text={tr.text} trend={tr.trend} />
        </PillInfo>
      ) : undefined,
      detail: buckets.length > 0,
      chips: trChips,
      body: buckets.length ? (
        <>
          <LineChart data={buckets} series={trSeries} h={300} aria={h.trend.aria} onOpen={() => setDetail('tr_trend')} />
          <Legend items={trSeries} />
        </>
      ) : tr ? (
        <Caption>{h.trend.missing}</Caption>
      ) : (
        noData
      ),
    },
    playstyle: {
      right: <Caption>{h.playstyle.right}</Caption>,
      detail: hasPs,
      chips: ps.filter((p) => p.e).map((p) => chipOf(p.e!, nameOf(p.k))),
      body: hasPs ? <DivergingBars items={psItems} max={psMax} tick={psTick} aria={h.playstyle.aria} /> : noData,
    },
    attack: {
      right: pill(app),
      chips: present([app, wapp]).map((e) => chipOf(e)),
      body: app || wapp ? <Strip>{[tile('delta_app', h.sub.app), tile('delta_weighted_app', h.sub.wapp)]}</Strip> : noData,
    },
    defense: {
      right: pill(vsApm),
      chips: present([vsApm, cheese]).map((e) => chipOf(e)),
      body: vsApm || cheese ? <Strip>{[tile('delta_vs_apm', h.sub.vsapm), tile('delta_cheese_index', h.sub.cheese)]}</Strip> : noData,
    },
    strength_split: {
      right: <Caption>{h.split.right}</Caption>,
      chips: present([split]).map((e) => chipOf(e)),
      body: split ? <Strip>{tile('strength_split', h.split.sub)}</Strip> : noData,
    },
    comeback_rate: {
      right: pill(cbNet),
      chips: present([cb, ca]).map((e) => chipOf(e, e.stat === 'comeback_rate' ? 'Comeback Rate' : 'Comeback Allowed')),
      body:
        cb || ca ? (
          <>
            <Strip>
              <StatBox k="Comeback Rate" v={cb ? `${cb.text} ▲` : '—'} s={sampled(h.comeback.rateSub, cs?.comeback_opportunities, cs?.comeback_won)} color={cb ? 'var(--color-delta-up)' : 'var(--color-muted)'} info={h.comeback.rateInfo} />
              <StatBox k="Comeback Allowed" v={ca ? `${ca.text} ▼` : '—'} s={sampled(h.comeback.allowedSub, cs?.comeback_against_opportunities, cs?.comeback_against_allowed)} color={ca ? 'var(--color-delta-down)' : 'var(--color-muted)'} info={h.comeback.allowedInfo} />
            </Strip>
            {/* 뒤집은 쪽과 뒤집힌 쪽을 같은 축에 올려 어느 쪽이 더 큰지 바로 보이게 한다 */}
            <ColumnChart
              items={[
                ...(cb ? [{ k: h.comeback.mine, s: h.comeback.mineMark, v: cb.value * 100, c: '#66C0F4', vc: '#8FC93A' }] : []),
                ...(ca ? [{ k: h.comeback.allowed, s: h.comeback.allowedMark, v: ca.value * 100, c: '#D9524C', vc: '#E5837E' }] : []),
              ]}
              h={250}
              mb={62}
              max={cbMax}
              bw={120}
              note={h.comeback.note(cbMax)}
              aria={h.comeback.aria}
            />
          </>
        ) : (
          noData
        ),
    },
    session_vs_slope: {
      right: pill(slope),
      detail: vsCurve.length >= 2,
      chips: condChips,
      body:
        vsCurve.length >= 2 ? (
          <>
            <LineChart data={condData} series={condSeries} h={260} yfmt={(v) => v.toFixed(2)} aria={h.condition.aria} onOpen={() => setDetail('session_vs_slope')} />
            <Legend items={condSeries} />
          </>
        ) : slope ? (
          <Strip>
            <StatBox
              k="VS Slope"
              v={markText(slope)}
              s={slope.trend === 'up' ? h.condition.rising : slope.trend === 'down' ? h.condition.falling : h.condition.flat}
              color={slope.trend ? STAT_COLOR[slope.trend] : 'var(--color-ink)'}
            />
          </Strip>
        ) : (
          noData
        ),
    },
    rivals: {
      right: <Caption>{r.right(rivals.length)}</Caption>,
      chips: [
        { k: r.nemesis, v: r.nemesisRule },
        { k: r.edge, v: r.edgeRule },
        { k: r.repeat, v: r.repeatSub },
      ],
      tail: <RivalPolicy />,
      body: rivals.length ? <RivalBoard items={rivals} /> : <Notice title={r.empty} />,
    },
  }

  let modal: ReactNode = null
  if (detail) {
    const idx = HEAVY_CHAPTERS.indexOf(detail)
    let body: ReactNode
    if (detail === 'tr_trend') {
      const full: LinePoint[] = series.map((v, i) => ({ label: String(i + 1), head: `GAME ${i + 1}`, values: { tr: v } }))
      body = (
        <>
          <LineChart data={full} series={trSeries} h={340} every={Math.max(1, Math.round(full.length / 15))} aria={h.trend.aria} />
          <Legend items={trSeries} />
          <DataTable head={[h.trend.game, 'TR']} rows={series.map((v, i) => [i + 1, num(v, 1)])} scroll={460} note={h.trend.tableNote(series.length)} />
        </>
      )
    } else if (detail === 'playstyle') {
      body = (
        <>
          <DivergingBars items={psItems} max={psMax} tick={psTick} aria={h.playstyle.aria} />
          <DataTable head={[h.playstyle.metric, h.playstyle.desc, 'Δ']} rows={ps.map((p) => [nameOf(p.k), STAT_META[p.k].label[lang], p.e?.text ?? '—'])} />
        </>
      )
    } else {
      body = (
        <>
          <LineChart data={condData} series={condSeries} h={320} yfmt={(v) => v.toFixed(2)} aria={h.condition.aria} />
          <Legend items={condSeries} />
          <DataTable
            head={[h.condition.round, h.condition.avgVs, ...(ppsCurve.length ? [h.condition.pps] : [])]}
            rows={vsCurve.map((v, i) => [`R${i + 1}`, v.toFixed(2), ...(ppsCurve.length ? [ppsCurve[i].toFixed(2)] : [])])}
          />
        </>
      )
    }
    modal = (
      <DetailModal no={String(idx + 1).padStart(2, '0')} eyebrow={EYEBROWS[detail]} title={h.chapters[detail]} onClose={closeDetail}>
        {body}
      </DetailModal>
    )
  }

  return (
    <div className="flex flex-col">
      <div className="mb-8">
        <ProfilePanel data={data} />
      </div>
      <div className="flex flex-col gap-8">
        <StreamError heavy={heavy} />
        {HEAVY_CHAPTERS.map((id, i) => {
          const c = chapters[id]
          return (
            <Chapter
              key={id}
              index={i}
              color={CHAPTER_COLORS[id]}
              eyebrow={EYEBROWS[id]}
              title={h.chapters[id]}
              right={c.right}
              onDetail={c.detail ? () => setDetail(id as Detail) : undefined}
              chips={c.chips}
              result={heavy.chapters[id]}
              foot={footStateOf(heavy.chapters[id], heavy.status)}
              tail={c.tail}
            >
              {c.body}
            </Chapter>
          )
        })}
      </div>
      {modal}
    </div>
  )
}
