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

/* chart series colors — chroma eased down slightly from the raw neon values */
export const CHART_COLORS = {
  brand: '#4a5ad0',
  emerald: '#2ba27b',
  amber: '#e3a52c',
  red: '#e05c5c',
  violet: '#8a72ea',
  slate: '#94a3b8',
}

export interface SeriesDef {
  key: string
  label: string
  color: string
}

export function TrendChart({
  data,
  series,
  type = 'line',
  height = 200,
  refLine,
}: {
  data: Point[]
  series: SeriesDef[]
  type?: 'line' | 'area'
  height?: number
  refLine?: { y: number; label: string }
}) {
  const axis = { fontSize: 10, fill: '#94a3b8' }

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
            <CartesianGrid strokeDasharray="3 3" stroke="#eef0f3" vertical={false} />
            <XAxis dataKey="t" tick={axis} tickLine={false} axisLine={false} interval="preserveStartEnd" minTickGap={36} />
            <YAxis tick={axis} tickLine={false} axisLine={false} width={44} />
            <Tooltip contentStyle={tooltipStyle} />
            {refLine && (
              <ReferenceLine y={refLine.y} stroke="#ef4444" strokeDasharray="4 3" label={refLabel(refLine.label)} />
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
              />
            ))}
          </AreaChart>
        ) : (
          <LineChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#eef0f3" vertical={false} />
            <XAxis dataKey="t" tick={axis} tickLine={false} axisLine={false} interval="preserveStartEnd" minTickGap={36} />
            <YAxis tick={axis} tickLine={false} axisLine={false} width={44} />
            <Tooltip contentStyle={tooltipStyle} />
            {refLine && (
              <ReferenceLine y={refLine.y} stroke="#ef4444" strokeDasharray="4 3" label={refLabel(refLine.label)} />
            )}
            {series.map((s) => (
              <Line
                key={s.key}
                type="monotone"
                dataKey={s.key}
                name={s.label}
                stroke={s.color}
                strokeWidth={2}
                dot={false}
                isAnimationActive={false}
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
  border: '1px solid #e5e7eb',
  fontSize: 12,
  boxShadow: '0 4px 16px rgba(0,0,0,0.08)',
}

function refLabel(text: string) {
  return { value: text, position: 'insideTopRight' as const, fontSize: 10, fill: '#ef4444' }
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
