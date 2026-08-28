// Сравнение построений (МВП-М2 §3): строки — варианты, колонки — метрики
// группами; сортировка заголовком; Парето-подсветка (оси переключаемы,
// недоминируемых считает СЕРВЕР); пороги требований — фильтром с перечнем
// отсеянных. Никакого итогового балла (ловушка 1) — сравнение = таблица.
// Группа Г — с пометкой «прокси» и подсказкой, чем посчитано.
import { useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import { edit, type StoredSummary } from '../api/edit'
import type { CompareVariantRow, ConstellationCompareView } from '../api/types'
import { Select } from '../ui/Select'
import { useSession } from '../ui/session'
import { SortTh, useSort } from '../ui/sort'

const AXES: Array<{ key: string; title: string }> = [
  { key: 'capacity', title: 'запас ёмкости' },
  { key: 'max_gap', title: 'max gap' },
  { key: 'cost', title: 'стоимость' },
  { key: 'deployment_days', title: 'развёртывание' },
  { key: 'degradation', title: 'деградация' },
]

const THRESHOLD_METRICS: Array<{ key: string; title: string; higher: boolean }> = [
  { key: 'coverage_A_prime', title: "покрытие A′ ≥", higher: true },
  { key: 'coverage_C_prime', title: "покрытие C′ ≥", higher: true },
  { key: 'max_gap', title: 'max gap ≤, с', higher: false },
  { key: 'latency_A_prime', title: "латентность A′ ≤, с", higher: false },
  { key: 'latency_C_prime', title: "латентность C′ ≤, с", higher: false },
  { key: 'cost', title: 'стоимость ≤, у.е.', higher: false },
]

const fmt0 = (v?: number) => (v === undefined || Number.isNaN(v) ? '—' : v.toFixed(0))
const fmt1 = (v?: number) => (v === undefined || Number.isNaN(v) ? '—' : v.toFixed(1))
const fmtPct = (v?: number) => (v === undefined ? '—' : `${(v * 100).toFixed(1)}%`)
const fmtMin = (s?: number) => (s === undefined ? '—' : `${(s / 60).toFixed(0)} мин`)

export function ConstellationCompare() {
  const { author } = useSession()
  const [scenarios, setScenarios] = useState<StoredSummary[]>([])
  const [scenario, setScenario] = useState('')
  const [variants, setVariants] = useState<StoredSummary[]>([])
  const [working, setWorking] = useState('')
  const [picked, setPicked] = useState<Set<string>>(new Set())
  const [axes, setAxes] = useState<string[]>(['capacity', 'max_gap', 'cost'])
  const [thresholds, setThresholds] = useState<Array<{ metric: string; value: string; label: string }>>([])
  const [view, setView] = useState<ConstellationCompareView | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const reload = () => {
    edit.list('scenario').then((rows) => {
      setScenarios(rows)
      setScenario((cur) => cur || rows[0]?.id || '')
    }).catch((e) => setError(String(e)))
    edit.list('constellation').then(setVariants).catch((e) => setError(String(e)))
  }
  useEffect(reload, [])
  useEffect(() => {
    if (!scenario) return
    edit.object(scenario)
      .then((s) => setWorking((s.doc as { constellation_ref?: string }).constellation_ref ?? ''))
      .catch(() => setWorking(''))
  }, [scenario])

  const compare = () => {
    if (busy || picked.size < 2) return
    setBusy(true)
    setError(null)
    setNotice(null)
    api.constellationCompare({
      scenario,
      variants: [...picked],
      axes,
      thresholds: thresholds
        .filter((t) => t.value.trim() !== '')
        .map((t) => ({ metric: t.metric, value: Number(t.value), label: t.label || undefined })),
    })
      .then(setView)
      .catch((e) => {
        if (e instanceof ApiError && e.status === 409) setNotice(e.body.slice(0, 300))
        else setError(String(e))
      })
      .finally(() => setBusy(false))
  }

  const makeWorking = (id: string) => {
    api.setWorkingConstellation(scenario, id, author || 'инженер')
      .then(() => { setWorking(id); setNotice(`рабочий вариант — ${id}: его считает карта и показатели`) })
      .catch((e) => setNotice(String(e)))
  }

  const rows = view?.variants ?? []
  const { sorted, sort, toggle } = useSort(rows, {
    name: (r: CompareVariantRow) => r.name,
    sats: (r) => r.total_sats,
    covA: (r) => r.service.A_prime?.coverage_share ?? -1,
    covC: (r) => r.service.C_prime?.coverage_share ?? -1,
    gap: (r) => worstGap(r),
    lat: (r) => worstLatency(r),
    cap: (r) => minCapacity(r) ?? -1,
    batches: (r) => r.logistics.launch_batches,
    deploy: (r) => r.logistics.deployment_days,
    cost: (r) => r.logistics.cost_proxy,
    degr: (r) => r.resilience.degradation_dmax_gap_s,
    shadow: (r) => worstShadow(r),
    stations: (r) => r.orbit_proxy.stations_for_latency,
  })

  return (
    <>
      <div className="toolbar">
        <h2>Сравнение построений</h2>
        <span className="secondary">Сценарий:</span>
        <Select value={scenario} width={260} placeholder="сценарий"
          options={scenarios.map((s) => ({ key: s.id, title: `${s.id}${s.title ? ` — ${s.title}` : ''}` }))}
          onChange={setScenario} />
        <div className="grow" />
        <button className="btn btn--primary" disabled={busy || picked.size < 2 || picked.size > 5 || !scenario}
          title="сравнить выбранные варианты (2–5) по метрикам групп А–Г"
          onClick={compare}>
          {busy ? 'Считаю…' : `Сравнить · ${picked.size}`}
        </button>
      </div>
      <div className="workarea" style={{ padding: '10px 16px', overflow: 'auto' }}>
        {error && <div className="warn" style={{ padding: 8 }}>{error}</div>}
        {notice && <div className="notice" style={{ marginBottom: 8 }}>{notice}</div>}

        <div className="card" style={{ marginBottom: 10 }}>
          <h3>Варианты построений <span className="count">{variants.length}</span></h3>
          <div>
            <p className="secondary" style={{ margin: '2px 0 6px' }}>
              Вариант — обычный объект (версии, происхождение); заводится в «Входах
              моделирования». Рабочий (★) считает карта и показатели требований;
              смена — явным действием.
            </p>
            {variants.map((v) => (
              <div key={v.id} className="sp-file" style={{ padding: '3px 0' }}>
                <input type="checkbox" checked={picked.has(v.id)}
                  title="в сравнение (2–5)"
                  onChange={(e) => setPicked((prev) => {
                    const next = new Set(prev)
                    if (e.target.checked) next.add(v.id)
                    else next.delete(v.id)
                    return next
                  })} />
                <span className="mono">{v.id}</span>
                <span>{v.title ?? ''}</span>
                {v.id === working
                  ? <span title="рабочий вариант: его считает карта покрытия и показатели">★ рабочий</span>
                  : (
                    <button className="rr-assign" title="сделать рабочим — смена ссылки сценария с основанием"
                      onClick={() => makeWorking(v.id)}>
                      сделать рабочим
                    </button>
                  )}
              </div>
            ))}
            {variants.length === 0 && (
              <div className="secondary">построений нет — заведите на «Входах моделирования»</div>
            )}
          </div>
        </div>

        <div className="card" style={{ marginBottom: 10 }}>
          <h3>Пороги требований и оси Парето</h3>
          <div>
            <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap', marginBottom: 6 }}>
              <span className="secondary" title="вариант, не прошедший порог, отсеивается с перечнем — честно, не молча">
                пороги:
              </span>
              {thresholds.map((t, i) => (
                <span key={i} style={{ display: 'flex', gap: 4, alignItems: 'center' }}>
                  <Select value={t.metric} width={180}
                    options={THRESHOLD_METRICS.map((m) => ({ key: m.key, title: m.title }))}
                    onChange={(m) => setThresholds((prev) => prev.map((x, j) => (j === i ? { ...x, metric: m } : x)))} />
                  <input style={{ width: 80 }} value={t.value} placeholder="значение"
                    onChange={(e) => setThresholds((prev) => prev.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)))} />
                  <input style={{ width: 110 }} value={t.label} placeholder="подпись (RQ-…)"
                    title="имя порога в перечне отсеянных — например, требование постановки"
                    onChange={(e) => setThresholds((prev) => prev.map((x, j) => (j === i ? { ...x, label: e.target.value } : x)))} />
                  <button className="rr-assign" title="убрать порог"
                    onClick={() => setThresholds((prev) => prev.filter((_, j) => j !== i))}>✕</button>
                </span>
              ))}
              <button className="rr-assign" onClick={() => setThresholds((p) => [...p, { metric: 'latency_A_prime', value: '', label: '' }])}>
                + порог
              </button>
            </div>
            <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
              <span className="secondary" title="Парето: недоминируемые по выбранным осям подсвечены; считает сервер">
                оси Парето (2–3):
              </span>
              {AXES.map((a) => (
                <button key={a.key} className="tab" aria-selected={axes.includes(a.key)}
                  title={`ось «${a.title}»`}
                  onClick={() => setAxes((prev) => (prev.includes(a.key)
                    ? prev.filter((x) => x !== a.key)
                    : prev.length >= 3 ? prev : [...prev, a.key]))}>
                  {a.title}
                </button>
              ))}
            </div>
          </div>
        </div>

        {view && (
          <>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ minWidth: 1280 }}>
                <thead>
                  <tr>
                    <th colSpan={2} />
                    <th colSpan={5} title="группа А: обслуживание — из интеграла видимости по сетке спроса">А · Обслуживание</th>
                    <th colSpan={3} title="группа Б: стоимость и логистика — из построения; коэффициенты данными">Б · Логистика</th>
                    <th colSpan={2} title="группа В: живучесть — повторный интеграл без одного КА; Δv по нормативу">В · Живучесть</th>
                    <th colSpan={4} title="группа Г — ПРОКСИ: тень/β прямой астрономией, радиация таблицей, станции перебором каталога, окно/доплер из геометрии">
                      Г · Следствия орбиты (прокси)
                    </th>
                  </tr>
                  <tr>
                    <SortTh label="Вариант" sortKey="name" sort={sort} onToggle={toggle} />
                    <SortTh label="КА" sortKey="sats" sort={sort} onToggle={toggle} width={44} />
                    <SortTh label="Покр. A′" sortKey="covA" sort={sort} onToggle={toggle} width={74} />
                    <SortTh label="Покр. C′" sortKey="covC" sort={sort} onToggle={toggle} width={74} />
                    <SortTh label="Max gap" sortKey="gap" sort={sort} onToggle={toggle} width={78} />
                    <SortTh label="Латентн." sortKey="lat" sort={sort} onToggle={toggle} width={78} />
                    <SortTh label="Ёмк. мин" sortKey="cap" sort={sort} onToggle={toggle} width={86} />
                    <SortTh label="Партии" sortKey="batches" sort={sort} onToggle={toggle} width={62} />
                    <SortTh label="Развёрт." sortKey="deploy" sort={sort} onToggle={toggle} width={72} />
                    <SortTh label="Стоим." sortKey="cost" sort={sort} onToggle={toggle} width={64} />
                    <SortTh label="Δmax gap −1КА" sortKey="degr" sort={sort} onToggle={toggle} width={104} />
                    <th style={{ width: 120 }} title="Δv поддержания в год и путь увода — по высоте и нормативу">Δv / увод</th>
                    <SortTh label="Тень худш." sortKey="shadow" sort={sort} onToggle={toggle} width={84} />
                    <th style={{ width: 70 }} title="класс радиационной среды — таблица по высоте и наклонению (прокси)">Радиац.</th>
                    <SortTh label="Станций" sortKey="stations" sort={sort} onToggle={toggle} width={68} />
                    <th style={{ width: 110 }} title="медианное окно сеанса и максимальный доплер — из геометрии (прокси)">Окно · доплер</th>
                  </tr>
                </thead>
                <tbody>
                  {sorted.map((r) => {
                    const pareto = view.pareto.includes(r.variant)
                    return (
                      <tr key={r.variant}
                        style={pareto ? { background: 'rgba(63,178,127,0.12)' } : undefined}
                        title={pareto ? `недоминируем по осям: ${view.axes.join(' · ')}` : undefined}>
                        <td>
                          <span className="mono">{r.variant}</span> {r.name}
                          {r.variant === view.working_variant && <span title="рабочий вариант"> ★</span>}
                          {pareto && <span title="Парето: недоминируем по выбранным осям"> ◆</span>}
                        </td>
                        <td className="num">{r.total_sats}</td>
                        <td className="num">{fmtPct(r.service.A_prime?.coverage_share)}</td>
                        <td className="num">{fmtPct(r.service.C_prime?.coverage_share)}</td>
                        <td className="num" title="худший по классам максимальный разрыв">{fmtMin(worstGap(r))}</td>
                        <td className="num" title="худшая по классам латентность доставки (сбор → сброс)">{fmtMin(worstLatency(r))}</td>
                        <td className="num" title="запас ёмкости: проходо-мин на сообщение спроса, минимум по зонам (канала в модели КА нет — прокси-единица)">
                          {fmt1(minCapacity(r))}
                        </td>
                        <td className="num" title="несовместимые пусковые партии (наклонение × высота)">{r.logistics.launch_batches}</td>
                        <td className="num" title="время до рабочей конфигурации, сут (RAAN — прецессией)">{fmt0(r.logistics.deployment_days)}</td>
                        <td className="num" title="прокси-стоимость, у.е.: КА × платформа + партии × выведение × попутность">{fmt1(r.logistics.cost_proxy)}</td>
                        <td className="num" title="рост max gap худшей зоны при потере одного КА худшей подгруппы">{fmtMin(r.resilience.degradation_dmax_gap_s)}</td>
                        <td className="secondary" title={`Δv поддержания ${r.resilience.station_keeping_dv_mps_year} м/с·год`}>
                          {fmt0(r.resilience.station_keeping_dv_mps_year)} · {r.resilience.disposal.split(';')[0]}
                        </td>
                        <td className="num" title={r.orbit_proxy.power_regime
                          .map((p) => `${p.name}: тень ${(p.worst_shadow_share * 100).toFixed(0)}%, β ${p.beta_min_deg.toFixed(0)}…${p.beta_max_deg.toFixed(0)}°`)
                          .join('; ') + ' — прокси прямой астрономии'}>
                          {fmtPct(worstShadow(r))}
                        </td>
                        <td title={`${r.orbit_proxy.radiation_note} — таблица-прокси`}>{r.orbit_proxy.radiation_class}</td>
                        <td className="num" title={`до целевой латентности: ${r.orbit_proxy.stations_names} — перебор каталога (прокси)`}>
                          {r.orbit_proxy.stations_for_latency}
                        </td>
                        <td className="secondary" title="медианная длительность сеанса · max доплер — из геометрии (прокси)">
                          {fmt0((r.orbit_proxy.median_pass_s ?? 0) / 60)} мин · {fmt0(r.orbit_proxy.doppler_max_hz / 1000)} кГц
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
            {view.excluded.length > 0 && (
              <div className="warn" style={{ padding: '6px 10px', marginTop: 8 }}
                title="пороги требований — фильтр: отсеянные названы поимённо">
                Отсеяны порогами: {view.excluded.map((e) => (
                  `${e.name} — по «${e.threshold}» (${fmt1(e.value)} против ${fmt1(e.limit)})`
                )).join('; ')}
              </div>
            )}
            <p className="secondary" style={{ marginTop: 8 }}>
              ◆ — Парето-недоминируемые по осям: {view.axes.join(' · ')}. Итогового
              балла нет — сравнение читается таблицей; веса владельца придут
              профилем после итога прохода. Таблица сохранена результатом и уйдёт
              вставкой в раздел AoA (отчёт о концепции, §2) — выпуск зафиксирует
              снимок.
            </p>
          </>
        )}
      </div>
    </>
  )
}

const worstGap = (r: CompareVariantRow) =>
  Object.values(r.service).reduce((m, s) => (s.max_gap_s > m ? s.max_gap_s : m), 0)
const worstLatency = (r: CompareVariantRow) =>
  Object.values(r.service).reduce((m, s) => (s.latency_s > m ? s.latency_s : m), 0)
const minCapacity = (r: CompareVariantRow): number | undefined => {
  const vals = Object.values(r.service)
    .map((s) => s.capacity_margin_min_per_msg)
    .filter((v): v is number => v !== undefined)
  if (vals.length === 0) return undefined
  return vals.reduce((m, v) => (v < m ? v : m))
}
const worstShadow = (r: CompareVariantRow) =>
  r.orbit_proxy.power_regime.reduce((m, p) => (p.worst_shadow_share > m ? p.worst_shadow_share : m), 0)
