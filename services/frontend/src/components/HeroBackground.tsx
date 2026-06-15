import { useEffect, useRef } from 'react'
import * as THREE from 'three'

/**
 * Landing hero background — a monochrome point field that ripples on a sine
 * wave (three.js). Decorative only.
 *
 * - Renders only while the hero is on-screen (IntersectionObserver).
 * - `prefers-reduced-motion` → paints a single static frame, no animation loop.
 * - Mobile → reduced point density.
 * - WebGL unavailable → leaves the plain background (no canvas paint), so the
 *   hero still reads fine.
 */
export function HeroBackground({ className }: { className?: string }) {
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    const host = canvas?.parentElement
    if (!canvas || !host) return

    const reduceMotion =
      window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false

    let renderer: THREE.WebGLRenderer
    try {
      renderer = new THREE.WebGLRenderer({ canvas, alpha: true, antialias: true })
    } catch {
      return // WebGL unsupported → keep the static hero background.
    }
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))

    const scene = new THREE.Scene()
    const camera = new THREE.PerspectiveCamera(55, 1, 0.1, 100)
    camera.position.set(0, 0, 16)

    const mobile = window.innerWidth < 768
    const COLS = mobile ? 38 : 66
    const ROWS = mobile ? 24 : 42
    const GX = 44
    const GY = 27
    const n = COLS * ROWS
    const pos = new Float32Array(n * 3)
    const base = new Float32Array(n * 2)
    let idx = 0
    for (let y = 0; y < ROWS; y++) {
      for (let x = 0; x < COLS; x++) {
        const px = (x / (COLS - 1) - 0.5) * GX
        const py = (y / (ROWS - 1) - 0.5) * GY
        pos[idx * 3] = px
        pos[idx * 3 + 1] = py
        pos[idx * 3 + 2] = 0
        base[idx * 2] = px
        base[idx * 2 + 1] = py
        idx++
      }
    }
    const geo = new THREE.BufferGeometry()
    geo.setAttribute('position', new THREE.BufferAttribute(pos, 3))
    const mat = new THREE.PointsMaterial({
      size: 0.17,
      color: 0xbdbdbd,
      transparent: true,
      opacity: 0.5,
      depthWrite: false,
    })
    const points = new THREE.Points(geo, mat)
    points.rotation.x = -0.95
    scene.add(points)

    function resize() {
      const w = host!.clientWidth
      const h = host!.clientHeight
      if (w === 0 || h === 0) return
      renderer.setSize(w, h, false)
      camera.aspect = w / h
      camera.updateProjectionMatrix()
    }

    function renderFrame(t: number) {
      const p = geo.attributes.position.array as Float32Array
      for (let k = 0; k < n; k++) {
        const bx = base[k * 2]
        const by = base[k * 2 + 1]
        p[k * 3 + 2] = Math.sin(bx * 0.5 + t * 0.6) * 0.8 + Math.cos(by * 0.5 + t * 0.48) * 0.8
      }
      geo.attributes.position.needsUpdate = true
      points.rotation.z = Math.sin(t * 0.05) * 0.05
      renderer.render(scene, camera)
    }

    let raf = 0
    let running = false
    function loop(now: number) {
      renderFrame(now * 0.001)
      raf = requestAnimationFrame(loop)
    }
    function start() {
      if (running || reduceMotion) return
      running = true
      raf = requestAnimationFrame(loop)
    }
    function stop() {
      running = false
      if (raf) cancelAnimationFrame(raf)
      raf = 0
    }

    resize()
    renderFrame(0) // static first frame (also the resting state when paused)

    const io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          if (e.isIntersecting) start()
          else stop()
        }
      },
      { threshold: 0.01 },
    )
    io.observe(host)

    const onResize = () => {
      resize()
      if (!running) renderFrame(0)
    }
    window.addEventListener('resize', onResize)

    return () => {
      stop()
      io.disconnect()
      window.removeEventListener('resize', onResize)
      geo.dispose()
      mat.dispose()
      renderer.dispose()
    }
  }, [])

  return <canvas ref={canvasRef} className={className} aria-hidden="true" />
}
