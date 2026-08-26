// Профили службы (О-4) — по эталону docs/ui/reference2/reference-ai-profiles.html
// (круг 1). Подписной элемент — вкладка «Промпт» с атрибуцией источников:
// каждая часть собранного службой промпта подписана, откуда пришла — из
// профиля, из модели проекта или из входа операции. Промпт собирает служба;
// здесь он читается, не правится.
import { useCallback, useEffect, useState } from 'react'
import { api } from '../api/client'
import { edit, type StoredSummary } from '../api/edit'
import { ObjectEditor } from '../ui/ObjectEditor'

interface Block {
  source: string
  title: string
  text: string
}

const SRC_LABEL: Record<string, string> = {
  profile: 'из профиля',
  model: 'из модели',
  input: 'из входа',
}

export function AiProfiles() {
  const [rows, setRows] = useState<StoredSummary[] | null>(null)
  const [kindsOf, setKindsOf] = useState<Record<string, string[]>>({})
  const [selected, setSelected] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [tab, setTab] = useState<'def' | 'prompt'>('def')
  const [pkgKind, setPkgKind] = useState('')
  const [blocks, setBlocks] = useState<Block[] | null>(null)
  const [failure, setFailure] = useState<string | null>(null)

  const reload = useCallback(() => {
    edit.list('ai_profile')
      .then(async (list) => {
        setRows(list)
        const kinds: Record<string, string[]> = {}
        await Promise.all(list.map(async (r) => {
          const doc = (await edit.object(r.id)).doc as { kinds?: string[] }
          kinds[r.id] = doc.kinds ?? []
        }))
        setKindsOf(kinds)
        setSelected((cur) => cur ?? list[0]?.id ?? null)
      })
      .catch((e) => setFailure(String(e)))
  }, [])

  useEffect(reload, [reload])

  // предпросмотр: вид пакета — из разрешённых профилем
  useEffect(() => {
    if (!selected) return
    const allowed = kindsOf[selected] ?? []
    setPkgKind((cur) => (allowed.includes(cur) ? cur : allowed[0] ?? ''))
  }, [selected, kindsOf])

  useEffect(() => {
    setBlocks(null)
    if (tab !== 'prompt' || !selected || !pkgKind) return
    setFailure(null)
    api.aiCompose(pkgKind, selected, '')
      .then((r) => setBlocks((r as { blocks?: Block[] }).blocks ?? []))
      .catch((e) => setFailure(String(e)))
  }, [tab, selected, pkgKind])

  if (rows == null) return <div className="empty">Загрузка…</div>

  return (
    <div className="apf">
      <div className="apf-list">
        <div className="apf-lh">
          <b>Профили службы</b>
          <span className="count">{rows.length}</span>
          <div className="grow" />
          <button className="btn btn--primary"
            onClick={() => { setSelected(null); setCreating(true) }}>
            Создать
          </button>
        </div>
        {rows.map((r) => (
          <button key={r.id} className="apf-row" aria-selected={r.id === selected}
            onClick={() => { setCreating(false); setSelected(r.id); setTab('def') }}>
            <div className="apf-pid mono">{r.id}</div>
            <div className="apf-pnm">{r.title ?? r.id}</div>
            <div className="apf-pds">{(kindsOf[r.id] ?? []).join(' · ')}</div>
          </button>
        ))}
      </div>
      <div className="apf-card">
        {creating || selected ? (
          <>
            {!creating && (
              <div className="tabs" style={{ marginBottom: 10 }}>
                <button className="tab" aria-selected={tab === 'def'} onClick={() => setTab('def')}>
                  Определение
                </button>
                <button className="tab" aria-selected={tab === 'prompt'} onClick={() => setTab('prompt')}>
                  Промпт
                </button>
              </div>
            )}
            {(creating || tab === 'def') && (
              <ObjectEditor kind="ai_profile" schemaName="core/ai-profile"
                id={creating ? null : selected} title="Профили службы" maturity
                onSaved={(id) => { setCreating(false); setSelected(id); reload() }}
                onCancelled={() => { setCreating(false); reload() }} />
            )}
            {!creating && tab === 'prompt' && (
              <div className="apf-prompt">
                <div className="apf-pctl">
                  <span className="np-label" style={{ margin: 0 }}>Вид пакета</span>
                  <select value={pkgKind} onChange={(e) => setPkgKind(e.target.value)}>
                    {(kindsOf[selected ?? ''] ?? []).map((k) => (
                      <option key={k} value={k}>{k}</option>
                    ))}
                  </select>
                  <span className="np-hint" style={{ margin: 0 }}>
                    предпросмотр без входа операции — вход подставится при запуске
                  </span>
                </div>
                <div className="apf-legend">
                  <span><span className="apf-sw apf-sw--profile" />из профиля</span>
                  <span><span className="apf-sw apf-sw--model" />из модели проекта</span>
                  <span><span className="apf-sw apf-sw--input" />из входа операции</span>
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
          </>
        ) : (
          <div className="empty">
            Профилей в проекте нет. Мастер-путь «Начало проекта» соберёт профиль из
            заготовки библиотеки и ограничений проекта — или создайте руками.
          </div>
        )}
      </div>
    </div>
  )
}
