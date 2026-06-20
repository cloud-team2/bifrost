import { useEffect, useRef, type RefObject } from 'react'
import * as THREE from 'three'

/**
 * Landing hero background — a deep-space starfield crossed by the Bifröst beam
 * (three.js). Decorative only. Matches the landing/console design language (#879).
 *
 * - Stars (perspective) + a chromatic beam (orthographic fullscreen quad, additive).
 * - Renders only while the hero is on-screen (IntersectionObserver).
 * - `prefers-reduced-motion` → paints a single static frame, no animation loop.
 * - WebGL unavailable → leaves the deep-space background (no canvas paint).
 *
 * Beam parameters are locked to the approved values:
 *   intensity 0.4 · beamY 0.68 · saturation 1.5 · core 0.03 · star 1.0
 */
const SPECTRUM_GLSL = `
vec3 spectrum(float t){ t = clamp(t, 0.0, 1.0);
  vec3 c0 = vec3(0.18, 0.77, 1.0);   // cyan
  vec3 c1 = vec3(0.42, 0.48, 1.0);   // indigo
  vec3 c2 = vec3(0.70, 0.42, 1.0);   // violet
  vec3 c3 = vec3(1.0, 0.37, 0.66);   // magenta
  vec3 c4 = vec3(1.0, 0.82, 0.45);   // gold
  float s = t * 4.0;
  if (s < 1.0) return mix(c0, c1, s);
  if (s < 2.0) return mix(c1, c2, s - 1.0);
  if (s < 3.0) return mix(c2, c3, s - 2.0);
  return mix(c3, c4, s - 3.0);
}`

export function HeroBackground({
  className,
  beamTargetRef,
}: {
  className?: string
  /** 빔을 이 요소(워드마크)의 수직 중앙에 맞춘다. 없으면 0.68 고정. */
  beamTargetRef?: RefObject<HTMLElement | null>
}) {
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
      return // WebGL unsupported → keep the static deep-space background.
    }
    const dpr = Math.min(window.devicePixelRatio, 2)
    renderer.setPixelRatio(dpr)
    renderer.autoClear = false
    renderer.setClearColor(0x000000, 0)

    const mobile = window.innerWidth < 768

    // ── Stars (perspective) ──
    const starScene = new THREE.Scene()
    const starCam = new THREE.PerspectiveCamera(60, 1, 0.1, 100)
    starCam.position.z = 14
    const SN = mobile ? 600 : 1000
    const sp = new Float32Array(SN * 3)
    const sPhase = new Float32Array(SN)
    const sSize = new Float32Array(SN)
    for (let i = 0; i < SN; i++) {
      sp[i * 3] = (Math.random() - 0.5) * 60
      sp[i * 3 + 1] = (Math.random() - 0.5) * 36
      sp[i * 3 + 2] = (Math.random() - 0.5) * 20
      sPhase[i] = Math.random() * 6.28
      sSize[i] = Math.random() * 1.6 + 0.4
    }
    const sg = new THREE.BufferGeometry()
    sg.setAttribute('position', new THREE.BufferAttribute(sp, 3))
    sg.setAttribute('aPhase', new THREE.BufferAttribute(sPhase, 1))
    sg.setAttribute('aSize', new THREE.BufferAttribute(sSize, 1))
    const sUni = { t: { value: 0 }, uStar: { value: 1.0 }, uDpr: { value: dpr } }
    const starMat = new THREE.ShaderMaterial({
      uniforms: sUni,
      transparent: true,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
      vertexShader: `attribute float aPhase; attribute float aSize; uniform float t; uniform float uDpr; varying float vT;
        void main(){ vT = 0.55 + 0.45 * sin(t * 1.6 + aPhase);
          vec4 mv = modelViewMatrix * vec4(position, 1.0);
          gl_PointSize = aSize * uDpr * (0.7 + 0.5 * vT) * (120.0 / -mv.z);
          gl_Position = projectionMatrix * mv; }`,
      fragmentShader: `uniform float uStar; varying float vT;
        void main(){ vec2 d = gl_PointCoord - 0.5; float a = smoothstep(0.5, 0.0, length(d));
          gl_FragColor = vec4(vec3(1.0, 1.0, 1.05) * vT * uStar, a * vT * uStar); }`,
    })
    const starPoints = new THREE.Points(sg, starMat)
    starScene.add(starPoints)

    // ── Beam (orthographic fullscreen quad, additive) ──
    const beamScene = new THREE.Scene()
    const beamCam = new THREE.OrthographicCamera(-1, 1, 1, -1, 0, 1)
    const bUni = {
      t: { value: 0 },
      uRes: { value: new THREE.Vector2(1, 1) },
      uIntensity: { value: 0.4 },
      uBeamY: { value: 0.68 },
      uSat: { value: 1.5 },
      uCore: { value: 0.03 },
    }
    const beamMat = new THREE.ShaderMaterial({
      uniforms: bUni,
      transparent: true,
      depthWrite: false,
      blending: THREE.AdditiveBlending,
      vertexShader: `varying vec2 vUv; void main(){ vUv = uv; gl_Position = vec4(position.xy, 0.0, 1.0); }`,
      fragmentShader:
        SPECTRUM_GLSL +
        `varying vec2 vUv; uniform float t, uIntensity, uBeamY, uSat, uCore; uniform vec2 uRes;
        void main(){
          vec2 uv = vUv; float aspect = uRes.x / uRes.y; float d = uv.y - uBeamY;
          float sh = 0.82 + 0.12 * sin(uv.x * 70.0 - t * 7.0) + 0.08 * sin(uv.x * 26.0 + t * 3.0);
          float core = exp(-pow(d / uCore, 2.0));
          float halo = exp(-pow(d / 0.075, 2.0));
          float hue = clamp(0.5 - d * 5.5, 0.0, 1.0);
          vec3 chroma = spectrum(hue);
          chroma = mix(vec3(dot(chroma, vec3(0.33))), chroma, uSat);
          float env = smoothstep(0.0, 0.05, uv.x) * mix(1.0, 0.30, smoothstep(0.04, 1.0, uv.x));
          vec3 col = (core * vec3(1.0, 1.0, 1.05) * 1.5 + halo * chroma * 1.15) * env * sh;
          vec2 o = vec2(0.07, uBeamY);
          float fl = exp(-pow(distance(vec2(uv.x * aspect, uv.y), vec2(o.x * aspect, o.y)) / 0.055, 2.0));
          col += fl * vec3(1.0, 0.92, 1.0) * 1.7;
          col *= uIntensity;
          gl_FragColor = vec4(col, clamp(max(col.r, max(col.g, col.b)), 0.0, 1.0));
        }`,
    })
    const beamMesh = new THREE.Mesh(new THREE.PlaneGeometry(2, 2), beamMat)
    beamScene.add(beamMesh)

    function resize() {
      const w = host!.clientWidth
      const h = host!.clientHeight
      if (w === 0 || h === 0) return
      renderer.setSize(w, h, false)
      starCam.aspect = w / h
      starCam.updateProjectionMatrix()
      bUni.uRes.value.set(w, h)

      // 빔을 워드마크("o") 수직 중앙에 정렬 (반응형/폰트 로드 시 재계산).
      let by = 0.68
      const target = beamTargetRef?.current
      if (target) {
        const hostRect = host!.getBoundingClientRect()
        const tRect = target.getBoundingClientRect()
        if (hostRect.height > 0) {
          const centerFromTop = tRect.top - hostRect.top + tRect.height / 2
          // uv.y: 0=하단, 1=상단 → 상단 기준 비율을 반전.
          by = Math.max(0.04, Math.min(0.96, 1 - centerFromTop / hostRect.height))
        }
      }
      bUni.uBeamY.value = by
    }

    function renderFrame(t: number) {
      sUni.t.value = t
      bUni.t.value = t
      starScene.rotation.z = t * 0.008
      renderer.clear()
      renderer.render(starScene, starCam)
      renderer.render(beamScene, beamCam)
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

    // 웹폰트(Space Grotesk) 로드로 워드마크 크기가 확정되면 빔 위치 재정렬.
    document.fonts?.ready.then(() => {
      resize()
      if (!running) renderFrame(0)
    })

    return () => {
      stop()
      io.disconnect()
      window.removeEventListener('resize', onResize)
      sg.dispose()
      starMat.dispose()
      beamMesh.geometry.dispose()
      beamMat.dispose()
      renderer.dispose()
    }
  }, [])

  return <canvas ref={canvasRef} className={className} aria-hidden="true" />
}
