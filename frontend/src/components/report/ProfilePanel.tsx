import type { ReactNode } from 'react'
import type { StatsResponse } from '../../api/types'
import { useI18n } from '../../i18n/context'
import { cx } from '../../lib/cx'
import { rankColor } from '../../lib/rankColors'
import { num, numOrDash, rankLabel, signed, trendOf, winRateDeltaPp } from '../../lib/stats'
import { BigNum, Caption, DeltaBadge } from './parts'

function Cell({ k, children, sub, accent, tag }: { k: string; children: ReactNode; sub?: ReactNode; accent?: boolean; tag?: string }) {
  return (
    <div className={cx('relative min-w-0 px-4 py-[15px]', accent ? 'bg-[#1A3348]' : 'bg-cell')}>
      {tag && (
        <span className="absolute top-[13px] right-3.5 rounded-[3px] border border-primary-bright/40 px-1.5 py-px font-display text-[9px] font-bold tracking-[.12em] text-primary-bright">
          {tag}
        </span>
      )}
      <div className={cx('font-display text-[9.5px] font-bold tracking-[.2em]', accent ? 'text-primary-bright' : 'text-faint')}>{k}</div>
      <div className="num-hud mt-[5px] text-[23px] tracking-[-.018em] text-white">{children}</div>
      {sub && <div className="mt-1.5 flex flex-col items-start gap-1.5 text-[11.5px] text-muted">{sub}</div>}
    </div>
  )
}

/** 아래 띠의 작은 값 — GLICKO (GAMES는 AI READOUT 코멘트 아래로 옮겼다) */
function Strip({ k, v, unit }: { k: string; v: string; unit?: string }) {
  return (
    <span className="flex items-baseline gap-2">
      <span className="font-display text-[9.5px] font-bold tracking-[.18em] text-faint">{k}</span>
      <span className="font-num text-[17px] font-bold text-head tabular-nums">
        <BigNum value={v} />
      </span>
      {unit && <span className="text-[11px] text-muted">{unit}</span>}
    </span>
  )
}

/** 유저 기본 정보 — /stats의 profile · fixed_metrics 값을 그대로 보여준다 */
export function ProfilePanel({ data }: { data: StatsResponse }) {
  const { t } = useI18n()
  const p = t.report.profile
  const pr = data.profile
  const rank = rankLabel(pr.rank)
  /* WIN RATE = 현재 시즌 랭크 승리 / 시즌 판수. 시즌 판수가 0이라 값이 없으면 지어내지 않고 '—' */
  const sp = pr.season_games_played
  const sw = pr.season_wins
  const season = typeof sp === 'number' && typeof sw === 'number' && sp > 0 ? { rate: sw / sp, wins: sw, losses: pr.season_losses ?? sp - sw } : null
  /* 5칸 증감 — 최근 N판 vs 그 이전 판 변화율 %. 값이 빠지면 배지도 없다 */
  const wd = pr.window_delta
  const badge = (v: number | null | undefined, unit: string) =>
    typeof v === 'number' ? <DeltaBadge text={`${signed(v, 1)}${unit}`} trend={trendOf(v, 1)} /> : null
  const pct = (v: number | undefined) => badge(v, '%')
  /* 승률만 %p — 백엔드 변화율(%)을 분석 경기 승률과 함께 승률 차로 바꾼다 */
  const overall = data.fixed_metrics.win_rate
  const wrPp = wd?.wr_delta_pct !== undefined && typeof overall === 'number' ? winRateDeltaPp(overall, data.match_count, wd.recent_matches, wd.wr_delta_pct) : null

  return (
    <section className="panel overflow-hidden bg-surface" aria-label="PROFILE">
      <div className="grid grid-cols-2 gap-0.5 bg-deep md:grid-cols-3 lg:grid-cols-[200px_repeat(5,minmax(0,1fr))]">
        <div className="flex min-w-0 items-center bg-cell py-[15px] pr-4 pl-4 lg:pl-7">
          <div className="min-w-0">
            <div className="font-display text-xs font-bold tracking-[.2em] text-faint">RANK</div>
            {/* 랭크마다 TETR.IO 랭크 색 (lib/rankColors) */}
            <div
              className={cx(
                'mt-1 font-display font-extrabold tracking-[.02em]',
                rank ? 'text-[50px] leading-none text-head [text-shadow:0_3px_0_rgba(0,0,0,.45),0_0_16px_color-mix(in_srgb,currentColor_45%,transparent)]' : 'text-[26px] leading-tight text-muted',
              )}
              style={rank ? { color: rankColor(pr.rank) } : undefined}
              title={pr.rank === 'z' ? t.report.unranked : undefined}
            >
              {/* 값이 안 왔으면 모르는 것이라 '—'. 'z'(언랭크)는 '?' — 분석은 정상 진행 */}
              {pr.rank === 'z' ? (
                <>
                  <span aria-hidden="true">?</span>
                  <span className="sr-only">{t.report.unranked}</span>
                </>
              ) : (
                (rank ?? '—')
              )}
            </div>
            <div className="mt-1 text-sm text-muted">{p.season}</div>
          </div>
        </div>
        <Cell
          k="TR"
          accent
          tag={p.basis}
          sub={
            <>
              {pct(wd?.tr_delta_pct)}
              <span>{p.trSub}</span>
            </>
          }
        >
          <BigNum value={numOrDash(pr.tr, 2)} />
        </Cell>
        {/* 승률을 모르면 지어내지 않고 '—' */}
        <Cell
          k="WIN RATE"
          sub={
            (wrPp !== null || season) && (
              <>
                {badge(wrPp, '%p')}
                {season && <span>{p.wl(season.wins, season.losses)}</span>}
              </>
            )
          }
        >
          {season ? <BigNum value={(season.rate * 100).toFixed(1)} suffix="%" /> : '—'}
        </Cell>
        <Cell
          k="APM"
          sub={
            <>
              {pct(wd?.apm_delta_pct)}
              <span>{p.apmSub}</span>
            </>
          }
        >
          <BigNum value={numOrDash(pr.apm, 2)} />
        </Cell>
        <Cell
          k="PPS"
          sub={
            <>
              {pct(wd?.pps_delta_pct)}
              <span>{p.ppsSub}</span>
            </>
          }
        >
          <BigNum value={numOrDash(pr.pps, 2)} />
        </Cell>
        <Cell
          k="VS"
          sub={
            <>
              {pct(wd?.vs_delta_pct)}
              <span>{p.vsSub}</span>
            </>
          }
        >
          <BigNum value={numOrDash(pr.vs, 2)} />
        </Cell>
      </div>
      <div className="flex flex-wrap items-center gap-x-6 gap-y-1 border-t-2 border-deep bg-[#0F1D29] px-[18px] py-1.5 leading-tight">
        <Strip k="GLICKO" v={numOrDash(pr.glicko, 1)} unit={pr.rd >= 0 ? p.rd(num(pr.rd, 1)) : undefined} />
        {wd && <Caption className="text-xs lg:ml-auto">{p.note(wd.recent_matches)}</Caption>}
      </div>
    </section>
  )
}
