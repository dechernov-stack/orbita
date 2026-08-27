// Профили службы ИИ (О-4, круг 2) — по эталону владельца
// docs/ui/reference2/reference-ai-profiles.html: список с долей акцепта
// (главный показатель здоровья профиля), карточка с вкладками Правила ·
// Промпт · Глоссарий · Журнал, промпт — только чтение с подписанными
// источниками; изменить его можно одним способом — новой версией профиля.
import { useCallback, useEffect, useState } from 'react'
import { api, type AiJournal } from '../api/client'
import { edit, type StoredSummary } from '../api/edit'
import { ObjectEditor } from '../ui/ObjectEditor'
import { Select } from '../ui/Select'

interface Block {
  source: string
  title: string
  text: string
}

interface ProfileDoc {
  name?: string
  purpose?: string
  kinds?: string[]
  statement_rules?: string[]
  prohibitions?: string[]
  glossary?: Array<{ term: string; meaning: string; not_to_confuse?: string }>
  require_source?: boolean
  provenance?: { author?: string }
}

interface ProfileStat {
  calls: number
  proposed: number
  accepted: number
  acceptance_pct?: number
  cost_usd: number
  last_at?: string
  last_model?: string
}

const SRC_LABEL: Record<string, string> = {
  profile: 'профиль',
  model: 'состояние модели',
  input: 'вход операции',
  format: 'формат ответа',
}

export function AiProfiles({ onGo }: { onGo?: (screen: string) => void }) {
  const [rows, setRows] = useState<StoredSummary[] | null>(null)
  const [docs, setDocs] = useState<Record<string, ProfileDoc>>({})
  const [versions, setVersions] = useState<Record<string, string>>({})
  const [stats, setStats] = useState<Record<string, ProfileStat>>({})
  const [journal, setJournal] = useState<AiJournal | null>(null)
  const [selected, setSelected] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState(false)
  const [tab, setTab] = useState<'rules' | 'prompt' | 'glossary' | 'journal'>('rules')
  const [pkgKind, setPkgKind] = useState('')
  const [blocks, setBlocks] = useState<Block[] | null>(null)
  const [failure, setFailure] = useState<string | null>(null)

  const reload = useCallback(() => {
    edit.list('ai_profile')
      .then(async (list) => {
        setRows(list)
        const d: Record<string, ProfileDoc> = {}
        const v: Record<string, string> = {}
        await Promise.all(list.map(async (r) => {
          const o = await edit.object(r.id)
          d[r.id] = o.doc as ProfileDoc
          v[r.id] = o.version
        }))
        setDocs(d)
        setVersions(v)
        setSelected((cur) => cur ?? list[0]?.id ?? null)
      })
      .catch((e) => setFailure(String(e)))
    api.aiJournal()
      .then((j) => {
        setJournal(j)
        setStats(((j as unknown as { by_profile?: Record<string, ProfileStat> }).by_profile) ?? {})
      })
      .catch(() => setJournal(null))
  }, [])

  useEffect(reload, [reload])

  useEffect(() => {
    if (!selected) return
    const allowed = docs[selected]?.kinds ?? []
    setPkgKind((cur) => (allowed.includes(cur) ? cur : allowed[0] ?? ''))
  }, [selected, docs])

  useEffect(() => {
    setBlocks(null)
    if (tab !== 'prompt' || !selected || !pkgKind) return
    setFailure(null)
    api.aiCompose(pkgKind, selected, '')
      .then((r) => setBlocks((r as { blocks?: Block[] }).blocks ?? []))
      .catch((e) => setFailure(String(e)))
  }, [tab, selected, pkgKind])

  if (rows == null) return <div className="empty">Загрузка…</div>

  const doc = selected ? docs[selected] : undefined
  const stat = selected ? stats[selected] : undefined
  const profileCalls = (journal?.calls ?? []).filter((c) => c.profile === selected)

  // Пустое состояние — по эталону: профилей нет
  if (rows.length === 0 && !creating) {
    return (
      <div className="apf-empty">
        <h3>Профилей службы пока нет</h3>
        <p>
          Профиль — это ограничения инженера для ИИ: разрешённые виды, правила
          формулировок, запреты проекта. Служба собирает из него промпт для каждого
          прогона — промптов, написанных руками, в системе нет.
        </p>
        <div className="np-actions" style={{ marginTop: 8 }}>
          <button className="np-btn np-pri" onClick={() => setCreating(true)}>Создать профиль</button>
          <button className="np-btn" onClick={() => onGo?.('importb')}>Загрузить пачкой</button>
        </div>
      </div>
    )
  }

  return (
    <div className="apf">
      <div className="apf-list">
        <div className="apf-lh">
          <b>Профили службы</b>
          <div className="grow" />
          <button className="btn" onClick={() => onGo?.('importb')}>Пачкой</button>
          <button className="btn btn--primary"
            onClick={() => { setEditing(false); setCreating(true) }}>
            Создать
          </button>
        </div>
        {rows.map((r) => {
          const st = stats[r.id]
          return (
            <button key={r.id} className="apf-row" aria-selected={r.id === selected}
              onClick={() => { setCreating(false); setEditing(false); setSelected(r.id) }}>
              <div className="apf-pnm">{r.title ?? r.id}</div>
              <div className="apf-meta">
                <span className="mono">{r.id}</span>
                <span>v{versions[r.id] ?? '1'}</span>
                {st && st.calls > 0 ? (
                  <span className={`apf-acc${(st.acceptance_pct ?? 0) >= 70 ? ' apf-acc--good' : ''}`}>
                    {st.acceptance_pct != null ? `акцепт ${st.acceptance_pct} %` : `вызовов ${st.calls}`}
                  </span>
                ) : (
                  <span className="apf-acc apf-acc--none">вызовов не было</span>
                )}
              </div>
            </button>
          )
        })}
      </div>
      <div className="apf-card">
        {(creating || editing) && (
          <ObjectEditor kind="ai_profile" schemaName="core/ai-profile"
            id={creating ? null : selected} title="Профили службы" maturity
            onSaved={(id) => { setCreating(false); setEditing(false); setSelected(id); reload() }}
            onCancelled={() => { setCreating(false); setEditing(false) }} />
        )}
        {!creating && !editing && selected && doc && (
          <>
            <div className="apf-chead">
              <div className="mono apf-cid">{selected}</div>
              <h3>{doc.name ?? selected}</h3>
              <div className="apf-cver">
                v{versions[selected] ?? '1'}
                {doc.provenance?.author ? ` · ${doc.provenance.author}` : ''}
                {doc.require_source && (
                  <span> · основание обязательно: число без источника не проходит фильтр</span>
                )}
              </div>
            </div>
            <div className="tabs" style={{ margin: '8px 0 10px' }}>
              <button className="tab" aria-selected={tab === 'rules'} onClick={() => setTab('rules')}>Правила</button>
              <button className="tab" aria-selected={tab === 'prompt'} onClick={() => setTab('prompt')}>Промпт</button>
              <button className="tab" aria-selected={tab === 'glossary'} onClick={() => setTab('glossary')}>Глоссарий</button>
              <button className="tab" aria-selected={tab === 'journal'} onClick={() => setTab('journal')}>Журнал</button>
            </div>

            {tab === 'rules' && (
              <div className="apf-body">
                <div className="apf-sec">
                  <div className="apf-k">Разрешённые виды и носители</div>
                  {(doc.kinds ?? []).map((k) => <span key={k} className="chip" style={{ marginRight: 6 }}>{k}</span>)}
                </div>
                {(doc.statement_rules ?? []).length > 0 && (
                  <div className="apf-sec">
                    <div className="apf-k">Правила формулировок</div>
                    {(doc.statement_rules ?? []).map((r, i) => (
                      <div key={i} className="apf-rule"><span className="apf-no mono">П{i + 1}</span><span>{r}</span></div>
                    ))}
                  </div>
                )}
                <div className="apf-sec">
                  <div className="apf-k">Запреты проекта</div>
                  {(doc.prohibitions ?? []).length === 0 && <div className="secondary">— не заданы</div>}
                  {(doc.prohibitions ?? []).map((r, i) => (
                    <div key={i} className="apf-rule"><span className="apf-no mono">—</span><span>{r}</span></div>
                  ))}
                  {doc.require_source && (
                    <div className="apf-rule"><span className="apf-no mono">—</span>
                      <span>Число без ссылки на источник не предлагать вовсе — фильтр его не пропустит.</span></div>
                  )}
                </div>
              </div>
            )}

            {tab === 'prompt' && (
              <div className="apf-body">
                <div className="apf-ro">
                  Только чтение — промпт собирает служба из профиля, состояния модели и
                  входа. Изменить его можно одним способом: новой версией профиля.
                </div>
                <div className="apf-pctl">
                  <span className="np-label" style={{ margin: 0 }}>Вид пакета</span>
                  <Select
                    value={pkgKind}
                    options={(doc.kinds ?? []).map((k) => ({ key: k, title: k }))}
                    onChange={setPkgKind}
                  />
                  <span className="np-hint" style={{ margin: 0 }}>
                    предпросмотр без входа операции — вход подставится при запуске
                  </span>
                </div>
                <div className="apf-legend">Источники:
                  <span><span className="apf-sw apf-sw--profile" />профиль {selected} v{versions[selected] ?? '1'}</span>
                  <span><span className="apf-sw apf-sw--model" />состояние модели</span>
                  <span><span className="apf-sw apf-sw--input" />вход операции</span>
                  <span><span className="apf-sw apf-sw--format" />формат ответа</span>
                </div>
                {failure && <div className="np-err">{failure}</div>}
                {blocks == null && !failure && <div className="empty">Сборка промпта…</div>}
                {(blocks ?? []).map((b, i) => (
                  <div key={i} className={`sp-blk apf-blk--${b.source}`}>
                    <div className="sp-src">{b.title} · {SRC_LABEL[b.source] ?? b.source}</div>
                    <pre>{b.text}</pre>
                  </div>
                ))}
              </div>
            )}

            {tab === 'glossary' && (
              <div className="apf-body">
                {(doc.glossary ?? []).length === 0 && <div className="empty">Глоссарий профиля пуст.</div>}
                {(doc.glossary ?? []).map((g, i) => (
                  <div key={i} className="apf-rule">
                    <span className="apf-no mono">{i + 1}</span>
                    <span><b>{g.term}</b>: {g.meaning}{g.not_to_confuse ? `; не путать с ${g.not_to_confuse}` : ''}</span>
                  </div>
                ))}
              </div>
            )}

            {tab === 'journal' && (
              <div className="apf-body">
                {profileCalls.length === 0 && <div className="empty">Вызовов по профилю не было.</div>}
                {profileCalls.length > 0 && (
                  <table>
                    <thead><tr><th>Когда</th><th>Задание</th><th>Модель</th>
                      <th style={{ textAlign: 'right' }}>Предл.</th>
                      <th style={{ textAlign: 'right' }}>Акцепт</th></tr></thead>
                    <tbody>
                      {profileCalls.map((c) => (
                        <tr key={c.pk}>
                          <td className="mono">{c.at.slice(0, 16).replace('T', ' ')}</td>
                          <td>{c.kind}</td>
                          <td className="mono">{c.model ?? '—'}</td>
                          <td className="num">{c.proposed}</td>
                          <td className="num">{c.accepted}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            )}

            <div className="apf-cfoot">
              <button className="np-btn np-pri" onClick={() => onGo?.('aiservice')}>Запустить прогон</button>
              <button className="np-btn" onClick={() => setEditing(true)}>Новая версия</button>
              <span className="grow" />
              {stat && stat.calls > 0 && (
                <span className="apf-aud">
                  Вызовов: {stat.calls}
                  {stat.cost_usd > 0 ? ` · ${stat.cost_usd.toFixed(2)} $` : ''}
                  {stat.last_at ? ` · последний — ${stat.last_at.slice(0, 10)}` : ''}
                  {stat.last_model ? `, ${stat.last_model}` : ''}
                </span>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  )
}
