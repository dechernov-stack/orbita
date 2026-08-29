// Д1 (РЕШЕНИЕ-РАЗБОР-ДОКУМЕНТОВ.md): разбор документа — то, что система
// увидела в чужом файле САМА, без службы ИИ: оглавление блоков с якорями,
// числа каноном единиц, термы глоссария, обозначения нормативов кандидатами.
// Текст живёт в MD-каноне (ссылка «канон» ниже) — здесь только координаты.
//
// Сюда же придёт вкладка «Найдено в документе» (Д2, смысловой разбор).
import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { edit, type StoredSummary } from '../api/edit'
import { Num } from '../ui/Num'
import { useSession } from '../ui/session'
import { DocHarvest } from './DocHarvest'
import type { DocumentParseMap } from '../api/types'

const TYPE_LABEL: Record<string, string> = {
  title: 'заголовок',
  section: 'раздел',
  para: 'абзац',
  table: 'таблица',
}

function valueOf(v: number | { min: number; max: number }): string {
  return typeof v === 'number' ? String(v) : `${v.min}…${v.max}`
}

export function DocParse({ documentId }: { documentId?: string }) {
  const { label } = useSession()
  const [docs, setDocs] = useState<StoredSummary[]>([])
  const [id, setId] = useState<string | undefined>(documentId)
  const [map, setMap] = useState<DocumentParseMap | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  /** Д2: смысловой урожай — вкладкой рядом с детерминированным разбором. */
  const [tab, setTab] = useState<'parse' | 'harvest' | 'search'>('parse')
  /** Д3: поиск по материалам — по канонам, с координатой блока. */
  const [query, setQuery] = useState('')
  const [found, setFound] = useState<Awaited<ReturnType<typeof api.documentSearch>> | null>(null)
  /** Д3: блоки, отмеченные «в промпт» — хранятся в паспорте (start_path). */
  const [inPrompt, setInPrompt] = useState<Set<string>>(new Set())
  const [promptNote, setPromptNote] = useState<string | null>(null)

  /** Д3: выбор блоков в промпт живёт в паспорте — читаем при заходе. */
  useEffect(() => {
    if (!id) return
    edit.list('project')
      .then((rows) => rows[0] && edit.object(rows[0].id))
      .then((p) => {
        const blocks = (p?.doc as { start_path?: { source_blocks?: Record<string, string[]> } })
          ?.start_path?.source_blocks?.[id] ?? []
        setInPrompt(new Set(blocks))
      })
      .catch(() => setInPrompt(new Set()))
  }, [id])

  useEffect(() => {
    edit.list('source_document')
      .then((rows) => {
        setDocs(rows)
        if (!documentId && rows.length > 0) setId((cur) => cur ?? rows[0].id)
      })
      .catch((e) => setError(String(e)))
  }, [documentId])

  const load = (target: string) => {
    setError(null)
    setMap(null)
    api.sdParse(target).then(setMap).catch((e) => setError(String(e)))
  }

  useEffect(() => { if (id) load(id) }, [id])

  /** Отметка блока «в промпт»: правит паспорт — состав промпта хранится там. */
  const togglePromptBlock = async (anchor: string) => {
    if (!id) return
    const next = new Set(inPrompt)
    if (next.has(anchor)) next.delete(anchor)
    else next.add(anchor)
    setInPrompt(next)
    setPromptNote(null)
    try {
      const projects = await edit.list('project')
      if (projects.length === 0) return
      const fresh = await edit.object(projects[0].id)
      const doc = { ...(fresh.doc as Record<string, unknown>) }
      const path = (doc.start_path as Record<string, unknown> | undefined) ?? { status: 'in_progress', step: 3 }
      const refs = new Set((path.source_refs as string[] | undefined) ?? [])
      const blocks = { ...((path.source_blocks as Record<string, string[]> | undefined) ?? {}) }
      if (next.size > 0) { refs.add(id); blocks[id] = [...next] } else { delete blocks[id] }
      doc.start_path = {
        ...path,
        ...(refs.size > 0 ? { source_refs: [...refs] } : {}),
        ...(Object.keys(blocks).length > 0 ? { source_blocks: blocks } : {}),
      }
      await edit.changeWithRef(projects[0].id, doc, 'состав промпта: выбор блоков документа (Д3)')
      setPromptNote(`в промпт: ${next.size} блоков документа ${id}`)
    } catch (e) {
      setPromptNote(String(e))
    }
  }

  const runSearch = () => {
    if (query.trim().length < 2) return
    setError(null)
    api.documentSearch(query).then(setFound).catch((e) => setError(String(e)))
  }

  const reparse = () => {
    if (!id) return
    setBusy(true)
    api.sdReparse(id)
      .then(() => load(id))
      .catch((e) => setError(String(e)))
      .finally(() => setBusy(false))
  }

  if (docs.length === 0 && !error) {
    return (
      <div className="empty">
        Исходных документов нет. Файл складывается в «Начало проекта → Библиотека
        и материалы» либо в реестр «Материалы проекта»; разбор считается при
        загрузке.
      </div>
    )
  }

  const s = map?.summary

  return (
    <div style={{ padding: '8px 0', overflowY: 'auto' }}>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 8, flexWrap: 'wrap' }}>
        <span className="secondary">Документ:</span>
        <select value={id ?? ''} onChange={(e) => setId(e.target.value)}
          title="исходные документы проекта — разбор считается при загрузке">
          {docs.map((d) => (
            <option key={d.id} value={d.id}>{d.id}{d.title ? ` — ${d.title}` : ''}</option>
          ))}
        </select>
        {map && (
          <>
            <a className="rr-assign" href={api.sdCanonUrl(map.source_document)} target="_blank" rel="noreferrer"
              title="MD-канон: 100% текста документа с якорями блоков — единственный носитель текста">
              канон .md
            </a>
            <a className="rr-assign" href={api.sdFileUrl(map.source_document)}
              title="исходный файл как он был загружен">файл</a>
            <span className="secondary mono"
              title={`отпечаток разбора: хеш файла + версия разборщика ${map.parser_version}`}>
              {map.fingerprint}
            </span>
          </>
        )}
        <button className="tab" aria-selected={tab === 'parse'} onClick={() => setTab('parse')}
          title="что система увидела сама, без службы: оглавление, величины, нормативы">
          Разбор
        </button>
        <button className="tab" aria-selected={tab === 'harvest'} onClick={() => setTab('harvest')}
          title="урожай смыслового разбора: кандидаты сущностей с координатами и акцепт по адресам">
          Найдено в документе
        </button>
        <button className="tab" aria-selected={tab === 'search'} onClick={() => setTab('search')}
          title="поиск по материалам проекта: находит блок, а не файл — с координатой">
          Поиск по материалам
        </button>
        <div style={{ flex: 1 }} />
        <button className="rr-assign" disabled={busy || !id} onClick={reparse}
          title="пересчитать разбор: нужен документам, загруженным до появления разбора, и после смены версии разборщика">
          {busy ? 'разбираю…' : 'переразобрать'}
        </button>
      </div>

      {error && (
        <div className="warn" style={{ padding: 8 }}>
          Разбора нет или он не удался: {error}. Нажмите «переразобрать».
        </div>
      )}

      {tab === 'harvest' && id && <DocHarvest documentId={id} />}

      {tab === 'search' && (
        <div className="card">
          <h3>Поиск по материалам проекта</h3>
          <div>
            <p className="secondary" style={{ marginTop: 0 }}>
              Ищется по канонам разбора: находка — блок с координатой, а не
              «где-то в файле». Терм глоссария находит блоки, где он употреблён.
            </p>
            <div style={{ display: 'flex', gap: 6 }}>
              <input style={{ flex: 1 }} value={query} placeholder="слово, число или терм глоссария"
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') runSearch() }} />
              <button className="tab tab--primary" onClick={runSearch} disabled={query.trim().length < 2}
                title="искать по всем материалам проекта">
                Искать
              </button>
            </div>
            {found && (
              <div style={{ marginTop: 8 }}>
                <div className="secondary">
                  найдено {found.hits}{found.hits === 0 ? ' — ни одного блока' : ''}
                </div>
                {found.results.map((r, i) => (
                  <div key={`${r.document}-${r.anchor}-${i}`}
                    style={{ padding: '4px 0', borderBottom: '1px solid var(--line, #2223)' }}>
                    <div style={{ display: 'flex', gap: 6, alignItems: 'baseline', flexWrap: 'wrap' }}>
                      <span className="mono secondary">{r.document} · {r.anchor}</span>
                      <b>{r.document_name}</b>
                      {r.section && <span className="secondary">{r.section}</span>}
                      <span className="chip" title="как нашлось: по тексту либо по терму глоссария">{r.by}</span>
                    </div>
                    <div>{r.fragment}</div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}

      {tab === 'parse' && map && !s && (
        <div className="warn" style={{ padding: 8 }}>
          Разбор старой версии — сводки нет. Нажмите «переразобрать».
        </div>
      )}

      {tab === 'parse' && map && s && (
        <>
          <div className="card">
            <h3>Что система увидела сама</h3>
            <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap' }}>
              <span title="абзацы с якорями — координаты для ссылок и промптов">
                блоков <Num v={s.blocks} />
              </span>
              <span title="разделы документа">разделов <Num v={s.sections} /></span>
              <span title="таблицы: адрес строки — ключевой колонкой (t1#15)">
                таблиц <Num v={s.tables} />
              </span>
              <span title="числа с единицами: приведены к канону справочника">
                величин <Num v={s.numbers} />
              </span>
              <span title="термы глоссария, упомянутые в тексте">
                термов <Num v={s.terms} />
              </span>
              <span title="обозначения нормативных актов — кандидаты на связь с нормативом">
                нормативов <Num v={s.normative_candidates} />
              </span>
              <span className="secondary" title="канон несёт весь текст документа: потери нет">
                текста <Num v={s.source_chars} /> знаков → канон <Num v={s.canon_chars} />
              </span>
            </div>
          </div>

          <div className="card">
            <h3>Оглавление</h3>
            <div style={{ overflowX: 'auto' }}>
              <table className="rr-table">
                <thead>
                  <tr>
                    <th title="якорь в каноне: по нему берут кусок документа">Якорь</th>
                    <th>Тип</th>
                    <th>Заголовок</th>
                    <th title="блоки раздела или строки таблицы">Состав</th>
                    <th title="Д3: включение документа в промпт — выбором блоков, а не файлом целиком">
                      В промпт
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {map.structure.map((el) => (
                    <tr key={el.anchor}>
                      <td className="mono">{el.anchor}</td>
                      <td>{TYPE_LABEL[el.type] ?? el.type}</td>
                      <td>{el.title || <span className="secondary">—</span>}</td>
                      <td className="secondary">
                        {el.type === 'table'
                          ? `строк ${el.rows ?? 0} · адрес строки — «${el.row_key}» · ${(el.cols ?? []).join(', ')}`
                          : el.blocks?.length
                            ? `блоки ${el.blocks.join(', ')}`
                            : '—'}
                      </td>
                      <td>
                        <input type="checkbox" checked={inPrompt.has(el.anchor)}
                          aria-label={`блок ${el.anchor} в промпт`}
                          title="взять этот блок в промпт службы"
                          onChange={() => void togglePromptBlock(el.anchor)} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {promptNote && <div className="secondary" style={{ padding: '4px 0' }}>{promptNote}</div>}
          </div>

          {map.numbers.length > 0 && (
            <div className="card">
              <h3>Величины каноном</h3>
              <div style={{ overflowX: 'auto' }}>
                <table className="rr-table">
                  <thead>
                    <tr>
                      <th>Блок</th>
                      <th>Как в тексте</th>
                      <th title="канон справочника единиц — в нём величина и хранится">Канон</th>
                    </tr>
                  </thead>
                  <tbody>
                    {map.numbers.map((n, i) => (
                      <tr key={`${n.block}-${i}`}>
                        <td className="mono">{n.block}</td>
                        <td>
                          {n.converted_from || `${valueOf(n.value)} ${label('unit', n.unit)}`}
                        </td>
                        <td className="mono">
                          {n.canonical
                            ? `${valueOf(n.canonical.value)} ${n.canonical.unit}`
                            : `${valueOf(n.value)} ${n.unit}`}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {map.normative_candidates.length > 0 && (
            <div className="card">
              <h3>Нормативы — кандидаты</h3>
              <div>
                <p className="secondary" style={{ margin: '0 0 6px' }}>
                  обозначения из текста; связью с нормативом становятся акцептом
                </p>
                {map.normative_candidates.map((n, i) => (
                  <div key={`${n.mention}-${i}`}>
                    <span className="mono secondary">{n.block}</span> {n.mention}
                  </div>
                ))}
              </div>
            </div>
          )}

          {map.terms.length > 0 && (
            <div className="card">
              <h3>Термы глоссария в тексте</h3>
              <div>
                {map.terms.map((t) => (
                  <div key={t.term}>
                    {t.term} <span className="secondary mono">{t.blocks.join(', ')}</span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  )
}
