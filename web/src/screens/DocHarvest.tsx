// Д2 (ADR-032): вкладка «Найдено в документе» — урожай смыслового разбора.
// Кандидаты по классам с координатами блоков; акцепт раскладывает их по
// адресам системы. Недостающее обязательное поле спрашивается у инженера
// (роль стейкхолдера, реквизиты норматива) — служба его не выдумывает.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { Num } from '../ui/Num'
import { useSession } from '../ui/session'
import type { DocumentHarvestView } from '../api/types'
import { SortTh, useSort } from '../ui/sort'

type Item = DocumentHarvestView['items'][number]

const CLASS_LABEL: Record<string, string> = {
  stakeholder: 'стейкхолдеры',
  normative_ref: 'нормативы',
  service: 'сервисы',
  goal: 'цели',
  need: 'нужды',
  milestone: 'этапы',
  budget: 'суммы',
  geography: 'география',
  constraint: 'ограничения',
  evaluation_criterion: 'критерии оценки',
  need_ref_flags: 'из них «уточнить обозначение»',
}

function textOf(item: Item): string {
  return item.statement || item.name || ''
}

export function DocHarvest({ documentId }: { documentId: string }) {
  const { author } = useSession()
  const [view, setView] = useState<DocumentHarvestView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [raw, setRaw] = useState('')
  const [picked, setPicked] = useState<Set<number>>(new Set())
  const [filled, setFilled] = useState<Record<number, Record<string, string>>>({})
  const [report, setReport] = useState<Awaited<ReturnType<typeof api.sdHarvestAccept>> | null>(null)
  const [prompt, setPrompt] = useState<string | null>(null)

  const load = () => {
    setError(null)
    api.sdHarvest(documentId).then(setView).catch(() => setView(null))
  }

  useEffect(() => {
    setView(null)
    setPicked(new Set())
    setFilled({})
    setReport(null)
    setPrompt(null)
    load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [documentId])

  const putPacket = () => {
    if (!raw.trim()) return
    setBusy(true)
    setError(null)
    api.sdHarvestPut(documentId, raw)
      .then(() => { setRaw(''); load() })
      .catch((e) => setError(String(e)))
      .finally(() => setBusy(false))
  }

  const composePrompt = () => {
    setBusy(true)
    setError(null)
    api.sdHarvestPrompt(documentId)
      .then((p) => setPrompt(p.text))
      .catch((e) => setError(String(e)))
      .finally(() => setBusy(false))
  }

  const accept = () => {
    if (picked.size === 0 || !author) return
    setBusy(true)
    setError(null)
    api.sdHarvestAccept(
      documentId,
      [...picked].map((index) => ({ index, filled: filled[index] })),
      author,
    )
      .then((r) => { setReport(r); setPicked(new Set()); load() })
      .catch((e) => setError(String(e)))
      .finally(() => setBusy(false))
  }

  const setField = (index: number, field: string, value: string) =>
    setFilled((prev) => ({ ...prev, [index]: { ...prev[index], [field]: value } }))

  // Сортировка заголовком (§2.4): кандидаты урожая — по классу и координате.
  // Исходный индекс несём с собой: акцепт идёт по позициям пакета, и порядок
  // показа не имеет права его сдвинуть.
  const пронумерованные = (view?.items ?? []).map((item, index) => ({ item, index }))
  const { sorted, sort, toggle } = useSort(пронумерованные, {
    cls: (r) => String(r.item.class ?? ''),
    what: (r) => String(r.item.statement ?? r.item.name ?? ''),
    block: (r) => String(r.item.block ?? ''),
  })

  if (!view) {
  return (
      <div style={{ padding: '8px 0' }}>
        <div className="card">
          <h3>Смысловой разбор — урожай</h3>
          <div>
            <p className="secondary" style={{ marginTop: 0 }}>
              Один вызов службы на документ, по всем классам сразу, поверх выжимки
              разбора: кандидаты приходят с координатами блоков и раскладываются
              по адресам системы акцептом. Промпт собирает система — правила
              разбора, карточка документа и выжимка блоками.
            </p>
            {error && <div className="warn" style={{ padding: 8 }}>{error}</div>}
            <div className="toolbar" style={{ padding: '6px 0', gap: 6 }}>
              <button className="rr-assign" onClick={composePrompt} disabled={busy}
                title="собрать промпт разбора — для закрытого контура: скопировать в модель, ответ внести пакетом">
                Собрать промпт
              </button>
            </div>
            {prompt && (
              <textarea rows={10} readOnly value={prompt}
                style={{ width: '100%', fontFamily: 'var(--font-mono)', fontSize: 12 }} />
            )}
            <div className="field">
              <label>Урожай пакетом (ответ модели либо заготовленный разбор)</label>
              <textarea rows={4} value={raw} onChange={(e) => setRaw(e.target.value)}
                style={{ width: '100%', fontFamily: 'var(--font-mono)', fontSize: 12 }}
                placeholder='{"kind": "document_semantic_parse", "source_document": "…", "items": […]}' />
            </div>
            <div className="toolbar" style={{ padding: '6px 0', gap: 6 }}>
              <label className="rr-assign" title="выбрать файл пакета, не вставлять текст руками">
                Выбрать файл…
                <input type="file" accept="application/json,.json" style={{ display: 'none' }}
                  onChange={(e) => {
                    const file = e.target.files?.[0]
                    e.target.value = ''
                    if (file) file.text().then(setRaw).catch((err) => setError(String(err)))
                  }} />
              </label>
              <button className="tab tab--primary" onClick={putPacket} disabled={!raw.trim() || busy}
                title="ворота — нормативная схема разбора: чужая форма внутрь не пройдёт">
                {busy ? 'Внесение…' : 'Внести урожай'}
              </button>
            </div>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div style={{ padding: '8px 0' }}>
      <div className="card">
        <h3>Найдено в документе</h3>
        <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap' }}>
          {Object.entries(view.summary).map(([cls, n]) => (
            <span key={cls} title={view.targets[cls]?.where ?? 'класс вне раскладки — адрес назначает инженер'}>
              {CLASS_LABEL[cls] ?? cls} <Num v={n} />
            </span>
          ))}
          {view.parser && <span className="secondary">разобрал: {view.parser}</span>}
        </div>
        {view.schema_note && <div className="secondary" style={{ marginTop: 4 }}>{view.schema_note}</div>}
      </div>

      {report && (
        <div className="card">
          <h3>Итог акцепта</h3>
          <div>
            {report.created.map((c) => (
              <div key={`${c.index}-${c.id}`}>
                ✓ <span className="mono">{c.id}</span> — {CLASS_LABEL[c.class] ?? c.class}, {c.where}
              </div>
            ))}
            {report.refused.map((r) => (
              <div key={`r-${r.index}`} className="amber">△ кандидат {r.index + 1}: {r.why}</div>
            ))}
          </div>
        </div>
      )}

      {error && <div className="warn" style={{ padding: 8 }}>{error}</div>}

      <div className="card">
        <h3>Кандидаты · {view.items.length}</h3>
        <div style={{ overflowX: 'auto' }}>
          <table className="rr-table">
            <thead>
              <tr>
                <th title="выбрать кандидата для акцепта">✓</th>
                <SortTh label="Класс" sortKey="cls" sort={sort} onToggle={toggle} />
                <SortTh label="Что нашлось" sortKey="what" sort={sort} onToggle={toggle} />
                <SortTh label="Блок" sortKey="block" sort={sort} onToggle={toggle} />
                <th title="куда ляжет при акцепте и чего системе не хватает">Адрес и дозаполнение</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map(({ item, index }) => {
                const target = view.targets[item.class]
                const value = item.display ?? ''
                return (
                  <tr key={index}>
                    <td>
                      <input type="checkbox" checked={picked.has(index)}
                        aria-label={`кандидат ${index + 1}`}
                        disabled={!target?.type}
                        title={target?.type ? 'принять кандидата' : 'класс кладётся не объектом — см. адрес'}
                        onChange={(e) => setPicked((prev) => {
                          const next = new Set(prev)
                          if (e.target.checked) next.add(index)
                          else next.delete(index)
                          return next
                        })} />
                    </td>
                    <td>
                      {CLASS_LABEL[item.class] ?? item.class}
                      {item.source_mark && (
                        <div className="chip" title={
                          item.source_mark === 'П'
                            ? 'предлагаемая цель или инженерно-финансовое допущение, требующее подтверждения'
                            : item.source_mark === 'И'
                              ? 'внутренний документ — наш материал'
                              : 'внешний источник, проверенный на указанную дату'
                        }>
                          [{item.source_mark}]
                        </div>
                      )}
                      {item.need_ref && (
                        <div className="amber" title="реквизиты документ не назвал — их вносит инженер">
                          уточнить обозначение
                        </div>
                      )}
                      {item.establishes && (
                        <div className="secondary" title="документ учреждает эту сущность — её ещё нет">
                          учреждается
                        </div>
                      )}
                    </td>
                    <td style={{ minWidth: 220, whiteSpace: 'normal' }}>
                      {textOf(item)}
                      {value && <div className="secondary mono">{value}</div>}
                      {item.horizon && <div className="secondary">горизонт {item.horizon}</div>}
                      {item.scores && (
                        <div className="secondary mono">
                          {Object.entries(item.scores).map(([k, v]) => `${k} ${v}`).join(' · ')}
                        </div>
                      )}
                    </td>
                    <td className="mono secondary">{item.blocks_label}</td>
                    <td style={{ minWidth: 300, whiteSpace: 'normal' }}>
                      <div className="secondary">{target?.where ?? 'адрес назначает инженер'}</div>
                      {target?.note && <div className="secondary">{target.note}</div>}
                      {(target?.gaps ?? []).map((gap) => (
                        <div key={gap.field} style={{ display: 'flex', gap: 4, alignItems: 'center', marginTop: 2 }}>
                          <span className="secondary" style={{ minWidth: 120 }}>{gap.prompt}:</span>
                          {gap.options.length > 0 ? (
                            <select value={filled[index]?.[gap.field] ?? ''}
                              aria-label={`${gap.prompt} кандидата ${index + 1}`}
                              onChange={(e) => setField(index, gap.field, e.target.value)}>
                              <option value="">—</option>
                              {gap.options.map((o) => <option key={o} value={o}>{o}</option>)}
                            </select>
                          ) : (
                            <input value={filled[index]?.[gap.field] ?? ''}
                              aria-label={`${gap.prompt} кандидата ${index + 1}`}
                              onChange={(e) => setField(index, gap.field, e.target.value)} />
                          )}
                        </div>
                      ))}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
        <div className="toolbar" style={{ padding: '8px 0', gap: 6 }}>
          <button className="tab tab--primary" onClick={accept} disabled={picked.size === 0 || busy || !author}
            title={author ? 'принять выбранных кандидатов по их адресам' : 'представьтесь в шапке'}>
            {busy ? 'Акцепт…' : `Принять выбранное · ${picked.size}`}
          </button>
          <span className="secondary">
            запись транзакцией: отказ любого выбранного отменяет весь акцепт
          </span>
        </div>
      </div>

      {(view.derived ?? []).length > 0 && (
        <div className="card">
          <h3>Вычисленное разборщиком</h3>
          <div>
            <p className="secondary" style={{ margin: '0 0 6px' }}>
              производные (топы, суммы, сортировки) — отдельно от сказанного автором:
              в модель они не идут
            </p>
            {(view.derived ?? []).map((d) => (
              <div key={d.kind}>
                <b>{d.kind}</b> — {d.note}
                {d.rows && <span className="secondary"> · строк {d.rows.length}</span>}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
