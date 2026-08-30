// Ф-09: библиотека в контуре ИИ — факты и кандидаты, а не имена. Владелец:
// «библиотека — хранилище старых знаний, а в генерации новых не участвует».
//
// Экран показывает, ЧТО полка знает (нормативы своими пунктами и разобранными
// документами) и что из этого знания следует: кандидаты требований и
// ограничений с основанием. Кандидат — предложение, не истина: в модель он
// уходит только акцептом инженера, и основание видно до акцепта.
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { useSession } from '../ui/session'
import type { KnowledgeExportView, NormativeCandidatesPacket, NormativeReadiness } from '../api/types'

export function LibraryKnowledge() {
  const { author } = useSession()
  const [readiness, setReadiness] = useState<NormativeReadiness | null>(null)
  const [prompt, setPrompt] = useState<string | null>(null)
  const [raw, setRaw] = useState('')
  const [packet, setPacket] = useState<NormativeCandidatesPacket | null>(null)
  const [chosen, setChosen] = useState<Set<number>>(new Set())
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [note, setNote] = useState<string | null>(null)
  const [staleWarning, setStaleWarning] = useState<string | null>(null)
  // Ф-10: выгрузка знаний во внешний контур
  const [exportView, setExportView] = useState<KnowledgeExportView | null>(null)
  const [parts, setParts] = useState<Set<string>>(new Set())

  const reload = () => api.normativeReadiness().then(setReadiness).catch((e) => setError(String(e)))
  useEffect(() => { reload() }, [])

  useEffect(() => {
    api.knowledgeExport()
      .then((v) => { setExportView(v); setParts(new Set(v.parts.filter((p) => p.chosen).map((p) => p.key))) })
      .catch((e) => setError(String(e)))
  }, [])

  /** Состав пакета правится чекбоксами: отпечаток считается по содержимому. */
  const togglePart = (key: string) => {
    const next = new Set(parts)
    if (next.has(key)) next.delete(key); else next.add(key)
    setParts(next)
    api.knowledgeExport([...next]).then(setExportView).catch((e) => setError(String(e)))
  }

  const composePrompt = () => {
    setBusy(true); setError(null)
    api.normativePrompt()
      .then((p) => setPrompt(p.text))
      .catch((e) => setError(String(e)))
      .finally(() => setBusy(false))
  }

  const takePacket = () => {
    if (!raw.trim()) return
    setBusy(true); setError(null); setNote(null)
    api.normativeDraft(raw)
      .then((d) => {
        setPacket(d.packet)
        setChosen(new Set(d.packet.items.map((_, i) => i)))
        setStaleWarning(d.knowledge_warning ?? null)
        setRaw('')
      })
      .catch((e) => setError(String(e)))
      .finally(() => setBusy(false))
  }

  const toggle = (i: number) => {
    const next = new Set(chosen)
    if (next.has(i)) next.delete(i); else next.add(i)
    setChosen(next)
  }

  const accept = () => {
    if (!packet || !author || chosen.size === 0) return
    setBusy(true); setError(null)
    api.normativeAccept(packet, [...chosen], author)
      .then((r) => {
        const parts = [
          r.requirements.length ? `требований: ${r.requirements.length}` : '',
          r.constraints.length ? `ограничений: ${r.constraints.length}` : '',
        ].filter(Boolean)
        setNote(`принято — ${parts.join(', ') || 'ничего'}`)
        setPacket(null); setChosen(new Set())
        reload()
      })
      .catch((e) => setError(String(e)))
      .finally(() => setBusy(false))
  }

  if (!readiness) return null

  return (
    <div className="card">
      <h3>Знание полки: нормативы порождают кандидатов</h3>
      <p className="secondary" style={{ marginTop: 0 }}>{readiness.why}</p>
      {error && <div className="warn" style={{ padding: 6 }}>{error}</div>}
      {note && <div className="secondary">{note}</div>}

      <table className="grid" style={{ marginBottom: 8 }}>
        <thead>
          <tr><th>Норматив</th><th>Пунктов</th><th>Документ</th><th>Что знает</th></tr>
        </thead>
        <tbody>
          {readiness.sources.map((s) => (
            <tr key={s.id}>
              <td className="mono">{s.id}</td>
              <td>{s.clauses || '—'}</td>
              <td className="mono">{s.document ?? '—'}{s.document && !s.parsed ? ' (не разобран)' : ''}</td>
              <td>
                {s.speaks
                  ? <span title="пункты и/или блоки канона идут в промпт">{s.name}</span>
                  : <span className="warn" title="карточка знает только наименование: впишите пункты или приложите документ">
                      только имя — знание в промпт не идёт
                    </span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <h4 style={{ margin: '10px 0 4px' }}>Документы полки: что система из них знает</h4>
      {readiness.documents.length === 0
        ? <p className="secondary" style={{ margin: 0 }}>документов на полке нет — приложите материал в «Полки»</p>
        : (
          <table className="grid" style={{ marginBottom: 8 }}>
            <thead>
              <tr><th>Документ</th><th>Тип</th><th>Разбор</th><th>Урожай</th><th>В промпт</th></tr>
            </thead>
            <tbody>
              {readiness.documents.map((d) => (
                <tr key={d.id}>
                  <td className="mono" title={d.name}>{d.id}</td>
                  <td>{d.kind || '—'}</td>
                  <td>{d.parsed
                    ? 'канон и карта есть'
                    : <span className="warn" title="без разбора документ в промпт не идёт">не разобран</span>}</td>
                  <td>{d.harvested ? 'смысловой разбор есть' : <span className="secondary">не собирался</span>}</td>
                  <td>{d.in_prompt
                    ? `${d.blocks || 0} блоков`
                    : <span className="secondary" title="умолчание по типу документа: справка в промпт не идёт">нет</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

      <div className="toolbar" style={{ padding: '4px 0', gap: 6 }}>
        <button className="rr-assign" disabled={!readiness.can_compose || busy} onClick={composePrompt}
          title={readiness.can_compose
            ? 'собрать промпт по пунктам нормативов и блокам их канонов'
            : 'нечего собирать: нормативы полки знают только свои имена'}>
          Собрать промпт кандидатов
        </button>
      </div>

      {prompt && (
        <textarea readOnly rows={6} value={prompt}
          style={{ width: '100%', fontFamily: 'var(--font-mono)', fontSize: 12 }} />
      )}

      {exportView && (
        <div style={{ margin: '10px 0' }}>
          <h4 style={{ margin: '0 0 4px' }}>Пакет знаний для внешнего контура</h4>
          <p className="secondary" style={{ margin: '0 0 4px' }}>
            Знания уходят файлами один раз, дальше промпты короткие. Отпечаток{' '}
            <span className="mono">{exportView.fingerprint}</span> стоит в шапке каждого файла;
            ответ службы обязан его вернуть — так видно, не устарели ли знания.
          </p>
          <div className="toolbar" style={{ padding: '4px 0', gap: 10, flexWrap: 'wrap' }}>
            {exportView.parts.map((p) => (
              <label key={p.key} className="secondary" title={`${p.file} · ${p.size} байт`}>
                <input type="checkbox" checked={parts.has(p.key)} onChange={() => togglePart(p.key)} />{' '}
                {p.title}{p.size > 0 ? ` (${p.size_kb} КБ)` : ' — пусто'}
              </label>
            ))}
          </div>
          <a className="rr-assign" href={api.knowledgeBundleUrl([...parts])}
            title="архив MD-файлов: инструкция генерируется из реестра видов — двух редакций правил не существует">
            Скачать пакет знаний
          </a>
        </div>
      )}

      <div className="field">
        <label>Пакет кандидатов (ответ службы либо заготовка)</label>
        <textarea rows={3} value={raw} onChange={(e) => setRaw(e.target.value)}
          style={{ width: '100%', fontFamily: 'var(--font-mono)', fontSize: 12 }}
          placeholder='{"kind": "normative_to_candidates", "items": [...]}' />
        <div className="toolbar" style={{ padding: '4px 0', gap: 6 }}>
          <label className="rr-assign" title="выбрать файл пакета">
            Выбрать файл…
            <input type="file" accept="application/json,.json" style={{ display: 'none' }}
              onChange={(e) => {
                const file = e.target.files?.[0]
                e.target.value = ''
                if (file) file.text().then(setRaw).catch((err) => setError(String(err)))
              }} />
          </label>
          <button className="rr-assign" onClick={takePacket} disabled={!raw.trim() || busy}
            title="ворота — нормативная схема кандидатов: кандидат без основания не пройдёт">
            Показать кандидатов
          </button>
        </div>
      </div>

      {staleWarning && <div className="warn" style={{ padding: 6 }}>{staleWarning}</div>}

      {packet && (
        <div style={{ marginTop: 6 }}>
          <p className="secondary" style={{ margin: '0 0 4px' }}>
            Кандидаты — предложение, не истина: требование ляжет объектом с трассой на норматив,
            ограничение — следующим кодом серии Р в паспорт. Основание видно до акцепта.
          </p>
          <table className="grid">
            <thead>
              <tr><th style={{ width: 24 }}></th><th>Что предлагается</th><th>Класс</th><th>Основание</th></tr>
            </thead>
            <tbody>
              {packet.items.map((item, i) => (
                <tr key={i}>
                  <td><input type="checkbox" checked={chosen.has(i)} onChange={() => toggle(i)} /></td>
                  <td>
                    {item.statement}
                    {item.measure && <span className="mono secondary"> · {item.measure.value} {item.measure.unit}</span>}
                    {item.note && <div className="secondary">{item.note}</div>}
                  </td>
                  <td>{item.class === 'requirement' ? 'требование' : `ограничение${item.category ? ` · ${item.category}` : ''}`}</td>
                  <td className="mono secondary">
                    {item.basis.normative_ref}{item.basis.clause ? `, ${item.basis.clause}` : ''}
                    {item.basis.anchors?.length ? ` [${item.basis.anchors.join(', ')}]` : ''}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="toolbar" style={{ padding: '6px 0', gap: 6 }}>
            <button className="tab tab--primary" onClick={accept} disabled={busy || !author || chosen.size === 0}
              title={author ? 'принять выбранных кандидатов в проект' : 'представьтесь в шапке'}>
              {busy ? 'Принимаю…' : `Принять выбранные (${chosen.size})`}
            </button>
            <button className="rr-assign" onClick={() => { setPacket(null); setChosen(new Set()) }}
              title="отказаться от пакета — в модели ничего не изменится">
              отклонить
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
