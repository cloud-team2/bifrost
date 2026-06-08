import { useState } from 'react'

/**
 * Bifrost brand mark — renders the real project logo image at `public/logo.svg`.
 * Until that file is added it falls back to a placeholder mark, so the UI
 * never shows a broken image.
 */
export function BrandMark({ size = 28, className }: { size?: number; className?: string }) {
  const [failed, setFailed] = useState(false)

  if (failed) {
    return (
      <svg
        width={size}
        height={size}
        viewBox="0 0 64 64"
        fill="none"
        className={className}
        aria-hidden="true"
      >
        <defs>
          <linearGradient id="bifrost-mark" x1="8" y1="32" x2="56" y2="32" gradientUnits="userSpaceOnUse">
            <stop offset="0" stopColor="#29b6ff" />
            <stop offset="0.3" stopColor="#4f6bff" />
            <stop offset="0.52" stopColor="#9b3dff" />
            <stop offset="0.72" stopColor="#ff3d96" />
            <stop offset="1" stopColor="#ff8a2b" />
          </linearGradient>
        </defs>
        <g stroke="url(#bifrost-mark)" strokeWidth="9" strokeLinecap="round" strokeLinejoin="round">
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
      style={{ objectFit: 'contain' }}
    />
  )
}
