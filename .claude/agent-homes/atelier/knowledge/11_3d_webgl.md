# 3D and WebGL - only when it proves something

## When 3D earns its place
- A system's *shape* is the point (a network of data centers, a mesh of services, order flow through a pipeline).
- The opening title sequence needs a living symbol (a particle field that condenses into the logo form or a chart).
- Never for charts that must be read precisely (no 3D bars/pies). Never behind dense text.

## Offline builds
- `<!-- @vendor three -->` -> global `THREE` (r186, WebGLRenderer) plus `THREE.OrbitControls`, `THREE.EffectComposer`,
  `THREE.RenderPass`, `THREE.UnrealBloomPass`, `THREE.OutputPass`, `THREE.RoomEnvironment`. ~765 KB.
- `<!-- @vendor three-webgpu -->` -> global `THREE` (WebGPU build: `THREE.WebGPURenderer` auto-falls back to WebGL2)
  and `TSL` (node shading language). ~1.2 MB. Never load both.
- WebGPU init is async: `await renderer.init()` before the first render (or use `renderer.setAnimationLoop`).

## Minimal glowing hero (WebGL build)
```js
const canvas = document.querySelector('#stage');
const renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true });
renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
const scene = new THREE.Scene(), camera = new THREE.PerspectiveCamera(45, 1, .1, 100); camera.position.set(0, 0, 9);
const N = 6000, pos = new Float32Array(N * 3), rnd = mulberry32(7);
for (let i = 0; i < N; i++) { const r = 3 + rnd() * .6, a = rnd() * Math.PI * 2, b = Math.acos(2 * rnd() - 1);
  pos.set([r * Math.sin(b) * Math.cos(a), r * Math.sin(b) * Math.sin(a), r * Math.cos(b)], i * 3); }
const geo = new THREE.BufferGeometry(); geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
const pts = new THREE.Points(geo, new THREE.PointsMaterial({ size: .025, color: 0x43d6a8, transparent: true, opacity: .9 }));
scene.add(pts);
const composer = new THREE.EffectComposer(renderer);
composer.addPass(new THREE.RenderPass(scene, camera));
composer.addPass(new THREE.UnrealBloomPass(new THREE.Vector2(1, 1), 0.9, 0.4, 0.1));  // strength, radius, threshold
composer.addPass(new THREE.OutputPass());
function fit() { const w = canvas.clientWidth, h = canvas.clientHeight; renderer.setSize(w, h, false);
  composer.setSize(w, h); camera.aspect = w / h; camera.updateProjectionMatrix(); }
fit(); addEventListener('resize', fit);
const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
renderer.setAnimationLoop(t => { if (!reduce) pts.rotation.y = t * 0.00008; composer.render(); });
```

## Compositing over CSS backgrounds
The EffectComposer (bloom) output is opaque - black around the particles hides the aurora/gradient behind
and shows a hard rectangle edge. Fix: `canvas { mix-blend-mode: screen; }` (black becomes transparent), and make
the canvas full-bleed (`width: 100vw`) or fade its edges with `mask-image: radial-gradient(...)`.
Without a composer, `new THREE.WebGLRenderer({ alpha: true })` keeps real transparency.

## Performance budget
- Pixel ratio capped at 2; pause the loop when the tab is hidden or the canvas is off-screen
  (IntersectionObserver -> `renderer.setAnimationLoop(null)`).
- < 100k points with PointsMaterial on WebGL; millions only with WebGPU compute (TSL `instancedArray`).
- Software-rendered environments (no GPU, VMs) make bloom very slow - if the first frame takes > 100 ms,
  drop the bloom pass or render a still.
- Keep draw calls low: merge geometries, use InstancedMesh for repeated objects.
- Always provide a static fallback (CSS gradient + SVG symbol) if WebGL context creation throws.
- Reduced motion: render one still frame.

Sources: utsubo.com Three.js 100 tips (2026) and WebGPU migration guide; buildmvpfast.com Three.js WebGPU guide
(Firefox WebGPU caveat); three r186 bundles verified to load offline in headless Chromium (WebGL context OK).
