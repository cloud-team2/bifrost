import { useState } from 'react'

/**
 * Bifrost brand mark — renders the real project logo image at `public/logo.svg`
 * (mono ink #0D0D0D). On dark backgrounds pass `tone="light"` to invert the
 * mark to white via CSS filter. Falls back to a mono inline glyph so the UI
 * never shows a broken image.
 */
export function BrandMark({
  size = 28,
  className,
  tone = 'ink',
}: {
  size?: number
  className?: string
  tone?: 'ink' | 'light'
}) {
  const [failed, setFailed] = useState(false)
  // brightness(0) → pure black, invert(1) → pure white. Used to flip the mono
  // ink mark to #FFFFFF on dark sections without shipping a second asset.
  const lightFilter = tone === 'light' ? 'brightness(0) invert(1)' : undefined

  if (failed) {
    return (
      <svg
        width={size}
        height={size}
        viewBox="0 0 64 64"
        fill="none"
        className={className}
        style={{ color: tone === 'light' ? '#FFFFFF' : '#0D0D0D' }}
        aria-hidden="true"
      >
        <g stroke="currentColor" strokeWidth="9" strokeLinecap="round" strokeLinejoin="round">
          <path d="M12 54 Q14 21 32 9 Q50 21 52 54" />
          <path d="M22 39 H42" />
        </g>
      </svg>
    )
  }

  return (
    <img
      src="/logo.svg"
      width={size}
      height={size}
      alt="Bifrost"
      className={className}
      onError={() => setFailed(true)}
      style={{ objectFit: 'contain', filter: lightFilter }}
    />
  )
}
