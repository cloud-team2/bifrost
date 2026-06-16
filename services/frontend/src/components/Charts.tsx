import {
  Area,
  AreaChart,
  CartesianGrid,
  Line,
  LineChart,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import type { ComponentProps } from 'react'
import type { Point } from '../data/types'

/**
 * recharts ResponsiveContainer 공용 래퍼(#99).
 *
 * recharts 3.x ResponsiveContainer는 {@code initialDimension} 기본값이 -1이라, ResizeObserver
 * 측정 전 첫 렌더에서 "The width(-1) and height(-1) of chart should be greater than 0" 경고를 낸다
 * (StrictMode 이중 렌더로 더 잘 노출). 양수 기본값을 주면 첫 렌더부터 크기가 양수라 경고가 사라지고,
 * ResizeObserver가 즉시 실제 크기로 보정하므로 반응형 동작은 그대로다.
 *
 * **모든 차트는 raw ResponsiveContainer 대신 이 래퍼를 사용한다** — 페이지마다 경고가 재발하지 않게.
 */
export function ResponsiveChart({
  initialDimension,
  ...rest
}: ComponentProps<typeof ResponsiveContainer>) {
  return <ResponsiveContainer initialDimension={initialDimension ?? { width: 300, height: 200 }} {...rest} />
}

/* chart series colors (#762). 1차 시리즈=데이터 색(잉크블루), 보조 시리즈=그레이 스케일,
   오류 시리즈만 빨강. 파랑=데이터·빨강=문제로 의미 분리. */
export const CHART_COLORS = {
  brand: '#3a47c2',
  emerald: '#8a8a8a',
  amber: '#b0b0b0',
  red: '#c0392b',
  violet: '#c8c8c8',
  slate: '#9a9a9a',
}

export interface SeriesDef {
  key: string
  label: string
  color: string
}

// 시간축(timeAxis) 포맷터: t가 epoch ms일 때 사용. Grafana처럼 실제 시간 간격으로 점을 배치한다.
const fmtClock = (ms: number) =>
  new Date(ms).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })
const fmtClockSec = (ms: number) =>
  new Date(ms).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit', second: '2-digit' })

export function TrendChart({
  data,
  series,
  type = 'line',
  height = 200,
  refLine,
  timeAxis = false,
  xDomain,
  showDots = false,
}: {
  data: Point[]
  series: SeriesDef[]
  type?: 'line' | 'area'
  height?: number
  refLine?: { y: number; label: string }
  /** t를 epoch ms로 보고 실제 시간 간격으로 배치(Grafana식). false면 t를 카테고리로 취급. */
  timeAxis?: boolean
  /** 시간축 도메인 [start, end] (epoch ms). 주면 데이터가 sparse해도 창 전체를 고정 표시(Grafana식). */
  xDomain?: [number, number]
  /** 데이터 점에 dot 표시(끊긴 시리즈의 고립 점이 보이도록). */
  showDots?: boolean
}) {
  const axis = { fontSize: 10, fill: '#9a9a9a' }

  // 시간축이면 t(ms)를 숫자 시간 스케일로, 아니면 기존 카테고리 라벨로.
  const xAxis = timeAxis ? (
    <XAxis dataKey="t" type="number" scale="time" domain={xDomain ?? ['dataMin', 'dataMax']}
      allowDataOverflow tickFormatter={fmtClock} tick={axis} tickLine={false} axisLine={false}
      interval="preserveStartEnd" minTickGap={44} />
  ) : (
    <XAxis dataKey="t" tick={axis} tickLine={false} axisLine={false}
      interval="preserveStartEnd" minTickGap={36} />
  )
  const tooltip = (
    <Tooltip contentStyle={tooltipStyle} labelFormatter={timeAxis ? (v) => fmtClockSec(v as number) : undefined} />
  )

  return (
    <div style={{ height }}>
      <ResponsiveChart width="100%" height="100%" initialDimension={{ width: 300, height }}>
        {type === 'area' ? (
          <AreaChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
            <defs>
              {series.map((s) => (
                <linearGradient key={s.key} id={`g-${s.key}`} x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stopColor={s.color} stopOpacity={0.28} />
                  <stop offset="100%" stopColor={s.color} stopOpacity={0.02} />
                </linearGradient>
              ))}
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="#f1f1f1" vertical={false} />
            {xAxis}
            <YAxis tick={axis} tickLine={false} axisLine={false} width={44} />
            {tooltip}
            {refLine && (
              <ReferenceLine y={refLine.y} stroke="#c0392b" strokeDasharray="4 3" label={refLabel(refLine.label)} />
            )}
            {series.map((s) => (
              <Area
                key={s.key}
                type="monotone"
                dataKey={s.key}
                name={s.label}
                stroke={s.color}
                strokeWidth={2}
                fill={`url(#g-${s.key})`}
                isAnimationActive={false}
                connectNulls={false}
              />
            ))}
          </AreaChart>
        ) : (
          <LineChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#f1f1f1" vertical={false} />
            {xAxis}
            <YAxis tick={axis} tickLine={false} axisLine={false} width={44} />
            {tooltip}
            {refLine && (
              <ReferenceLine y={refLine.y} stroke="#c0392b" strokeDasharray="4 3" label={refLabel(refLine.label)} />
            )}
            {series.map((s) => (
              <Line
                key={s.key}
                type="monotone"
                dataKey={s.key}
                name={s.label}
                stroke={s.color}
                strokeWidth={2}
                dot={showDots ? { r: 2, fill: s.color } : false}
                isAnimationActive={false}
                connectNulls={false}
              />
            ))}
          </LineChart>
        )}
      </ResponsiveChart>
    </div>
  )
}

const tooltipStyle = {
  borderRadius: 8,
  border: '1px solid #ececec',
  fontSize: 12,
  boxShadow: '0 4px 16px rgba(0,0,0,0.08)',
}

function refLabel(text: string) {
  return { value: text, position: 'insideTopRight' as const, fontSize: 10, fill: '#c0392b' }
}

export function ChartLegend({ series }: { series: SeriesDef[] }) {
  return (
    <div className="flex flex-wrap gap-4">
      {series.map((s) => (
        <div key={s.key} className="flex items-center gap-1.5 text-[12px] text-gray-500">
          <span className="h-2 w-2 rounded-full" style={{ background: s.color }} />
          {s.label}
        </div>
      ))}
    </div>
  )
}
