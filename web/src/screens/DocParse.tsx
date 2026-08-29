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
  const [tab, setTab] = useState<'parse' | 'harvest'>('parse')

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
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
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
