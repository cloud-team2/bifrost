import { useState } from 'react'
import { cn } from '../lib/format'

interface MemberCompany { id: string; name: string; shortName: string; color: string }

/**
 * Every member company is an SK affiliate, so they all share the single SK
 * Group brand mark — the company name is what tells them apart. The icon is
 * loaded from SK's own published favicon; a SK-red "SK" tile is shown as a
 * fallback when it can't be reached (e.g. offline).
 */
const SK_LOGO_SRC = 'https://www.google.com/s2/favicons?domain=sk.com&sz=128'
const SK_RED = '#ea002c'

export function MemberCompanyLogo({
  company,
  size = 40,
  radius = 'rounded-lg',
  className,
}: {
  company: MemberCompany
  size?: number
  radius?: string
  className?: string
}) {
  const [failed, setFailed] = useState(false)

  if (failed) {
    return (
      <span
        className={cn(
          'inline-flex shrink-0 items-center justify-center font-bold text-white',
          radius,
          className,
        )}
        style={{
          width: size,
          height: size,
          background: SK_RED,
          fontSize: Math.round(size * 0.34),
        }}
        title={company.name}
      >
        SK
      </span>
    )
  }

  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center justify-center overflow-hidden bg-white ring-1 ring-gray-200',
        radius,
        className,
      )}
      style={{ width: size, height: size }}
    >
      <img
        src={SK_LOGO_SRC}
        alt={`${company.name} 로고`}
        loading="lazy"
        referrerPolicy="no-referrer"
        onError={() => setFailed(true)}
        style={{ width: Math.round(size * 0.7), height: Math.round(size * 0.7) }}
        className="object-contain"
      />
    </span>
  )
}
