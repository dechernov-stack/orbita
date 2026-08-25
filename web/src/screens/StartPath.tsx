// Мастер-путь «Начало проекта» — конвейер экранов, эталон
// docs/ui/reference2/reference-project-start.html (круг 1) + бриф
// БРИФ-МАСТЕР-ПУТЬ.md. Три шага со степпером: параметры → библиотека → ИИ.
// Путь, не клетка: «пропустить» всегда на виду, шаг сохраняется в паспорт,
// брошенный путь живёт строкой на жизненном цикле.
import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import { edit } from '../api/edit'
import { useSession } from '../ui/session'

interface Constraint {
  code?: string
  text: string
}

interface PathState {
  status: 'in_progress' | 'done' | 'skipped'
  step: number
  source_ref?: string
  profile_ref?: string
}

interface SourceDocRow {
  id: string
  name: string
  hasText: boolean
}

const CHANGE_REF = 'мастер-путь «Начало проекта»: параметры старта'

/** Отказ — причиной, а не сырым JSON: службе есть что сказать инженеру. */
function reasonOf(e: unknown): string {
  const raw = String((e as Error).message ?? e)
  const brace = raw.indexOf('{')
  if (brace >= 0) {
    try {
      const parsed = JSON.parse(raw.slice(brace)) as { reason?: string; error?: string }
      if (parsed.reason) return parsed.reason
      if (parsed.error) return parsed.error
    } catch { /* не JSON — показываем как есть */ }
  }
  return raw
}

/** Материал запуска — тем же форматом, каким служба берёт документ. */
function statementOf(id: string, version: string, name: string, text: string): string {
  return `Источник: ${id} в. ${version} «${name}»\n\n${text}`
}

export function StartPath({ project, onGo, onDone }: {
  project: string
  onGo: (screen: string) => void
  onDone: () => void
}) {
  const { author } = useSession()
  const [step, setStep] = useState(1)
  const [missionClass, setMissionClass] = useState('')
  const [constraints, setConstraints] = useState<Constraint[]>([])
  const [adding, setAdding] = useState('')
  const [docs, setDocs] = useState<SourceDocRow[] | null>(null)
  const [sourceRef, setSourceRef] = useState('')
  const [profile, setProfile] = useState<{ id: string; version: string; name: string } | null>(null)
  const [promptFull, setPromptFull] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const [failure, setFailure] = useState<string | null>(null)

  useEffect(() => {
    edit.object(project)
      .then((o) => {
        const doc = o.doc as {
          mission_class?: string
          constraints?: Constraint[]
          start_path?: PathState
        }
        setMissionClass(doc.mission_class ?? '')
        setConstraints(doc.constraints ?? [])
        const sp = doc.start_path
        // шаг за пределами 1..3 отсекает схема паспорта — доверяем ей
        if (sp && sp.status === 'in_progress') setStep(sp.step)
        if (sp?.source_ref) setSourceRef(sp.source_ref)
      })
      .catch((e) => setFailure(reasonOf(e)))
    edit.list('source_document')
      .then(async (rows) => {
        const full = await Promise.all(rows.map(async (r) => {
          const doc = (await edit.object(r.id)).doc as { name?: string; text?: string }
          return { id: r.id, name: doc.name ?? r.id, hasText: Boolean(doc.text?.trim()) }
        }))
        setDocs(full)
        setSourceRef((cur) => cur || full.find((d) => d.hasText)?.id || '')
      })
      .catch(() => setDocs([]))
  }, [project])

  /** Шаг сохраняется сам: паспорт правится процедурой с основанием. */
  const save = useCallback(async (path: PathState) => {
    const fresh = await edit.object(project)
    const doc = { ...(fresh.doc as Record<string, unknown>) }
    doc.mission_class = missionClass.trim() || undefined
    if (doc.mission_class === undefined) delete doc.mission_class
    if (constraints.length > 0) doc.constraints = constraints
    else delete doc.constraints
    doc.start_path = { ...path, ...(sourceRef ? { source_ref: sourceRef } : {}) }
    return edit.changeWithRef(project, doc, CHANGE_REF)
  }, [project, missionClass, constraints, sourceRef])

  const skip = () => {
    if (busyRef.current) return
    save({ status: 'skipped', step }).then(onDone).catch((e) => setFailure(reasonOf(e)))
  }

  const toStep = (next: number) => {
    setFailure(null)
    save({ status: 'in_progress', step: next })
      .then(() => setStep(next))
      .catch((e) => setFailure(reasonOf(e)))
  }

  const assembleAndGo = () => {
    if (busyRef.current) return
    busyRef.current = true
    setBusy(true)
    setFailure(null)
    save({ status: 'in_progress', step: 3 })
      .then(() => api.startPathProfile(author))
      .then((p) => { setProfile(p); setStep(3) })
      .catch((e) => setFailure(reasonOf(e)))
      .finally(() => { busyRef.current = false; setBusy(false) })
  }

  const run = async () => {
    if (busyRef.current || !profile) return
    busyRef.current = true
    setBusy(true)
    setFailure(null)
    try {
      const src = docs?.find((d) => d.id === sourceRef)
      let statement = ''
      if (src) {
        const o = await edit.object(src.id)
        const d = o.doc as { name?: string; text?: string }
        statement = statementOf(src.id, o.version, d.name ?? src.id, d.text ?? '')
      }
      await api.aiAsk('mission_to_goals', profile.id, statement, author)
      await api.aiAsk('mission_to_needs', profile.id, statement, author)
      await save({ status: 'done', step: 3, profile_ref: profile.id })
      onDone()
    } catch (e) {
      setFailure(reasonOf(e))
    } finally {
      busyRef.current = false
      setBusy(false)
    }
  }

  const showPrompt = async () => {
    if (!profile) return
    setFailure(null)
    try {
      const src = docs?.find((d) => d.id === sourceRef)
      let statement = ''
      if (src) {
        const o = await edit.object(src.id)
        const d = o.doc as { name?: string; text?: string }
        statement = statementOf(src.id, o.version, d.name ?? src.id, d.text ?? '')
      }
      const r = await api.aiCompose('mission_to_goals', profile.id, statement)
      setPromptFull(r.prompt)
    } catch (e) {
      setFailure(reasonOf(e))
    }
  }

  // Продолжение брошенного пути с Ш3: профиль пересобирается при входе —
  // вызов идемпотентен (сервер обновляет, а не плодит дубли)
  useEffect(() => {
    if (step === 3 && !profile && author && !busyRef.current) {
      api.startPathProfile(author).then(setProfile).catch((e) => setFailure(reasonOf(e)))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [step, profile, author])

  const source = docs?.find((d) => d.id === sourceRef)
  const withText = (docs ?? []).filter((d) => d.hasText)

  return (
    <div className="np-main">
      <div className="sp-work">
        <div className="sp-path">
          <h2>Начало проекта</h2>
          <button className="sp-skip" onClick={skip}>Пропустить — заполню руками</button>
        </div>
        <div className="sp-steps">
          {['Основные параметры', 'Библиотека и материалы', 'Запуск ИИ'].map((t, i) => (
            <div key={t}
              className={`sp-st${step === i + 1 ? ' sp-on' : ''}${step > i + 1 ? ' sp-done' : ''}`}>
              <span className="sp-n">{i + 1}</span>{t}
            </div>
          ))}
        </div>

        {step === 1 && (
          <>
            <div className="np-row">
              <label className="np-label" htmlFor="sp-class">Класс миссии</label>
              <input className="np-name" id="sp-class" value={missionClass}
                onChange={(e) => setMissionClass(e.target.value)} />
            </div>
            <div className="np-row">
              <label className="np-label">Ограничения проекта{' '}
                <span style={{ fontWeight: 400, color: 'var(--status-draft)' }}>
                  — уйдут в запреты службы ИИ и проверки
                </span>
              </label>
              <div className="sp-limits">
                {constraints.map((c, i) => (
                  <div className="sp-lim" key={`${c.code ?? ''}-${i}`}>
                    <span className="sp-no">{c.code ?? ''}</span>
                    <span className="sp-tx">{c.text}</span>
                    <button className="sp-rm"
                      onClick={() => setConstraints(constraints.filter((_, j) => j !== i))}>
                      убрать
                    </button>
                  </div>
                ))}
                <div className="sp-addrow">
                  <input value={adding}
                    placeholder="Добавить ограничение — например: «зоны обслуживания — статическими масками»"
                    onChange={(e) => setAdding(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' && adding.trim()) {
                        setConstraints([...constraints, { text: adding.trim() }])
                        setAdding('')
                      }
                    }} />
                </div>
              </div>
            </div>
            <div className="np-actions">
              <button className="np-btn np-pri" onClick={() => toStep(2)}>Далее — библиотека</button>
              <span className="sp-sp" />
              <span className="np-hint" style={{ margin: 0 }}>
                Шаг сохраняется сам; вернуться можно в любой момент.
              </span>
            </div>
          </>
        )}

        {step === 2 && (
          <>
            <div className="sp-two">
              <div className="sp-pane">
                <div className="sp-ph">Взять из библиотеки
                  {missionClass && <span className="sp-sub">по классу «{missionClass}»</span>}
                </div>
                {/* Библиотеки наборов (типовые требования, шаблоны компонентов)
                    в системе пока нет — крайность брифа §4: путь работает без
                    неё. Комплект документов фазы действует всегда. */}
                <div className="sp-set">
                  <span className="sp-tx">
                    <div className="sp-nm">Библиотека наборов пуста</div>
                    <div className="sp-ds">
                      Путь работает без неё. Комплект документов фазы уже встроен —
                      структуры разделов на экране «Документы».
                    </div>
                  </span>
                  <button className="sp-open" onClick={() => onGo('docs')}>состав</button>
                </div>
              </div>
              <div className="sp-pane">
                <div className="sp-ph">Сложить своё
                  <span className="sp-sub">материал проекта — для ИИ и документов</span>
                </div>
                {docs == null && <div className="sp-set"><span className="sp-ds">Загрузка…</span></div>}
                {(docs ?? []).map((d) => (
                  <div className="sp-file" key={d.id}>
                    {withText.length > 1 && (
                      <input type="radio" name="sp-src" checked={sourceRef === d.id}
                        disabled={!d.hasText} onChange={() => setSourceRef(d.id)} />
                    )}
                    <span>{d.name}</span>
                    <span className="sp-mono">{d.id}</span>
                    {d.hasText
                      ? <span className="sp-ok">приложена ✓</span>
                      : <span className="sp-ds" style={{ marginLeft: 'auto' }}>текста нет</span>}
                  </div>
                ))}
                <div className="sp-set" style={{ borderTop: '1px solid var(--container-3)' }}>
                  <button className="sp-open" onClick={() => onGo('sourcedocs')}>
                    Загрузить документ
                  </button>
                </div>
              </div>
            </div>
            <div className="np-actions">
              <button className="np-btn" onClick={() => toStep(1)}>Назад</button>
              <button className="np-btn np-pri" disabled={busy} onClick={assembleAndGo}>
                Далее — запуск ИИ
              </button>
            </div>
          </>
        )}

        {step === 3 && profile && (
          <>
            <div className="sp-aihead">
              <span className="sp-nm">Профиль службы собран:</span>
              <span className="sp-mono">{profile.id} · {profile.name} · в. {profile.version}</span>
              <button className="np-linkish" onClick={() => onGo('aiprofiles')}>
                настроить подробнее
              </button>
            </div>
            <div className="sp-origin">
              Запреты — <b>из ваших ограничений (Ш1)</b>; материал — <b>из постановки
              и наборов (Ш2)</b>. Промпт собирает служба; руками он не пишется.
            </div>

            <div className="sp-blk sp-profile">
              <div className="sp-src">Из ограничений проекта · Ш1</div>
              <pre>{[
                ...constraints.map((c) => `— ${c.text}${c.code ? ` (${c.code})` : ''}`),
                '— число без ссылки на источник не предлагать вовсе',
              ].join('\n')}</pre>
            </div>
            <div className="sp-blk sp-input">
              <div className="sp-src">Из материалов · Ш2</div>
              <pre>{source
                ? `Постановка: ${source.name} (${source.id})`
                : 'генерация без постановки даст общие места'}</pre>
            </div>
            {promptFull && (
              <div className="sp-blk">
                <div className="sp-src">Промпт целиком</div>
                <pre>{promptFull}</pre>
              </div>
            )}

            {failure && <div className="np-err"><b>Не выполнено:</b> {failure}</div>}
            <div className="np-actions">
              <button className="np-btn" onClick={() => setStep(2)}>Назад</button>
              <button className="np-btn np-pri" disabled={busy} onClick={run}>
                {busy ? 'Генерация…' : 'Запустить генерацию целей и нужд'}
              </button>
              <button className="np-linkish" onClick={showPrompt}>показать промпт целиком</button>
              <span className="sp-sp" />
              <span className="np-hint" style={{ margin: 0 }}>
                Предложения придут на акцепт — в модель ИИ не пишет.
              </span>
            </div>
          </>
        )}

        {step !== 3 && failure && <div className="np-err"><b>Не выполнено:</b> {failure}</div>}
        {step === 3 && !profile && <div className="empty">Сборка профиля…</div>}
      </div>
    </div>
  )
}
