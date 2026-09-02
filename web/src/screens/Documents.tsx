// Документы БП-PA из модели (TZ-OUT-001, шаг 16 §2.4; список после MCR, п. 7).
//
// Документ — чистая функция модели: ручное дополнение текста не сохраняется,
// правка вносится в модель. Пустой раздел не выбрасывается — он остаётся на
// месте, а рядом стоит разрыв со словами регламента о том, что там должно быть.
//
// Человекочитаемость (п. 7б): записи разделов — таблицами с русскими
// колонками (подписи полей — серверный словарь, коды в интерфейс не выходят),
// а не сырым JSON. Разрывы (п. 7а) — сгруппированы по разделу и сути,
// объект в разрыве кликабелен: клик ведёт в его реестр, в инспектор.
import { useEffect, useState, type ReactNode } from 'react'
import { api } from '../api/client'
import { requestObject, screenOfObject, takeDocTemplate, OBJECT_ID } from '../api/intent'
import { useSession } from '../ui/session'
import type { DocumentIssuesView, GeneratedDocumentView } from '../api/types'

/** Знаки операторов сравнения показателей — как в матрицах. */
const OPERATOR: Record<string, string> = { ge: '≥', le: '≤', gt: '>', lt: '<', eq: '=' }

function isQuantity(v: Record<string, unknown>): boolean {
  return 'value' in v && ('unit' in v || 'provenance' in v)
}

/** Ссылка на объект модели: клик открывает его в родном реестре. */
function ObjectLink({ id, onGo }: { id: string; onGo?: (screen: string) => void }) {
  const screen = screenOfObject(id)
  if (!screen || !onGo) return <span className="mono">{id}</span>
  return (
    <button type="button" className="id" style={{ cursor: 'pointer', border: 0, background: 'none', padding: 0 }}
      title="открыть объект в его реестре"
      onClick={() => { requestObject(id); onGo(screen) }}>
      {id}
    </button>
  )
}

/**
 * Значение записи — по-человечески: величины числом с единицей, показатели
 * со знаком сравнения, ссылки кликабельны, и никакого сырого JSON.
 */
function Value({ v, onGo }: { v: unknown; onGo?: (screen: string) => void }): ReactNode {
  if (v == null) return ''
  if (typeof v === 'boolean') return v ? 'да' : 'нет'
  if (typeof v === 'number') return String(v)
  if (typeof v === 'string') {
    if (OBJECT_ID.test(v)) return <ObjectLink id={v} onGo={onGo} />
    return v
  }
  if (Array.isArray(v)) {
    if (v.length === 0) return ''
    return (
      <>
        {v.map((item, i) => (
          <span key={i}>
            {i > 0 && ', '}
            <Value v={item} onGo={onGo} />
          </span>
        ))}
      </>
    )
  }
  const o = v as Record<string, unknown>
  // показатель: имя, знак, величина
  if ('operator' in o && 'value' in o) {
    const q = o.value as Record<string, unknown> | undefined
    return (
      <>
        {o.name ? `${String(o.name)} ` : ''}
        {OPERATOR[String(o.operator)] ?? String(o.operator)}{' '}
        <Value v={q} onGo={onGo} />
      </>
    )
  }
  // величина: значение и единица («1» — безразмерная)
  if (isQuantity(o)) {
    const unit = String(o.unit ?? '')
    return `${String(o.value)}${unit && unit !== '1' ? ` ${unit}` : ''}`
  }
  // ссылка вида {ref: "SV-0101", ...}
  if ('ref' in o && typeof o.ref === 'string') {
    const extra = Object.entries(o).filter(([k]) => k !== 'ref')
    return (
      <>
        <Value v={o.ref} onGo={onGo} />
        {extra.length > 0 && (
          <span className="secondary"> ({extra.map(([, val]) => String(val)).join(', ')})</span>
        )}
      </>
    )
  }
  // прочий объект — пары «поле: значение», без фигурных скобок
  return (
    <>
      {Object.entries(o).map(([k, val], i) => (
        <span key={k}>
          {i > 0 && '; '}
          <span className="secondary">{k}: </span>
          <Value v={val} onGo={onGo} />
        </span>
      ))}
    </>
  )
}

/** Первый «текстовый» ключ записи — для сводной строки схлопнутого вида. */
const HEADLINE_KEYS = ['statement', 'name', 'title', 'question', 'gate', 'rule', 'kind', 'code']

/**
 * Раздел — таблица. Длинный раздел с широкими записями (спецификация на 200+
 * требований — «километр листания», находка прогона) схлопывается: строка —
 * id и формулировка, остальные атрибуты раскрываются по клику. expandAll
 * (кнопка «Развернуть всё» и печать) раскрывает принудительно.
 */
/** МВП-М2 §3.5: вставка «таблица сравнения построений» в разделе AoA. */
function CompareInsert({ item }: { item: Record<string, unknown> }) {
  const variants = (item.variants ?? []) as Array<{
    variant: string; name: string; total_sats: number
    service: Record<string, { coverage_share: number; max_gap_s: number; latency_s: number }>
    logistics: { launch_batches: number; deployment_days: number; cost_proxy: number }
  }>
  return (
    <div style={{ margin: '4px 0 8px' }}>
      <div className="secondary" style={{ marginBottom: 4 }}
        title="живая матрица сравнения построений — последний расчёт; выпуск фиксирует снимок">
        Сравнение построений · {String(item.scenario_ref ?? '')} · {String(item.computed_at ?? '').slice(0, 16).replace('T', ' ')}
      </div>
      <table style={{ minWidth: 560 }}>
        <thead>
          <tr>
            <th>Вариант</th><th style={{ width: 46 }}>КА</th>
            <th style={{ width: 90 }} title="покрытие A′ — доля времени, взвешено спросом">Покр. A′</th>
            <th style={{ width: 90 }} title="худший по классам максимальный разрыв">Max gap</th>
            <th style={{ width: 90 }} title="худшая латентность доставки">Латентн.</th>
            <th style={{ width: 66 }} title="несовместимые пусковые партии">Партии</th>
            <th style={{ width: 80 }} title="прокси-стоимость, у.е.">Стоим.</th>
          </tr>
        </thead>
        <tbody>
          {variants.map((v) => {
            const gaps = Object.values(v.service ?? {})
            const gap = gaps.reduce((m, s) => (s.max_gap_s > m ? s.max_gap_s : m), 0)
            const lat = gaps.reduce((m, s) => (s.latency_s > m ? s.latency_s : m), 0)
            return (
              <tr key={v.variant}>
                <td><span className="mono">{v.variant}</span> {v.name}</td>
                <td className="num">{v.total_sats}</td>
                <td className="num">{((v.service?.A_prime?.coverage_share ?? 0) * 100).toFixed(1)}%</td>
                <td className="num">{(gap / 60).toFixed(0)} мин</td>
                <td className="num">{(lat / 60).toFixed(0)} мин</td>
                <td className="num">{v.logistics?.launch_batches}</td>
                <td className="num">{v.logistics?.cost_proxy?.toFixed(1)}</td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

function SectionTable({ items, fieldLabel, onGo, expandAll }: {
  items: Array<Record<string, unknown>>
  fieldLabel: (name: string) => string
  onGo?: (screen: string) => void
  expandAll: boolean
}) {
  // вставки со своим рендером — отдельно от табличных записей раздела
  const inserts = items.filter((it) => it.kind === 'constellation_compare_table')
  if (inserts.length > 0) {
    const rest = items.filter((it) => it.kind !== 'constellation_compare_table')
    return (
      <div>
        {inserts.map((it, i) => <CompareInsert key={i} item={it} />)}
        {rest.length > 0 && (
          <SectionTable items={rest} fieldLabel={fieldLabel} onGo={onGo} expandAll={expandAll} />
        )}
      </div>
    )
  }
  const columns: string[] = []
  items.forEach((it) => Object.keys(it).forEach((k) => { if (!columns.includes(k)) columns.push(k) }))
  const compact = !expandAll && items.length > 12 && columns.length > 4
  if (!compact) {
    return (
      <div style={{ overflowX: 'auto' }}>
        <table style={{ minWidth: 560 }}>
          <thead>
            <tr>
              {columns.map((c) => <th key={c}>{fieldLabel(c)}</th>)}
            </tr>
          </thead>
          <tbody>
            {items.map((it, i) => (
              <tr key={i}>
                {columns.map((c) => (
                  <td key={c} className="wrap" style={{ verticalAlign: 'top' }}>
                    <Value v={it[c]} onGo={onGo} />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    )
  }
  const head = HEADLINE_KEYS.find((k) => columns.includes(k))
  return (
    <div>
      {items.map((it, i) => {
        const id = String(it.id ?? it.requirement ?? i + 1)
        const rest = columns.filter((c) => c !== 'id' && c !== head)
        return (
          <details key={i} className="docrow">
            <summary>
              <span className="mono" style={{ marginRight: 8 }}>{id}</span>
              {head ? String(it[head] ?? '') : ''}
            </summary>
            <div className="docrow__body">
              {rest.map((c) => (
                it[c] == null || it[c] === '' ? null : (
                  <div key={c} className="docrow__field">
                    <span className="secondary">{fieldLabel(c)}: </span>
                    <Value v={it[c]} onGo={onGo} />
                  </div>
                )
              ))}
            </div>
          </details>
        )
      })}
    </div>
  )
}

/** Разрыв, разобранный для группировки: объект (если назван) и суть. */
/**
 * Шип 1 «трёх пакетов»: раздел с режимом [С] — связный текст. Действие
 * «Написать связно» живёт У РАЗДЕЛА, не у шага задачи: любой шаблон полки с
 * режимом prose на разделе получает его. Черновик пишет служба из данных
 * вставок раздела (вид section_prose), инженер правит и принимает —
 * черновик в документ сам не пишется. Режим — свойство шаблона (данными).
 */
function ProseSection({ code, section, author, onGo, onSaved, expandAll }: {
  code: string
  section: GeneratedDocumentView['body']['sections'][number]
  author: string
  onGo?: (screen: string, kind?: string, target?: string) => void
  onSaved: () => void
  /** Таблица смешанного раздела — основная часть: раскрыта, как и остальные записи экрана. */
  expandAll: boolean
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(section.text ?? '')
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const режим = section.mode === 'manual' ? 'рука'
    : section.mode === 'prose_table' ? 'абзац связного текста и таблица данных'
      : 'связный текст'

  const save = () => {
    setBusy(true)
    api.saveSectionText(code, section.number, draft, author)
      .then(() => { setNotice('текст принят'); setEditing(false); onSaved() })
      .catch((e) => setNotice(String(e).slice(0, 200)))
      .finally(() => setBusy(false))
  }

  return (
    <div className="doc-prose">
      <div className="secondary" style={{ marginBottom: 4 }}>
        режим раздела: {режим}
        {section.text_stale && (
          <span className="warn"> · текст устарел: данные вставок изменились
            {section.text_diff && section.text_diff.length > 0 && ` — разошлось: ${section.text_diff.join('; ')}`}
          </span>
        )}
      </div>
      {editing ? (
        <div>
          <textarea rows={8} style={{ width: '100%' }} value={draft}
            onChange={(e) => setDraft(e.target.value)} />
          <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
            <button type="button" className="btn btn--primary" disabled={busy || !author || !draft.trim()}
              onClick={save}
              title={!author ? 'представьтесь в шапке: текст подписывается автором'
                : !draft.trim() ? 'пустой текст принимать нечего' : 'принять текст как авторский'}>
              Принять
            </button>
            <button type="button" className="btn" onClick={() => { setEditing(false); setDraft(section.text ?? '') }}>
              Отмена
            </button>
          </div>
        </div>
      ) : section.text
        ? <div className="doc-prose__text" style={{ whiteSpace: 'pre-wrap' }}>{section.text}</div>
        : <div className="empty" style={{ padding: 8 }}>
            Связного текста нет. Регламент ожидает: {section.expects}
          </div>}
      {!editing && (
        <div style={{ display: 'flex', gap: 8, marginTop: 6, alignItems: 'center' }}>
          {section.mode !== 'manual' && onGo && (
            <button type="button" className="btn"
              onClick={() => onGo('aiservice', 'section_prose', `${code}#${section.number}`)}
              title="черновик пишет служба из данных вставок этого раздела (и только их); принять — правкой здесь">
              Написать связно
            </button>
          )}
          <button type="button" className="btn" onClick={() => { setDraft(section.text ?? ''); setEditing(true) }}
            title={section.text ? 'править принятый текст' : 'написать текст рукой'}>
            {section.text ? 'Править' : 'Написать рукой'}
          </button>
          {notice && <span className="secondary">{notice}</span>}
        </div>
      )}
      {section.items.length > 0 && section.mode === 'prose_table' && (
        <div style={{ marginTop: 8 }}>
          <SectionTable items={section.items} fieldLabel={() => ''} onGo={onGo} expandAll={expandAll || section.items.length <= 25} />
        </div>
      )}
      {section.items.length > 0 && section.mode !== 'prose_table' && (
        <details style={{ marginTop: 6 }}>
          <summary className="secondary">данные вставок раздела · {section.items.length}</summary>
          <SectionTable items={section.items} fieldLabel={() => ''} onGo={onGo} expandAll={false} />
        </details>
      )}
    </div>
  )
}

function parseGap(g: { section: number; what: string; expected: string }) {
  const m = g.what.match(/^([A-Z]{2,3}-[0-9]{4}): (.+)$/)
  return { section: g.section, id: m?.[1] ?? null, what: m?.[2] ?? g.what, expected: g.expected }
}

export function Documents({ onGo, initialCode }: {
  onGo?: (screen: string, kind?: string, target?: string) => void
  /** Шаг задачи фазы ведёт сюда УЖЕ настроенным на свой шаблон (SEMP, ConOps). */
  initialCode?: string
}) {
  const [templates, setTemplates] = useState<Array<{ code: string; title: string; source: string }>>([])
  const [code, setCode] = useState(initialCode ?? '')
  const [doc, setDoc] = useState<GeneratedDocumentView | null>(null)
  const [issues, setIssues] = useState<DocumentIssuesView | null>(null)
  const [issueReport, setIssueReport] = useState<string | null>(null)
  const [issuing, setIssuing] = useState(false)
  const [showGaps, setShowGaps] = useState(true)
  /** «Развернуть всё»: длинные разделы схлопнуты по умолчанию; печать
   *  разворачивает сама (beforeprint) — на бумагу идёт полный документ. */
  const [expandAll, setExpandAll] = useState(false)

  useEffect(() => {
    const before = () => setExpandAll(true)
    window.addEventListener('beforeprint', before)
    return () => window.removeEventListener('beforeprint', before)
  }, [])
  const [error, setError] = useState<string | null>(null)
  const { author, fieldLabel } = useSession()

  const loadIssues = (c: string) => {
    api.documentIssues(c).then(setIssues).catch(() => setIssues(null))
  }

  useEffect(() => {
    api
      .documentTemplates()
      .then((rows) => {
        setTemplates(rows)
        // Переход «к документу» со строки готовности (п. 7а): экран
        // открывается сразу на названном шаблоне, а не на первом попавшемся.
        const wanted = takeDocTemplate()
        if (wanted && rows.some((r) => r.code === wanted)) setCode(wanted)
        else if (rows.length > 0) setCode((cur) => cur || rows[0].code)
      })
      .catch((e) => setError(String(e)))
  }, [])

  useEffect(() => {
    if (!code) return
    setDoc(null)
    setIssueReport(null)
    api.document(code).then(setDoc).catch((e) => setError(String(e)))
    loadIssues(code)
  }, [code])

  // Выпуск (Шаг 17 C5): дата — аргумент; здесь её даёт действие инженера,
  // а слепок и объект выпуска делает сервер
  const issue = () => {
    setIssueReport(null)
    setIssuing(true)
    api
      .issueDocument(code, new Date().toISOString(), author)
      .then(() => {
        setIssueReport('выпущено')
        loadIssues(code)
      })
      .catch((e) => setIssueReport(String(e).slice(0, 200)))
      .finally(() => setIssuing(false))
  }

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>

  // Разрывы: по разделу, внутри — по сути; объекты одной сути — перечнем
  const gapGroups = new Map<string, { section: number; what: string; expected: string; ids: string[] }>()
  doc?.gaps.forEach((g) => {
    const p = parseGap(g)
    const key = `${p.section}|${p.what}`
    const group = gapGroups.get(key) ?? { section: p.section, what: p.what, expected: p.expected, ids: [] }
    if (p.id) group.ids.push(p.id)
    gapGroups.set(key, group)
  })
  const gapsBySection = new Map<number, Array<{ what: string; expected: string; ids: string[] }>>()
  Array.from(gapGroups.values())
    .sort((a, b) => a.section - b.section || a.what.localeCompare(b.what, 'ru'))
    .forEach((g) => {
      const list = gapsBySection.get(g.section) ?? []
      list.push(g)
      gapsBySection.set(g.section, list)
    })

  return (
    <div className="pane doc-screen" style={{ gridArea: 'main', overflow: 'auto', padding: 16 }}>
      <div className="tabs doc-tabs" style={{ marginBottom: 8, flexWrap: 'wrap' }}>
        {templates.map((t) => (
          <button key={t.code} className="tab" aria-selected={t.code === code} onClick={() => setCode(t.code)}>
            {t.title}
          </button>
        ))}
      </div>

      {!doc ? (
        <div className="secondary">Сборка документа…</div>
      ) : (
        <>
          {/* Титул печатной формы: реквизиты документа, не экрана */}
          <div className="doc-title">
            <h2 style={{ fontSize: 16, margin: '4px 0 2px' }}>{doc.body.title}</h2>
            <div className="secondary">
              {doc.body.source} · слепок <span className="mono">{doc.digest.slice(0, 16)}</span>
              {doc.gaps.length > 0
                ? <span className="warn"> · разрывов {doc.gaps.length}</span>
                : <span> · разрывов нет</span>}
            </div>
            <p className="secondary doc-note" style={{ marginTop: 2 }}>
              Документ собран из модели: тот же вход даёт тот же текст, правка вносится
              в модель, не в документ.
            </p>
          </div>

          <div className="field doc-actions" style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            {/* Кнопка, оставшаяся кликабельной после нажатия, копила дубли
                выпусков одного слепка (находка прогона: четырнадцать выпусков
                с одним digest). Блокируется на время запроса И пока текущий
                слепок уже выпущен — переиздавать без изменений модели нечего. */}
            {(() => {
              const already = issues?.issues.find((i) => i.digest === doc.digest)
              const disabled = !author || issuing || already != null
              return (
                <button type="button" className="tab tab--primary" disabled={disabled} onClick={issue}
                  title={!author ? 'представьтесь в шапке'
                    : already ? `этот слепок уже выпущен (${already.id}) — переиздание не нужно`
                    : 'зафиксировать слепок текущей генерации'}>
                  {issuing ? 'Выпуск…' : already ? `Выпущено (${already.id})` : 'Выпустить'}
                </button>
              )
            })()}
            <button type="button" className="tab" onClick={() => setExpandAll((v) => !v)}
              title="длинные разделы схлопнуты до строки «id — формулировка»">
              {expandAll ? 'Свернуть записи' : 'Развернуть всё'}
            </button>
            <button type="button" className="tab"
              onClick={() => { setExpandAll(true); setTimeout(() => window.print(), 50) }}
              title="печатная форма: без оболочки, только документ (записи развёрнуты)">
              Печать
            </button>
            {/* В1.4/О-8: файл уходит людям без Орбиты — docx и PDF рендерит
                сервер; текущая генерация помечается черновиком просмотра,
                печать выпуска — из таблицы выпусков ниже */}
            <a className="tab" href={api.printUrl(code, 'docx')}
              title="скачать текущую генерацию файлом Word (черновик просмотра)">
              docx
            </a>
            <a className="tab" href={api.printUrl(code, 'pdf')}
              title="скачать текущую генерацию файлом PDF (черновик просмотра)">
              PDF
            </a>
            {issueReport && <span className="secondary">{issueReport}</span>}
          </div>

          {issues && issues.issues.length > 0 && (
            <table className="doc-issues" style={{ marginBottom: 8, maxWidth: 720 }}>
              <thead>
                <tr>
                  <th style={{ width: 90 }}>Выпуск</th>
                  <th style={{ width: 150 }}>Дата</th>
                  <th style={{ width: 90 }}>Статус</th>
                  <th style={{ width: 80 }}>Разрывов</th>
                  <th>Слепок</th>
                  <th style={{ width: 110 }}>Печать</th>
                </tr>
              </thead>
              <tbody>
                {issues.issues.map((i) => (
                  <tr key={i.id}>
                    <td><ObjectLink id={i.id} onGo={onGo} /></td>
                    <td className="mono">{i.issued_at.slice(0, 16).replace('T', ' ')}</td>
                    <td>{i.status}</td>
                    <td className="num">{i.gaps}</td>
                    <td className="mono">
                      {i.digest.slice(0, 12)}
                      {/* расхождение слепков — факт, а не ощущение */}
                      {i.stale && <span className="warn"> устарел: модель ушла вперёд</span>}
                    </td>
                    <td>
                      <a className="mono" href={api.printUrl(code, 'docx', i.id)}>docx</a>
                      {' · '}
                      <a className="mono" href={api.printUrl(code, 'pdf', i.id)}>PDF</a>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {doc.gaps.length > 0 && (
            <div className="card doc-gaps" style={{ marginBottom: 10 }}>
              <h3 style={{ cursor: 'pointer' }} onClick={() => setShowGaps((v) => !v)}>
                Разрывы — {doc.gaps.length} {showGaps ? '▾' : '▸'}
              </h3>
              {showGaps && (
                <div>
                  {Array.from(gapsBySection.entries()).map(([section, groups]) => {
                    const title = doc.body.sections.find((s) => s.number === section)?.title
                    return (
                      <div key={section} style={{ marginBottom: 8 }}>
                        <b>§{section}{title ? ` ${title}` : ''}</b>
                        {groups.map((g, i) => (
                          <div key={i} style={{ margin: '3px 0 3px 12px' }}>
                            <span className="warn">△ {g.what}</span>
                            <span className="secondary"> — {g.expected}</span>
                            {g.ids.length > 0 && (
                              <div style={{ marginLeft: 16 }}>
                                {g.ids.length > 1 && <span className="secondary">{g.ids.length} объектов: </span>}
                                {g.ids.map((id, j) => (
                                  <span key={id}>
                                    {j > 0 && ', '}
                                    <ObjectLink id={id} onGo={onGo} />
                                  </span>
                                ))}
                              </div>
                            )}
                            {/* Разрыв о самом проекте (назначение, область,
                                перспектива) не несёт id — дверь в паспорт
                                (замечание прогона: «нельзя перейти и
                                поправить») */}
                            {g.ids.length === 0 && g.what.startsWith('проект') && onGo && (
                              <div style={{ marginLeft: 16 }}>
                                <button type="button" className="btn"
                                  onClick={() => onGo('projreg')}>
                                  → Паспорт проекта
                                </button>
                              </div>
                            )}
                          </div>
                        ))}
                      </div>
                    )
                  })}
                </div>
              )}
            </div>
          )}

          {doc.body.sections.map((s) => (
            <details key={s.number} className="docsection" open={expandAll || s.items.length <= 25}>
              <summary>
                <h3 style={{ fontSize: 13.5, display: 'inline' }}>
                  {s.number}. {s.title}
                </h3>
                <span className="secondary"> · записей: {s.items.length}</span>
              </summary>
              {s.mode === 'prose' || s.mode === 'manual' || s.mode === 'prose_table' ? (
                <ProseSection code={code} section={s} author={author} onGo={onGo} expandAll={expandAll}
                  onSaved={() => api.document(code).then(setDoc).catch(() => undefined)} />
              ) : s.items.length === 0 ? (
                <div className="empty" style={{ padding: 8 }}>
                  Раздел пуст. Регламент ожидает: {s.expects}
                </div>
              ) : (
                <SectionTable items={s.items} fieldLabel={fieldLabel} onGo={onGo} expandAll={expandAll} />
              )}
            </details>
          ))}
        </>
      )}
    </div>
  )
}
