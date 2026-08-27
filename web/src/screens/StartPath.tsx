// Мастер-путь «Начало проекта» — конвейер экранов, эталон
// docs/ui/reference2/reference-project-start.html (круг 1) + бриф
// БРИФ-МАСТЕР-ПУТЬ.md. Три шага со степпером: параметры → библиотека → ИИ.
// Путь, не клетка: «пропустить» всегда на виду, шаг сохраняется в паспорт,
// брошенный путь живёт строкой на жизненном цикле.
import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from '../api/client'
import { edit } from '../api/edit'
import { countPhrase } from '../ui/countPhrase'
import { Select } from '../ui/Select'
import { useSession } from '../ui/session'

interface Constraint {
  code?: string
  text: string
}

interface PathState {
  status: 'in_progress' | 'done' | 'skipped'
  step: number
  /** Прежний одиночный выбор — читается для совместимости старых паспортов. */
  source_ref?: string
  /** Круг 3 §3: участие документов в промпте — множественное. */
  source_refs?: string[]
  /** Круг 3 §1: создано взятиями — числа для итога пути и свёрнутой строки. */
  created_counts?: Record<string, number>
  profile_ref?: string
}


interface SourceDocRow {
  id: string
  name: string
  hasText: boolean
  kind?: string
  org?: string
  docDate?: string
  summary?: string
  fileName?: string
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

/** Тип по префиксу id — для счётчиков создания (подписи TYPE_PLURALS). */
function typeOfId(id: string): string {
  const prefix = id.split('-')[0]
  const map: Record<string, string> = {
    RQ: 'requirement', DT: 'document_template', CM: 'component', CU: 'component_usage',
    IF: 'interface', TR: 'typical_risk', SH: 'stakeholder_profile', NR: 'normative_document',
    WB: 'wbs_element', CE: 'cost_estimate',
  }
  return map[prefix] ?? prefix
}

/** «Создано этим шагом: 34 требования · 13 шаблонов документов». */
function sumCounts(applied: Record<string, { by_type: Record<string, number> }>): Record<string, number> {
  const total: Record<string, number> = {}
  Object.values(applied).forEach((a) => {
    Object.entries(a.by_type).forEach(([t, n]) => { total[t] = (total[t] ?? 0) + n })
  })
  return total
}

export function StartPath({ project, onGo, onDone }: {
  project: string
  onGo: (screen: string) => void
  onDone: () => void
}) {
  const { author, label } = useSession()
  const [step, setStep] = useState(1)
  const [missionClass, setMissionClass] = useState('')
  const [constraints, setConstraints] = useState<Constraint[]>([])
  const [adding, setAdding] = useState('')
  const [docs, setDocs] = useState<SourceDocRow[] | null>(null)
  /** Библиотека: исходные документы других проектов (ADR-030). */
  const [library, setLibrary] = useState<Array<{
    id: string; project: string; name: string; kind: string; summary: string; has_text: boolean
  }> | null>(null)
  const [picked, setPicked] = useState<Set<string>>(new Set())
  const [takeNote, setTakeNote] = useState<string | null>(null)
  /** Полка Б4: классы миссии; выбор подставляет типовые ограничения. */
  const [classes, setClasses] = useState<Array<{
    id: string; name: string; typical_constraints: Array<{ code?: string; text: string }>
  }> | null>(null)
  /** Фрагменты полок (Б1/Б5/Б6/Б7/Г1) с живыми счётчиками. */
  const [shelves, setShelves] = useState<Array<{
    id: string; name: string; shelf: string; mission_class_ref: string; summary: string
    counters: Record<string, number>
    origin: { project?: string; author?: string; date?: string }
  }> | null>(null)
  const [openManifest, setOpenManifest] = useState<string | null>(null)
  /** Круг 2: загрузка файла с карточкой и раскрытие карточки у файла. */
  const [upKind, setUpKind] = useState('mission_note')
  const [upName, setUpName] = useState('')
  const [upOrg, setUpOrg] = useState('')
  const [upFile, setUpFile] = useState<File | null>(null)
  const [openCard, setOpenCard] = useState<string | null>(null)
  const [parseNote, setParseNote] = useState<string | null>(null)
  /** Круг 3 §3: участие в промпте множественное — чекбоксы, не radio. */
  const [promptDocs, setPromptDocs] = useState<Set<string>>(new Set())
  const promptSeeded = useRef(false)
  /** Круг 3 §1: взятые фрагменты — из связей «применяет» и локальных взятий. */
  const [applied, setApplied] = useState<Record<string, { count: number; by_type: Record<string, number> }>>({})
  const [busyFrag, setBusyFrag] = useState<string | null>(null)
  const [dragOver, setDragOver] = useState(false)
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
        if (sp?.source_refs?.length || sp?.source_ref) {
          promptSeeded.current = true
          setPromptDocs(new Set(sp.source_refs ?? [sp.source_ref!]))
        }
      })
      .catch((e) => setFailure(reasonOf(e)))
    reloadOwn()
    api.libraryDocs().then(setLibrary).catch(() => setLibrary([]))
    api.missionClasses().then(setClasses).catch(() => setClasses([]))
    api.libraryShelves()
      .then((rows) => {
        setShelves(rows)
        const taken: Record<string, { count: number; by_type: Record<string, number> }> = {}
        rows.forEach((f) => { if (f.applied) taken[f.id] = f.applied })
        setApplied(taken)
      })
      .catch(() => setShelves([]))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [project])

  /** Документы проекта («своё»): перечитываются и после взятия из библиотеки. */
  const reloadOwn = useCallback(() => {
    edit.list('source_document')
      .then(async (rows) => {
        const full = await Promise.all(rows.map(async (r) => {
          const doc = (await edit.object(r.id)).doc as {
            name?: string; text?: string; kind?: string; org?: string
            doc_date?: string; summary?: string; file?: { name?: string }
          }
          return {
            id: r.id, name: doc.name ?? r.id, hasText: Boolean(doc.text?.trim()),
            kind: doc.kind, org: doc.org, docDate: doc.doc_date,
            summary: doc.summary, fileName: doc.file?.name,
          }
        }))
        setDocs(full)
        // по умолчанию отмечены все с заполненной карточкой; снятие — осознанное
        if (!promptSeeded.current) {
          promptSeeded.current = true
          setPromptDocs(new Set(full.filter((d) => d.hasText).map((d) => d.id)))
        }
      })
      .catch(() => setDocs([]))
  }, [])

  /** Взятие выбранного: копии в проект с провенансом imported — на сервере. */
  const take = () => {
    if (busyRef.current || picked.size === 0) return
    busyRef.current = true
    setBusy(true)
    setFailure(null)
    api.libraryTake([...picked], author)
      .then((r) => {
        setTakeNote(`взято: ${r.taken.length} — теперь материал проекта`)
        setPicked(new Set())
        reloadOwn()
      })
      .catch((e) => setFailure(reasonOf(e)))
      .finally(() => { busyRef.current = false; setBusy(false) })
  }

  /** Круг 3 §1: «взять» — немедленное действие с видимым результатом.
   * Идемпотентность — на сервере (по связи «применяет»). */
  const applyFragment = (id: string) => {
    if (busyFrag) return
    setBusyFrag(id)
    setFailure(null)
    api.libraryApply(id, author)
      .then((r) => {
        const byType: Record<string, number> = {}
        r.created.forEach((c) => {
          const t = typeOfId(c.id)
          byType[t] = (byType[t] ?? 0) + 1
        })
        setApplied((prev) => ({
          ...prev,
          [id]: r.created.length > 0
            ? { count: r.created.length, by_type: byType }
            : prev[id] ?? { count: r.existing.length, by_type: {} },
        }))
      })
      .catch((e) => setFailure(reasonOf(e)))
      .finally(() => setBusyFrag(null))
  }

  /** Отмена взятия: удаляет созданное именно этим взятием; тронутое — отказ. */
  const revertFragment = (id: string) => {
    if (busyFrag) return
    setBusyFrag(id)
    setFailure(null)
    api.libraryRevert(id, author)
      .then(() => setApplied((prev) => {
        const next = { ...prev }
        delete next[id]
        return next
      }))
      .catch((e) => setFailure(reasonOf(e)))
      .finally(() => setBusyFrag(null))
  }

  /** Круг 2: файл + карточка одним приёмом; текст извлекает сервер. */
  const upload = () => {
    if (busyRef.current || !upFile || !upName.trim()) return
    busyRef.current = true
    setBusy(true)
    setFailure(null)
    api.sdUpload(upFile, { name: upName.trim(), kind: upKind, org: upOrg.trim() || undefined as unknown as string, author })
      .then((r) => {
        setTakeNote(r.text_extracted
          ? `загружено: ${r.id} — текст извлечён, карточка заполнена`
          : `загружено: ${r.id} — формат не читается, заполните текст в карточке`)
        setUpFile(null); setUpName('')
        reloadOwn()
      })
      .catch((e) => setFailure(reasonOf(e)))
      .finally(() => { busyRef.current = false; setBusy(false) })
  }

  /** Разбор карточки службой: результат придёт на акцепт (область LIB). */
  const parse = (d: SourceDocRow, kind: string, label: string) => {
    if (busyRef.current) return
    busyRef.current = true
    setBusy(true)
    setParseNote(null)
    Promise.all([edit.object(d.id), edit.list('ai_profile')])
      .then(async ([o, profiles]) => {
        const doc = o.doc as { name?: string; text?: string }
        const statement = statementOf(d.id, o.version, doc.name ?? d.id, doc.text ?? '')
        // профиль — тот, что разрешает вид разбора; промпт собирает служба
        for (const pr of profiles) {
          const pd = (await edit.object(pr.id)).doc as { kinds?: string[] }
          if ((pd.kinds ?? []).includes(kind)) {
            return api.aiAsk(kind, pr.id, statement, author)
          }
        }
        throw new Error(`нет профиля службы, разрешающего вид «${kind}» — добавьте вид в профиль`)
      })
      .then((r) => setParseNote(`${label}: предложений ${(r as { proposed?: number }).proposed ?? '—'} — результат придёт на акцепт`))
      .catch((e) => setParseNote(reasonOf(e)))
      .finally(() => { busyRef.current = false; setBusy(false) })
  }

  /** Шаг сохраняется сам: паспорт правится процедурой с основанием. */
  const save = useCallback(async (path: PathState) => {
    const fresh = await edit.object(project)
    const doc = { ...(fresh.doc as Record<string, unknown>) }
    doc.mission_class = missionClass.trim() || undefined
    if (doc.mission_class === undefined) delete doc.mission_class
    if (constraints.length > 0) doc.constraints = constraints
    else delete doc.constraints
    doc.start_path = {
      ...path,
      ...(promptDocs.size > 0 ? { source_refs: [...promptDocs] } : {}),
      ...(Object.keys(applied).length > 0 ? { created_counts: sumCounts(applied) } : {}),
    }
    return edit.changeWithRef(project, doc, CHANGE_REF)
  }, [project, missionClass, constraints, promptDocs, applied])

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

  /** Круг 3 §3: материал промпта — ВСЕ отмеченные документы, поимённо. */
  const materialStatement = async (): Promise<string> => {
    const chosen = (docs ?? []).filter((d) => promptDocs.has(d.id) && d.hasText)
    const excluded = (docs ?? []).filter((d) => d.hasText && !promptDocs.has(d.id))
    const parts = await Promise.all(chosen.map(async (src) => {
      const o = await edit.object(src.id)
      const d = o.doc as { name?: string; text?: string }
      return statementOf(src.id, o.version, d.name ?? src.id, d.text ?? '')
    }))
    if (parts.length === 0) return ''
    const head = `Документы в промпте — ${chosen.length} из ${chosen.length + excluded.length}` +
      (excluded.length > 0 ? ` (${excluded.map((d) => `«${d.name}»`).join(', ')} исключен${excluded.length === 1 ? 'а' : 'ы'} вами)` : '') + ':'
    return [head, ...parts].join('\n\n')
  }

  const run = async () => {
    if (busyRef.current || !profile) return
    busyRef.current = true
    setBusy(true)
    setFailure(null)
    try {
      const statement = await materialStatement()
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
      const statement = await materialStatement()
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

  const withText = (docs ?? []).filter((d) => d.hasText)
  const chosenDocs = withText.filter((d) => promptDocs.has(d.id))
  const excludedDocs = withText.filter((d) => !promptDocs.has(d.id))
  const stepCounts = sumCounts(applied)

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
              {(classes ?? []).length > 0 ? (
                <>
                  <Select
                    value={missionClass}
                    placeholder="—"
                    width={320}
                    options={[{ key: '', title: '—' },
                      ...(classes ?? []).map((c) => ({ key: c.id, title: c.name }))]}
                    onChange={(v) => {
                      const cls = classes?.find((c) => c.id === v)
                      setMissionClass(v)
                      // выбор класса подставляет типовые ограничения — правятся
                      if (cls && constraints.length === 0 && cls.typical_constraints.length > 0) {
                        setConstraints(cls.typical_constraints)
                      }
                    }}
                  />
                  <div className="np-hint">Класс определяет наборы библиотеки на следующем шаге.</div>
                </>
              ) : (
                <input className="np-name" id="sp-class" value={missionClass}
                  onChange={(e) => setMissionClass(e.target.value)} />
              )}
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
                  <span className="sp-sub">исходные документы других проектов</span>
                </div>
                {/* Полки (§4 Ш2): фрагменты по классу миссии с живыми
                    счётчиками; «состав» раскрывает манифест; «взять» создаёт
                    экземпляры со связью «применяет». Ниже — документы других
                    проектов (канал сбора из живой работы). */}
                {(shelves ?? [])
                  .filter((f) => !missionClass || !f.mission_class_ref || f.mission_class_ref === missionClass)
                  .map((f) => (
                    <div className="sp-set" key={f.id}>
                      <span className="sp-tx">
                        <div className="sp-nm">
                          {f.name}
                          {' · '}
                          {Object.values(f.counters).reduce((a, b) => a + b, 0)}
                        </div>
                        <div className="sp-ds">{f.summary || f.id}</div>
                        {openManifest === f.id && (
                          <div className="sp-ds">
                            {Object.entries(f.counters).map(([t, n]) => `${t}: ${n}`).join(' · ')}
                            {f.origin.project ? ` — из ${f.origin.project}` : ''}
                            {f.origin.date ? `, ${f.origin.date}` : ''}
                          </div>
                        )}
                      </span>
                      {applied[f.id] && (
                        <span className="sp-took">Взято ✓ создано <b>{applied[f.id].count}</b></span>
                      )}
                      <button className="sp-open"
                        onClick={() => setOpenManifest(openManifest === f.id ? null : f.id)}>
                        состав
                      </button>
                      {applied[f.id]
                        ? (
                          <button className="sp-undo" disabled={busyFrag !== null}
                            title="удаляет созданное именно этим взятием; тронутое руками — отказ"
                            onClick={() => revertFragment(f.id)}>
                            {busyFrag === f.id ? 'отменяю…' : 'отменить'}
                          </button>
                        )
                        : (
                          <button className="sp-take" disabled={busyFrag !== null}
                            onClick={() => applyFragment(f.id)}>
                            {busyFrag === f.id ? 'беру…' : 'взять'}
                          </button>
                        )}
                    </div>
                  ))}
                {Object.keys(stepCounts).length > 0 && (
                  <div className="sp-stepsum">
                    Создано этим шагом:{' '}
                    <span>
                      {Object.entries(stepCounts).map(([t, n], i) => (
                        <span key={t}>{i > 0 && ' · '}<b>{n}</b> {countPhrase(t, n).replace(/^\d+ /, '')}</span>
                      ))}
                    </span>
                    <span className="secondary" style={{ marginLeft: 'auto' }}>
                      — со связями «применяет»; отмена удаляет созданное взятием
                    </span>
                  </div>
                )}
                {library == null && shelves == null && <div className="sp-set"><span className="sp-ds">Загрузка…</span></div>}
                {library != null && library.length === 0 && (shelves ?? []).length === 0 && (
                  <div className="sp-set">
                    <span className="sp-tx">
                      <div className="sp-nm">В библиотеке пока нет документов</div>
                      <div className="sp-ds">
                        Путь работает без неё: сложите своё справа — в следующем
                        проекте это уже будет библиотекой.
                      </div>
                    </span>
                  </div>
                )}
                {(library ?? []).map((d) => (
                  <div className="sp-set" key={d.id}>
                    <input type="checkbox" checked={picked.has(d.id)}
                      onChange={(e) => {
                        const next = new Set(picked)
                        if (e.target.checked) next.add(d.id)
                        else next.delete(d.id)
                        setPicked(next)
                      }} />
                    <span className="sp-tx">
                      <div className="sp-nm">{d.name}</div>
                      <div className="sp-ds">{d.summary || d.kind}{d.project ? ` · из ${d.project}` : ''}</div>
                    </span>
                  </div>
                ))}
                {library != null && library.length > 0 && (
                  <div className="sp-set">
                    <button className="sp-open" disabled={picked.size === 0 || busy} onClick={take}>
                      Взять выбранное{picked.size > 0 ? ` · ${picked.size}` : ''}
                    </button>
                    {takeNote && <span className="sp-ds">{takeNote}</span>}
                  </div>
                )}
              </div>
              <div className="sp-pane">
                <div className="sp-ph">Сложить своё
                  <span className="sp-sub">материал проекта — для ИИ и документов</span>
                </div>
                {docs == null && <div className="sp-set"><span className="sp-ds">Загрузка…</span></div>}
                {(docs ?? []).length > 0 && (
                  <div className="sp-ds" style={{ padding: '2px 0 4px' }}>
                    отмеченные уходят в промпт службы на Ш3 — участие множественное
                  </div>
                )}
                {(docs ?? []).map((d) => (
                  <div key={d.id}>
                    <div className="sp-file" style={{ cursor: 'pointer' }}
                      onClick={() => setOpenCard(openCard === d.id ? null : d.id)}>
                      <input type="checkbox" checked={promptDocs.has(d.id)}
                        disabled={!d.hasText}
                        title={d.hasText ? 'участвует в промпте службы' : 'текста нет — в промпт не попадёт'}
                        onChange={() => setPromptDocs((prev) => {
                          const next = new Set(prev)
                          if (next.has(d.id)) next.delete(d.id)
                          else next.add(d.id)
                          return next
                        })}
                        onClick={(e) => e.stopPropagation()} />
                      <span>{d.name}</span>
                      {d.fileName
                        ? <a className="sp-mono" href={api.sdFileUrl(d.id)}
                            onClick={(e) => e.stopPropagation()}>{d.fileName}</a>
                        : <span className="sp-mono">{d.id}</span>}
                      {d.hasText
                        ? <span className="sp-ok">карточка заполнена ✓</span>
                        : <span className="sp-ds" style={{ marginLeft: 'auto' }}>текста нет</span>}
                    </div>
                    {openCard === d.id && (
                      <div className="sp-card">
                        <div className="sp-card__meta">
                          <span>Тип: <b>{label('sd_kind', d.kind)}</b></span>
                          {d.org && <span>Источник: <b>{d.org}</b></span>}
                          {d.docDate && <span>Дата: <b>{d.docDate}</b></span>}
                        </div>
                        {d.summary && <div className="sp-card__sum">{d.summary}</div>}
                        <div className="sp-card__acts">
                          <button className="np-btn" disabled={busy}
                            onClick={() => parse(d, 'mission_to_stakeholders', 'профили стейкхолдеров')}>
                            Разобрать: профили стейкхолдеров
                          </button>
                          <button className="np-btn" disabled={busy}
                            onClick={() => parse(d, 'mission_to_typical_risks', 'типовые риски')}>
                            Разобрать: типовые риски
                          </button>
                          <span className="sp-ds">результат придёт на акцепт</span>
                        </div>
                        {parseNote && <div className="sp-ds" style={{ marginTop: 4 }}>{parseNote}</div>}
                      </div>
                    )}
                  </div>
                ))}
                {/* круг 2: файл + карточка; круг 3 §2: без нативных контролов —
                    drop-зона, стилизованная кнопка, тип чипами */}
                <div className="sp-set" style={{ borderTop: '1px solid var(--container-3)', display: 'block' }}>
                  <div
                    className={`sp-drop${dragOver ? ' over' : ''}`}
                    onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
                    onDragLeave={() => setDragOver(false)}
                    onDrop={(e) => {
                      e.preventDefault()
                      setDragOver(false)
                      const f = e.dataTransfer.files?.[0] ?? null
                      if (f) {
                        setUpFile(f)
                        if (!upName) setUpName(f.name.replace(/\.[^.]+$/, ''))
                      }
                    }}
                  >
                    Перетащите файлы сюда — справочники, записки, регуляторные документы —{' '}
                    <label className="sp-take" style={{ cursor: 'pointer', display: 'inline-block' }}>
                      Выбрать файлы
                      <input type="file" style={{ display: 'none' }} onChange={(e) => {
                        const f = e.target.files?.[0] ?? null
                        setUpFile(f)
                        if (f && !upName) setUpName(f.name.replace(/\.[^.]+$/, ''))
                        e.target.value = ''
                      }} />
                    </label>{' '}
                    лягут материалом проекта.
                  </div>
                  {upFile && (
                    <div className="sp-file" style={{ marginTop: 6 }}>
                      <span className="sp-mono">{upFile.name}</span>
                      <button className="sp-undo" onClick={() => setUpFile(null)}>убрать</button>
                    </div>
                  )}
                  <div className="sp-tchips" style={{ marginTop: 6 }}>
                    Тип:{' '}
                    {(['mission_note', 'normative', 'datasheet', 'reference', 'other'] as const).map((k) => (
                      <button key={k} type="button"
                        className={`sp-tchip${upKind === k ? ' sel' : ''}`}
                        onClick={() => setUpKind(k)}>
                        {label('sd_kind', k)}
                      </button>
                    ))}
                  </div>
                  <div style={{ display: 'flex', gap: 6, marginTop: 6 }}>
                    <input placeholder="наименование карточки" value={upName}
                      onChange={(e) => setUpName(e.target.value)} style={{ flex: 1 }} />
                    <input placeholder="источник (организация)" value={upOrg}
                      onChange={(e) => setUpOrg(e.target.value)} style={{ width: 160 }} />
                    <button className="sp-open" disabled={busy || !upFile || !upName.trim()} onClick={upload}>
                      Загрузить
                    </button>
                  </div>
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
              <pre>{chosenDocs.length > 0
                ? [
                  `Документы в промпте — ${chosenDocs.length} из ${withText.length}` +
                    (excludedDocs.length > 0
                      ? ` (${excludedDocs.map((d) => `«${d.name}»`).join(', ')} исключен${excludedDocs.length === 1 ? 'а' : 'ы'} вами)`
                      : '') + ':',
                  ...chosenDocs.map((d) => `· ${d.name}${d.summary ? `: «${d.summary}»` : ''}`),
                  ...(Object.keys(stepCounts).length > 0
                    ? [`Наборы: ${Object.entries(stepCounts).map(([t, n]) => countPhrase(t, n)).join(' · ')}.`]
                    : []),
                ].join('\n')
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
