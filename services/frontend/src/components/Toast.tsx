import { createContext, useCallback, useContext, useState, type ReactNode } from 'react'
import { Icon, type IconName } from './Icon'
import { cn } from '../lib/format'

export type ToastTone = 'success' | 'info' | 'error'

interface ToastItem {
  id: number
  message: string
  tone: ToastTone
}

type ToastFn = (message: string, tone?: ToastTone) => void

const ToastContext = createContext<ToastFn>(() => {})

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])

  const dismiss = useCallback((id: number) => {
    setItems((prev) => prev.filter((t) => t.id !== id))
  }, [])

  const toast = useCallback<ToastFn>((message, tone = 'success') => {
    const id = Date.now() + Math.random()
    setItems((prev) => [...prev, { id, message, tone }])
    setTimeout(() => setItems((prev) => prev.filter((t) => t.id !== id)), 3600)
  }, [])

  return (
    <ToastContext.Provider value={toast}>
      {children}
      <div className="pointer-events-none fixed right-5 top-5 z-[100] flex flex-col gap-2.5">
        {items.map((t) => (
          <ToastCard key={t.id} item={t} onClose={() => dismiss(t.id)} />
        ))}
      </div>
    </ToastContext.Provider>
  )
}

export function useToast(): ToastFn {
  return useContext(ToastContext)
}

const TONE: Record<ToastTone, { bg: string; icon: IconName }> = {
  success: { bg: 'bg-[#157f4a]', icon: 'check' }, // #869 성공은 초록 배경(UX)
  info: { bg: 'bg-[#1b1e24]', icon: 'info' },
  error: { bg: 'bg-[#c0392b]', icon: 'info' },
}

function ToastCard({ item, onClose }: { item: ToastItem; onClose: () => void }) {
  const tone = TONE[item.tone]
  return (
    <div
      className={cn(
        'bifrost-fade pointer-events-auto flex min-w-[280px] max-w-sm items-center gap-2.5 rounded-lg px-4 py-3 text-sm font-medium text-white shadow-lg',
        tone.bg,
      )}
    >
      <Icon name={tone.icon} size={16} strokeWidth={2.6} />
      <span className="flex-1">{item.message}</span>
      <button onClick={onClose} className="text-white/70 transition-colors hover:text-white">
        <Icon name="x" size={14} />
      </button>
    </div>
  )
}
