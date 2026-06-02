import { useRef, useState, type MouseEvent, type ReactNode, type WheelEvent } from 'react'
import { Icon } from '../../components/Icon'
import { TechIcon, nodeKind } from '../../components/TechIcon'
import { StatusBadge, StatusDot } from '../../components/blocks'
import { useApp } from '../../store/AppStore'
import { nodeName, topoLinks, projectEdges, projectNodes } from '../../data/helpers'
import type { Node } from '../../data/types'
import { cn, clamp } from '../../lib/format'

const STATUS_STROKE: Record<string, string> = {
  active: '#10b981',
  lag: '#f59e0b',
  error: '#ef4444',
  paused: '#cbd5e1',
  creating: '#3b53e0',
}

export function Topology() {
  const app = useApp()
  const proj = app.currentProject!
  const nodes = projectNodes(proj, app.nodes, app.edges)
  const edges = projectEdges(proj, app.edges)
  const links = topoLinks(edges, nodes)

  const [zoom, setZoom] = useState(1)
  const [pan, setPan] = useState({ x: 60, y: 40 })
  const [mode, setMode] = useState<'flow' | 'status'>('flow')
  const [selected, setSelected] = useState<string | null>(null)
  const drag = useRef<{ x: number; y: number; px: number; py: number } | null>(null)

  const node = (id: string) => nodes.find((n) => n.id === id)
  const selNode = selected ? node(selected) : null

  function onWheel(e: WheelEvent) {
    e.preventDefault()
    setZoom((z) => clamp(z - e.deltaY * 0.0012, 0.5, 2))
  }
  function onDown(e: MouseEvent) {
    drag.current = { x: e.clientX, y: e.clientY, px: pan.x, py: pan.y }
  }
  function onMove(e: MouseEvent) {
    if (!drag.current) return
    setPan({
      x: drag.current.px + (e.clientX - drag.current.x),
      y: drag.current.py + (e.clientY - drag.current.y),
    })
  }
  const endDrag = () => (drag.current = null)

  return (
    <div className="relative h-full">
      <div
        className="h-full cursor-grab overflow-hidden bg-[radial-gradient(#e2e4e9_1px,transparent_1px)] [background-size:22px_22px] active:cursor-grabbing"
        onWheel={onWheel}
        onMouseDown={onDown}
        onMouseMove={onMove}
        onMouseUp={endDrag}
        onMouseLeave={endDrag}
      >
        <svg className="h-full w-full">
          <g transform={`translate(${pan.x},${pan.y}) scale(${zoom})`}>
            {/* edges */}
            {links.map((l) => {
              const a = node(l.from)
              const b = node(l.to)
              if (!a || !b) return null
              const stroke = mode === 'status' ? STATUS_STROKE[l.edge.status] ?? '#cbd5e1' : '#94a3b8'
              const animated = l.edge.status === 'active' || l.edge.status === 'lag'
              const path = `M${a.x + 80},${a.y} C${a.x + 180},${a.y} ${b.x - 100},${b.y} ${b.x - 80},${b.y}`
              return (
                <g key={l.id}>
                  <path d={path} fill="none" stroke={stroke} strokeWidth={2} strokeDasharray={l.edge.status === 'paused' ? '5 4' : undefined} />
                  {animated && (
                    <circle r={3.4} fill={mode === 'status' ? stroke : '#3b53e0'}>
                      <animateMotion dur={l.edge.status === 'lag' ? '3.4s' : '2s'} repeatCount="indefinite" path={path} />
                    </circle>
                  )}
                  {l.edge.status === 'lag' && (
                    <text x={(a.x + b.x) / 2 + 30} y={(a.y + b.y) / 2 - 8} fontSize={10} fontWeight={700} fill="#f59e0b">
                      ⚠ LAG
                    </text>
                  )}
                </g>
              )
            })}

            {/* nodes */}
            {nodes.map((n) => (
              <foreignObject key={n.id} x={n.x - 80} y={n.y - 33} width={160} height={66}>
                <div
                  onMouseDown={(e) => e.stopPropagation()}
                  onClick={() => setSelected(n.id)}
                  className={cn(
                    'flex h-full cursor-pointer items-center gap-2 rounded-xl border bg-white px-2.5 shadow-sm transition-shadow hover:shadow-md',
                    selected === n.id ? 'border-brand-500 ring-2 ring-brand-200' : 'border-gray-200',
                  )}
                >
                  <TechIcon kind={nodeKind(n)} size={34} />
                  <div className="min-w-0 flex-1">
                    <div className="truncate text-[12px] font-semibold text-gray-900">{nodeName(n)}</div>
                    <div className="truncate text-[10px] text-gray-400">
                      {n.type === 'database' ? n.tech : n.lang}
                    </div>
                    {n.type === 'service' && n.groupState && n.groupState !== 'STABLE' && (
                      <div className="text-[9.5px] font-semibold text-amber-600">{n.groupState}</div>
                    )}
                  </div>
                  <StatusDot status={n.status} />
                </div>
              </foreignObject>
            ))}
          </g>
        </svg>
      </div>

      {/* mode toggle */}
      <div className="absolute right-4 top-4 flex rounded-lg border border-gray-200 bg-white p-0.5 shadow-sm">
        {(['flow', 'status'] as const).map((m) => (
          <button
            key={m}
            onClick={() => setMode(m)}
            className={cn(
              'rounded-md px-2.5 py-1 text-[11.5px] font-medium capitalize',
              mode === m ? 'bg-brand-600 text-white' : 'text-gray-500',
            )}
          >
            {m}
          </button>
        ))}
      </div>

      {/* zoom controls */}
      <div className="absolute bottom-4 left-4 flex flex-col gap-1 rounded-lg border border-gray-200 bg-white p-1 shadow-sm">
        <ZoomBtn icon="plus" onClick={() => setZoom((z) => clamp(z + 0.15, 0.5, 2))} />
        <ZoomBtn icon="minus" onClick={() => setZoom((z) => clamp(z - 0.15, 0.5, 2))} />
        <ZoomBtn icon="expand" onClick={() => { setZoom(1); setPan({ x: 60, y: 40 }) }} />
        <div className="px-1 py-0.5 text-center font-mono text-[10px] text-gray-400">
          {Math.round(zoom * 100)}%
        </div>
      </div>

      {/* node panel */}
      {selNode && <NodePanel node={selNode} onClose={() => setSelected(null)} />}
    </div>
  )
}

function ZoomBtn({ icon, onClick }: { icon: 'plus' | 'minus' | 'expand'; onClick: () => void }) {
  return (
    <button onClick={onClick} className="rounded-md p-1.5 text-gray-500 hover:bg-gray-100">
      <Icon name={icon} size={15} />
    </button>
  )
}

function NodePanel({ node, onClose }: { node: Node; onClose: () => void }) {
  const app = useApp()
  return (
    <div className="absolute right-4 top-16 w-[280px] rounded-xl border border-gray-200 bg-white shadow-xl">
      <div className="flex items-center gap-2.5 border-b border-gray-100 px-4 py-3">
        <TechIcon kind={nodeKind(node)} size={34} />
        <div className="min-w-0 flex-1">
          <div className="truncate text-[13px] font-semibold text-gray-900">{nodeName(node)}</div>
          <div className="truncate text-[11px] text-gray-400">{node.techLabel}</div>
        </div>
        <button onClick={onClose} className="rounded p-1 text-gray-400 hover:bg-gray-100">
          <Icon name="x" size={15} />
        </button>
      </div>
      <div className="space-y-2 px-4 py-3 text-[12px]">
        <Row label="Status" value={<StatusBadge status={node.status} />} />
        <Row label="Host" value={<span className="font-mono text-[11px] text-gray-600">{node.host}</span>} />
        {node.type === 'database' ? (
          <>
            <Row label="TPS" value={node.metrics?.tps ?? 0} />
            <Row label="Lag" value={`${node.metrics?.lag_ms ?? 0} ms`} />
            <Row label="Tables" value={node.schema?.tables ?? 0} />
          </>
        ) : (
          <>
            <Row label="Group" value={node.consumerGroup} />
            <Row label="Group state" value={node.groupState} />
            <Row label="Lag" value={node.lag?.toLocaleString()} />
          </>
        )}
      </div>
      <div className="border-t border-gray-100 px-4 py-2.5">
        <button
          onClick={() => node.type === 'database' && app.openDatabase(node.id)}
          className="w-full rounded-md bg-brand-600 py-1.5 text-[12px] font-semibold text-white hover:bg-brand-700 disabled:bg-gray-200"
          disabled={node.type !== 'database'}
        >
          {node.type === 'database' ? 'Open database' : 'Consumer service'}
        </button>
      </div>
    </div>
  )
}

function Row({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-gray-500">{label}</span>
      <span className="font-medium text-gray-800">{value}</span>
    </div>
  )
}
