import { useEffect, type ReactNode } from 'react'
import { Icon } from './Icon'

export function Modal({
  open,
  onClose,
  title,
  subtitle,
  children,
  footer,
  width = 460,
}: {
  open: boolean
  onClose: () => void
  title: string
  subtitle?: string
  children: ReactNode
  footer?: ReactNode
  width?: number
}) {
  useEffect(() => {
    if (!open) return
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open, onClose])

  if (!open) return null

  return (
    <div className="fixed inset-0 z-[90] flex items-center justify-center p-6">
      <div className="absolute inset-0 bg-gray-900/40" onClick={onClose} />
      <div
        className="bifrost-fade relative z-10 max-h-[85vh] overflow-hidden rounded-xl bg-white shadow-2xl"
        style={{ width }}
      >
        <header className="flex items-start justify-between border-b border-gray-200 px-5 py-4">
          <div>
            <h3 className="text-[15px] font-semibold text-gray-900">{title}</h3>
            {subtitle && <p className="mt-0.5 text-[12.5px] text-gray-500">{subtitle}</p>}
          </div>
          <button
            onClick={onClose}
            className="rounded p-1 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600"
          >
            <Icon name="x" size={16} />
          </button>
        </header>
        <div className="max-h-[60vh] overflow-y-auto scroll-thin px-5 py-4">{children}</div>
        {footer && (
          <footer className="flex justify-end gap-2 border-t border-gray-200 bg-gray-50 px-5 py-3">
            {footer}
          </footer>
        )}
      </div>
    </div>
  )
}
