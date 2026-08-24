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

/** Раздел — таблица: колонки собраны из записей, подписи из словаря полей. */
function SectionTable({ items, fieldLabel, onGo }: {
  items: Array<Record<string, unknown>>
  fieldLabel: (name: string) => string
  onGo?: (screen: string) => void
}) {
  const columns: string[] = []
  items.forEach((it) => Object.keys(it).forEach((k) => { if (!columns.includes(k)) columns.push(k) }))
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

/** Разрыв, разобранный для группировки: объект (если назван) и суть. */
function parseGap(g: { section: number; what: string; expected: string }) {
  const m = g.what.match(/^([A-Z]{2,3}-[0-9]{4}): (.+)$/)
  return { section: g.section, id: m?.[1] ?? null, what: m?.[2] ?? g.what, expected: g.expected }
}

export function Documents({ onGo }: { onGo?: (screen: string) => void }) {
  const [templates, setTemplates] = useState<Array<{ code: string; title: string; source: string }>>([])
  const [code, setCode] = useState('')
  const [doc, setDoc] = useState<GeneratedDocumentView | null>(null)
  const [issues, setIssues] = useState<DocumentIssuesView | null>(null)
  const [issueReport, setIssueReport] = useState<string | null>(null)
  const [showGaps, setShowGaps] = useState(true)
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
    api
      .issueDocument(code, new Date().toISOString(), author)
      .then(() => {
        setIssueReport('выпущено')
        loadIssues(code)
      })
      .catch((e) => setIssueReport(String(e).slice(0, 200)))
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
            <button type="button" className="tab tab--primary" disabled={!author} onClick={issue}
              title={author ? 'зафиксировать слепок текущей генерации' : 'представьтесь в шапке'}>
              Выпустить
            </button>
            <button type="button" className="tab" onClick={() => window.print()}
              title="печатная форма: без оболочки, только документ">
              Печать
            </button>
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
            <div key={s.number} style={{ marginTop: 14 }}>
              <h3 style={{ fontSize: 13.5, marginBottom: 4 }}>
                {s.number}. {s.title}
              </h3>
              {s.items.length === 0 ? (
                <div className="empty" style={{ padding: 8 }}>
                  Раздел пуст. Регламент ожидает: {s.expects}
                </div>
              ) : (
                <SectionTable items={s.items} fieldLabel={fieldLabel} onGo={onGo} />
              )}
            </div>
          ))}
        </>
      )}
    </div>
  )
}
