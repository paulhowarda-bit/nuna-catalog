# Data visualization and fintech screens

## Choosing the chart (message first - FT Visual Vocabulary logic)
Decide the message, then its data pattern, then the chart:
| Pattern | Use | Avoid |
|---|---|---|
| Change over time | line, area (one series), column for discrete periods, candlestick for OHLC | pie, 3D |
| Ranking | sorted horizontal bars, dot plot | unsorted bars |
| Part-to-whole | stacked bar, treemap, waffle | pie with > 4 slices, donut charts as decoration |
| Deviation | diverging bars around zero/target, variance bars (IBCS) | dual axes |
| Distribution | histogram, beeswarm, box plot | averages alone |
| Correlation | scatter (+ trend), heatmap | |
| Flow | sankey (money/orders moving between states) | spaghetti lines |
| Magnitude | big number + sparkline, bars | gauges unless there's a real threshold |

## Tufte rules, applied
- Lie factor ~1: bars start at zero; no truncated axes on bars; same scale on before/after.
- Data-ink: remove borders, backgrounds, 3D, shadows, gradients on marks; gridlines 1px at 10-15% opacity or none.
- Direct labels at the line end instead of legends; annotate the event that matters.
- Small multiples on shared scales for comparisons across desks/regions.
- Sparklines beside KPIs: mark last value (dot), high/low (tiny marks), normal band (gray).
- Units once in the title/subtitle; period always stated.

## ECharts 6 setup (`<!-- @vendor echarts -->`, global `echarts`)
```js
const css = n => getComputedStyle(document.documentElement).getPropertyValue(n).trim();
const chart = echarts.init(el, null, { renderer: 'canvas' });   // 'svg' for crisp static charts
chart.setOption({
  animationDuration: 900, animationEasing: 'cubicOut',
  textStyle: { fontFamily: 'Instrument Sans, sans-serif', color: '#c9cfd8' },
  grid: { left: 8, right: 56, top: 24, bottom: 24, containLabel: true },
  xAxis: { type: 'time', axisLine: { show: false }, axisTick: { show: false }, splitLine: { show: false } },
  yAxis: { type: 'value', splitLine: { lineStyle: { color: 'rgba(255,255,255,.08)' } } },
  tooltip: { trigger: 'axis', backgroundColor: '#11151c', borderWidth: 0, textStyle: { color: '#eef1f5' } },
  series: [{ type: 'line', data, showSymbol: false, smooth: 0.2, lineStyle: { width: 2.5, color: '#43d6a8' },
    areaStyle: { opacity: .12 }, endLabel: { show: true, formatter: p => fmt(p.value[1]) } }]
});
addEventListener('resize', () => chart.resize());
```
- Give ECharts hex/rgb colors (convert OKLCH tokens to hex for charts - safest across renderers).
- Large streaming series: `chart.setOption({series:[{data}]})` on a timer, `animationDurationUpdate: 300`;
  for > 10k points use `sampling: 'lttb'`.
- Reduced motion: `animation: false`.
- Also built in: candlestick, heatmap, treemap, sankey, graph, gauge, calendar, parallel, themeRiver.

## D3 7 (`<!-- @vendor d3 -->`, global `d3`) - bespoke story visuals
Use for custom shapes: an animated ledger, a flow of orders, a force layout of systems, a hand-built sparkline.
`d3.scaleLinear`, `d3.line().curve(d3.curveMonotoneX)`, `d3.area`, `d3.stack`, `d3.forceSimulation`,
`selection.transition().duration(600).ease(d3.easeCubicOut)`.

## Deterministic, plausible simulated data
```js
function mulberry32(a) { return () => { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a);
  t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }
const rnd = mulberry32(20261028);
function walk(n, start, vol) { let v = start; return Array.from({ length: n }, () => (v *= 1 + (rnd() - .5) * vol)); }
```
Business-plausible shapes: intraday volume is U-shaped (open and close spikes); settlement fails cluster near cutoffs;
latency is long-tailed (report p50 and p99); never perfectly smooth curves. Same seed every run = rehearsable demo.

## Fintech / brokerage screen patterns
- **Trading-grade density:** near-monochrome dark surface so up/down stay legible at small sizes; tabular mono
  numerals; 1px separators; delta chips (+1.2% with arrow) instead of colored text alone.
- **Tick flash:** background flashes up/down color for ~900 ms on change, then fades (recipe 7).
- **KPI tile:** label (small caps, muted) / value (big, tabular) / delta chip / sparkline / timestamp.
- **Ledger visual (double-entry):** every movement shown as a balanced pair - debit and credit lines animate
  together, a running "balanced = 0" check stays green. Never show one side alone; totals must reconcile on screen.
- **Lifecycle flow:** order -> execution -> allocation -> clearing -> settlement as a horizontal track; items flow as
  dots; bottlenecks pile up visibly (the "what is" pain); the modern state flows straight through.
- **Real-time vs batch:** the batch world shows a frozen overnight gap (a gray band), the real-time world a continuous
  line - same axis, same day.
- **Resilience drill:** a node map; the presenter "pulls the plug"; traffic re-routes; a counter "orders lost: 0"
  holds; label "simulated replay of test <date>".
- Market hours in ET; T+1 settlement language as the audience uses it.

Sources: data.europa.eu FT visual vocabulary guide; edwardtufte.com sparklines; Georgia Tech Tufte notes; IBCS 2018
PDF; tanstack.com chart comparison; lightningchart.com (ECharts large-data notes); adminlte.io trading dashboard
review (2026); echarts 6.1.0 API (loaded and verified offline).
