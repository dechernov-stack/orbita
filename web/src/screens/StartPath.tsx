// Мастер-путь «Начало проекта» — конвейер экранов, эталон
// docs/ui/reference2/reference-project-start.html (круг 1) + бриф
// БРИФ-МАСТЕР-ПУТЬ.md. Четыре шага со степпером: параметры → библиотека и
// материалы → замысел → ИИ. Ф-11: замысел стоит ПОСЛЕ материалов — «собрать
// из документов» на первом шаге была мертва по построению, документы
// появлялись только на втором.
// Путь, не клетка: «пропустить» всегда на виду, шаг сохраняется в паспорт,
// брошенный путь живёт строкой на жизненном цикле.
import { useCallback, useEffect, useRef, useState } from 'react'
import { api, type TakeWindow } from '../api/client'
import { edit } from '../api/edit'
import { countPhrase } from '../ui/countPhrase'
import { MissionIntent } from './MissionIntent'
import { Select } from '../ui/Select'
import { useSession } from '../ui/session'

interface Constraint {
  code?: string
  text: string
  category?: string
  /** Ф-02: мягкая отмена с историей — след решения ценен для точки. */
  removed?: boolean
  removed_by?: string
  removed_at?: string
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

/** Ф-02: ОДИН префикс кодов — Р-серия для всех ограничений проекта;
 * коды стабильны (на них ссылаются промпт службы и трассировки), дыры
 * законны — убранное код не переиспользует. */
function nextOwnCode(existing: Constraint[]): string {
  let top = 0
  existing.forEach((c) => {
    const m = (c.code ?? '').match(/^Р(\d+)$/)
    if (m && Number(m[1]) > top) top = Number(m[1])
  })
  return `Р${top + 1}`
}

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
  // Полный разбор префикса: неизвестный возвращал сам себя, и в паспорте
  // появлялись «3 AP · 5 FC · 23 FN» — служебные буквы вместо слов (04.09)
  const map: Record<string, string> = {
    RQ: 'requirement', DT: 'document_template', CM: 'component', CU: 'component_usage',
    IF: 'interface', TR: 'typical_risk', SH: 'stakeholder_profile', NR: 'normative_document',
    WB: 'wbs_element', CE: 'cost_estimate', AP: 'ai_profile', FN: 'function',
    FC: 'function_chain', LC: 'logical_component', OC: 'capability', SK: 'stakeholder',
    SM: 'system_model', MG: 'mission_goal', ND: 'need', SV: 'service', CO: 'conops',
    RSK: 'risk', SD: 'source_document', ME: 'model_element', AR: 'arch_link',
    TL: 'technology', DN: 'decision', RF: 'review_item', OD: 'oda', AL: 'alternative',
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

/** Тексты замысла без служебных полей: sources — якоря, не строка. */
export function textOfIntent(mi: unknown): {
  for_whom?: string; what?: string; where?: string; horizon?: string; text?: string
} {
  if (!mi || typeof mi !== 'object') return {}
  const src = mi as Record<string, unknown>
  const out: Record<string, string> = {}
  for (const field of ['for_whom', 'what', 'where', 'horizon', 'text']) {
    const v = src[field]
    if (typeof v === 'string' && v.trim() !== '') out[field] = v
  }
  return out
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
  /** Ф-05: замысел миссии — без него генерация постановки заблокирована. */
  const [intent, setIntent] = useState<{
    for_whom?: string; what?: string; where?: string; horizon?: string; text?: string
  }>({})
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
  /** Ф-03: брифы типов — из глоссария (полка LIB), не из хардкода. */
  const [glossary, setGlossary] = useState<Record<string, {
    brief: string; extracts?: string; card_hint?: string
  }>>({})
  const [upName, setUpName] = useState('')
  const [upOrg, setUpOrg] = useState('')
  const [upFile, setUpFile] = useState<File | null>(null)
  /** Б5-01: имя, подставленное по файлу; правку руками не перетираем. */
  const autoName = useRef('')
  const [openCard, setOpenCard] = useState<string | null>(null)
  /** Круг 3 §3: участие в промпте множественное — чекбоксы, не radio. */
  const [promptDocs, setPromptDocs] = useState<Set<string>>(new Set())
  const promptSeeded = useRef(false)
  /** Круг 3 §1: взятые фрагменты — из связей «применяет» и локальных взятий. */
  const [applied, setApplied] = useState<Record<string, { count: number; by_type: Record<string, number> }>>({})
  const [busyFrag, setBusyFrag] = useState<string | null>(null)
  /** Решение Б3-01 ред. 2: окно взятия — выбор элементов у инженера. */
  const [takeWin, setTakeWin] = useState<TakeWindow | null>(null)
  const [takePicked, setTakePicked] = useState<Set<string>>(new Set())
  const [extras, setExtras] = useState<Record<string, Set<string>>>({})
  const [takeSearch, setTakeSearch] = useState('')
  const [takeDepth, setTakeDepth] = useState<number | null>(null)
  /** Отказ взятия виден у самой кнопки — низ экрана вне поля зрения. */
  const [fragErr, setFragErr] = useState<{ id: string; msg: string } | null>(null)
  const [dragOver, setDragOver] = useState(false)
  const [profile, setProfile] = useState<{ id: string; version: string; name: string } | null>(null)
  const [promptFull, setPromptFull] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [packetNote, setPacketNote] = useState<string | null>(null)
  const busyRef = useRef(false)
  const [failure, setFailure] = useState<string | null>(null)
  /** Что принесла генерация: предложения ждут акцепта, а не исчезают. */
  const [harvest, setHarvest] = useState<
    { call: number | null; model: string; items: Array<Record<string, unknown>> } | null
  >(null)
  const [pickedItems, setPickedItems] = useState<Set<number>>(new Set())

  useEffect(() => {
    edit.object(project)
      .then((o) => {
        const doc = o.doc as {
          mission_class?: string
          constraints?: Constraint[]
          start_path?: PathState
          mission_intent?: {
            for_whom?: string; what?: string; where?: string; horizon?: string; text?: string
          }
        }
        setMissionClass(doc.mission_class ?? '')
        setConstraints(doc.constraints ?? [])
        // Ф-07: у принятого замысла рядом с текстами лежат sources —
        // якоря происхождения полей. Это НЕ строка: в состояние формы идут
        // только тексты, иначе обход полей спотыкается о объект.
        setIntent(textOfIntent(doc.mission_intent))
        const sp = doc.start_path
        // шаг за пределами 1..4 отсекает схема паспорта — доверяем ей.
        // Путь, не клетка: пройденный путь открывается там, где его
        // оставили, а не сбрасывается на первый шаг — вернуться к запуску
        // генерации инженер обязан уметь в один щелчок.
        if (sp && (sp.status === 'in_progress' || sp.status === 'done')) setStep(sp.step)
        if (sp?.source_refs?.length || sp?.source_ref) {
          promptSeeded.current = true
          setPromptDocs(new Set(sp.source_refs ?? [sp.source_ref!]))
        }
      })
      .catch((e) => setFailure(reasonOf(e)))
    reloadOwn()
    api.libraryDocs().then(setLibrary).catch(() => setLibrary([]))
    api.missionClasses().then(setClasses).catch(() => setClasses([]))
    api.glossary()
      .then((rows) => {
        const byKind: Record<string, { brief: string; extracts?: string; card_hint?: string }> = {}
        rows.forEach((e) => { if (e.sd_kind) byKind[e.sd_kind] = e })
        setGlossary(byKind)
      })
      .catch(() => setGlossary({}))
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
  /** ADR-051: каркас берётся с условиями — их спрашивает диалог перед взятием. */
  const applyFragment = (id: string, options: Parameters<typeof api.libraryApply>[2] = {}) => {
    if (busyFrag) return
    setBusyFrag(id)
    setFragErr(null)
    api.libraryApply(id, author, options)
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
      .catch((e) => setFragErr({ id, msg: reasonOf(e) }))
      .finally(() => setBusyFrag(null))
  }

  const openTakeWindow = (id: string) => {
    api.libraryTakeWindow(id)
      .then((w) => {
        setTakeWin(w)
        setTakePicked(new Set(w.elements.filter((e) => e.default_take || e.taken).map((e) => e.id)))
        setExtras({})
        setTakeSearch('')
        setTakeDepth(null)
      })
      .catch((e) => setFragErr({ id, msg: reasonOf(e) }))
  }

  /** Зависимость выбранного элемента, которой нет в проекте: серая строка с кнопкой довзять. */
  const missingNeeds = (w: TakeWindow, sel: Set<string>) =>
    w.elements.filter((e) => sel.has(e.id) && !e.taken).flatMap((e) =>
      e.needs.filter((n) => !n.in_project && (n.internal ? !sel.has(n.payload_id ?? '') : true))
        .map((n) => ({ element: e, need: n })))

  const takeSelected = () => {
    if (!takeWin) return
    const w = takeWin
    const select = w.elements.filter((e) => takePicked.has(e.id) && !e.taken).map((e) => e.id)
    const unselect = w.elements.filter((e) => !takePicked.has(e.id) && e.taken).map((e) => e.id)
    const mapping = Object.fromEntries(w.elements.filter((e) => e.taken && e.code).map((e) => [e.code, e.taken]))
    const ex = Object.fromEntries(Object.entries(extras).map(([f, codes]) => [f, [...codes]]))
    applyFragment(w.id, { select, unselect, extras: ex, mapping, ...(takeDepth ? { depth: takeDepth } : {}) })
    setTakeWin(null)
  }

  /** Отмена взятия: удаляет созданное именно этим взятием; тронутое — отказ. */
  const revertFragment = (id: string) => {
    if (busyFrag) return
    setBusyFrag(id)
    setFragErr(null)
    api.libraryRevert(id, author)
      .then(() => setApplied((prev) => {
        const next = { ...prev }
        delete next[id]
        return next
      }))
      .catch((e) => setFragErr({ id, msg: reasonOf(e) }))
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

  /** Шаг сохраняется сам: паспорт правится процедурой с основанием.
   * over — значения свежее state (автосохранение правки ограничений). */
  const save = useCallback(async (
    path: PathState,
    over?: {
      constraints?: Constraint[]
      missionClass?: string
      intent?: { for_whom?: string; what?: string; where?: string; horizon?: string; text?: string }
    },
  ) => {
    const cons = over?.constraints ?? constraints
    const mc = over?.missionClass ?? missionClass
    const mi = over?.intent ?? intent
    const fresh = await edit.object(project)
    const doc = { ...(fresh.doc as Record<string, unknown>) }
    doc.mission_class = mc.trim() || undefined
    if (doc.mission_class === undefined) delete doc.mission_class
    if (cons.length > 0) doc.constraints = cons
    else delete doc.constraints
    // Ф-05: замысел миссии — поле паспорта; пустые поля не хранятся.
    // Ф-07: якоря происхождения (sources) — не текст и правке рукой не
    // подлежат, но и теряться не должны: правка формулировки не стирает
    // след того, из каких блоков документа поле выведено.
    const intentDoc: Record<string, unknown> = Object.fromEntries(
      Object.entries(mi).filter(([, v]) => typeof v === 'string' && v.trim() !== ''),
    )
    const keptSources = (fresh.doc as { mission_intent?: { sources?: unknown } })
      ?.mission_intent?.sources
    if (Object.keys(intentDoc).length > 0) {
      if (keptSources) intentDoc.sources = keptSources
      doc.mission_intent = intentDoc
    } else delete doc.mission_intent
    doc.start_path = {
      ...path,
      ...(promptDocs.size > 0 ? { source_refs: [...promptDocs] } : {}),
      ...(Object.keys(applied).length > 0 ? { created_counts: sumCounts(applied) } : {}),
    }
    return edit.changeWithRef(project, doc, CHANGE_REF)
  }, [project, missionClass, constraints, intent, promptDocs, applied])

  /** А2 ПМИ-2: «сохраняется само» — правка ограничений и класса пишется в
   * паспорт сразу, а не при смене шага; уход с экрана ничего не теряет. */
  const saveNow = (over: {
    constraints?: Constraint[]
    missionClass?: string
    intent?: { for_whom?: string; what?: string; where?: string; horizon?: string; text?: string }
  }) => {
    save({ status: 'in_progress', step }, over).catch((e) => setFailure(reasonOf(e)))
  }

  /** Набранное, но не добавленное ограничение не теряется: перед сменой
   * шага строка из поля становится ограничением с номером. */
  const flushAdding = (): Constraint[] => {
    if (!adding.trim()) return constraints
    const next = [...constraints, { code: nextOwnCode(constraints), text: adding.trim() }]
    setConstraints(next)
    setAdding('')
    return next
  }

  const addConstraint = () => {
    if (!adding.trim()) return
    const next = flushAdding()
    saveNow({ constraints: next })
  }

  /**
   * Вставленный в поле абзаца ПАКЕТ — не текст замысла.
   *
   * Инженер копирует ответ службы и вставляет в ближайшее поле; форма его
   * послушно сохраняла строкой, и в паспорт ложился весь JSON целиком —
   * замысел «задан», но состоит из фигурных скобок, а документы печатают
   * его как есть (находка живого прохода ПМИ-3). Теперь пакет узнаётся и
   * идёт своим путём: через ворота схемы на предложение с якорями.
   */
  const saveIntentText = async () => {
    const текст = (intent.text ?? '').trim()
    const похоже = текст.startsWith('{') && текст.includes('mission_intent')
    if (!похоже) {
      setPacketNote(null)
      saveNow({ intent })
      return
    }
    try {
      const draft = await api.missionIntentDraft(текст)
      const поля = draft.intent
      const собранный = {
        for_whom: поля.for_whom?.text ?? '',
        what: поля.what?.text ?? '',
        where: поля.where?.text ?? '',
        horizon: поля.horizon?.text ?? '',
      }
      setIntent(собранный)
      saveNow({ intent: собранный })
      setPacketNote('это был пакет службы, а не абзац — разобран воротами схемы, поля заполнены; якоря происхождения ложатся при акцепте предложения ниже')
    } catch (e) {
      setPacketNote(`похоже на пакет службы, но ворота его не приняли: ${String(e)}. Исправьте пакет либо напишите замысел словами`)
    }
  }

  /** Замысел задан: связный абзац либо все четыре поля (правило сервера). */
  const intentReady = (intent.text ?? '').trim() !== '' ||
    ['for_whom', 'what', 'where', 'horizon'].every(
      (f) => ((intent as Record<string, string | undefined>)[f] ?? '').trim() !== '',
    )

  const removeConstraint = (i: number) => {
    // Ф-02: отмена с историей (механика Б-02) — жёсткого удаления нет;
    // отклонение затравочного ограничения — след решения для точки
    const next = constraints.map((c, j) => (j === i
      ? { ...c, removed: true, removed_by: author || 'инженер', removed_at: new Date().toISOString().slice(0, 10) }
      : c))
    setConstraints(next)
    saveNow({ constraints: next })
  }

  const restoreConstraint = (i: number) => {
    const next = constraints.map((c, j) => {
      if (j !== i) return c
      const { removed, removed_by, removed_at, ...rest } = c
      void removed; void removed_by; void removed_at
      return rest
    })
    setConstraints(next)
    saveNow({ constraints: next })
  }

  const skip = () => {
    if (busyRef.current) return
    save({ status: 'skipped', step }, { constraints: flushAdding() })
      .then(onDone).catch((e) => setFailure(reasonOf(e)))
  }

  const toStep = (next: number) => {
    setFailure(null)
    save({ status: 'in_progress', step: next }, { constraints: flushAdding() })
      .then(() => setStep(next))
      .catch((e) => setFailure(reasonOf(e)))
  }

  const assembleAndGo = () => {
    if (busyRef.current) return
    busyRef.current = true
    setBusy(true)
    setFailure(null)
    save({ status: 'in_progress', step: 4 }, { constraints: flushAdding() })
      .then(() => api.startPathProfile(author))
      .then((p) => { setProfile(p); setStep(4) })
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

  /**
   * Генерация приносит ПРЕДЛОЖЕНИЯ, а не тишину. Раньше мастер звал службу
   * дважды и выбрасывал оба ответа: журнал считал «предложено 10 и 15», а
   * инженер видел пустые экраны — принять было нечего, потому что до акцепта
   * ничего не доходило. Теперь предложения складываются и ждут решения:
   * в модель по-прежнему пишет только акцепт.
   */
  const run = async () => {
    if (busyRef.current || !profile) return
    busyRef.current = true
    setBusy(true)
    setFailure(null)
    try {
      const statement = await materialStatement()
      const goals = await api.aiAsk('mission_to_goals', profile.id, statement, author)
      const needs = await api.aiAsk('mission_to_needs', profile.id, statement, author)
      const failed = [goals, needs].find((r) => r.failed)
      if (failed) {
        setFailure(failed.reason ?? 'служба не ответила')
        return
      }
      const items = [...goals.shown, ...needs.shown].map((s) => s.item)
      setHarvest({
        call: needs.call ?? goals.call ?? null,
        model: needs.model ?? goals.model ?? '',
        items,
      })
      setPickedItems(new Set(items.map((_, i) => i)))
      if (items.length === 0) {
        setFailure(
          'служба ответила, но ни одного предложения не прошло фильтр — ' +
          'смотрите журнал вызовов: там причина по каждому',
        )
      }
    } catch (e) {
      setFailure(reasonOf(e))
    } finally {
      busyRef.current = false
      setBusy(false)
    }
  }

  /** Акцепт принесённого: в модель пишет инженер, не служба. */
  const acceptHarvest = async () => {
    if (!harvest || busyRef.current || pickedItems.size === 0) return
    busyRef.current = true
    setBusy(true)
    setFailure(null)
    try {
      const chosen = harvest.items.filter((_, i) => pickedItems.has(i))
      await api.acceptBatchOfCall(harvest.call, harvest.model, author, chosen)
      await save({ status: 'done', step: 4, profile_ref: profile!.id })
      setHarvest(null)
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

  /** А6 ПМИ-2: промпт уносится в любую LLM одним действием — целиком в
   * буфер обмена; вне защищённого контекста — запасной путь. */
  const [copied, setCopied] = useState(false)
  const copyTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const copyPrompt = async () => {
    if (!profile) return
    setFailure(null)
    try {
      let text = promptFull
      if (!text) {
        const statement = await materialStatement()
        const r = await api.aiCompose('mission_to_goals', profile.id, statement)
        text = r.prompt
        setPromptFull(text)
      }
      try {
        await navigator.clipboard.writeText(text)
      } catch {
        const ta = document.createElement('textarea')
        ta.value = text
        ta.style.position = 'fixed'
        ta.style.opacity = '0'
        document.body.appendChild(ta)
        ta.select()
        document.execCommand('copy')
        ta.remove()
      }
      setCopied(true)
      if (copyTimer.current) clearTimeout(copyTimer.current)
      copyTimer.current = setTimeout(() => setCopied(false), 2500)
    } catch (e) {
      setFailure(reasonOf(e))
    }
  }

  /**
   * П-01: промпт забирается ФАЙЛОМ. Шапка .md называет вид пакета, отпечаток
   * знаний и схему ответа — внешний контур отвечает пакетом, который
   * вставляется без правок.
   */
  const downloadPrompt = async () => {
    if (!profile) return
    setFailure(null)
    try {
      let text = promptFull
      if (!text) {
        const statement = await materialStatement()
        const r = await api.aiCompose('mission_to_goals', profile.id, statement)
        text = r.prompt
        setPromptFull(text)
      }
      const шапка = [
        '# Промпт службы · Орбита',
        '',
        `- вид пакета: mission_to_goals (цели миссии) и mission_to_needs (нужды стейкхолдеров)`,
        `- профиль службы: ${profile.id} в. ${profile.version}`,
        `- проект: ${project}`,
        `- собран: ${new Date().toISOString().slice(0, 16).replace('T', ' ')}`,
        '',
        '## Схема ответа',
        '',
        'Ответ — JSON вида `{"kind": "<вид пакета>", "items": [<объекты вида>]}`;',
        'объекты — по схеме вида из реестра `prompt-package-kinds`. Вставляется',
        'на шаге «Запуск ИИ» кнопкой «Вставить пакет».',
        '',
        '## Промпт',
        '',
      ].join('\n')
      const blob = new Blob([`${шапка}${text}`], { type: 'text/markdown;charset=utf-8' })
      const a = document.createElement('a')
      a.href = URL.createObjectURL(blob)
      a.download = `промпт-${project}-${new Date().toISOString().slice(0, 10)}.md`
      document.body.appendChild(a)
      a.click()
      a.remove()
      setTimeout(() => URL.revokeObjectURL(a.href), 1000)
    } catch (e) {
      setFailure(reasonOf(e))
    }
  }

  // Продолжение брошенного пути с Ш4: профиль пересобирается при входе —
  // вызов идемпотентен (сервер обновляет, а не плодит дубли)
  useEffect(() => {
    if (step === 4 && !profile && author && !busyRef.current) {
      api.startPathProfile(author).then(setProfile).catch((e) => setFailure(reasonOf(e)))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [step, profile, author])

  const withText = (docs ?? []).filter((d) => d.hasText)
  const chosenDocs = withText.filter((d) => promptDocs.has(d.id))
  const excludedDocs = withText.filter((d) => !promptDocs.has(d.id))
  const stepCounts = sumCounts(applied)

return (
    <>
    {takeWin && (() => {
      const w = takeWin
      const q = takeSearch.trim().toLowerCase()
      const visible = w.elements.filter((e) =>
        (!q || `${e.code} ${e.name} ${e.why}`.toLowerCase().includes(q)) &&
        (takeDepth == null || e.level < 0 || e.level <= takeDepth))
      const groups = new Map<string, TakeWindow['elements']>()
      visible.forEach((e) => { groups.set(e.type, [...(groups.get(e.type) ?? []), e]) })
      const chosen = w.elements.filter((e) => takePicked.has(e.id))
      const newOnes = chosen.filter((e) => !e.taken)
      const missing = missingNeeds(w, takePicked)
      const unlocks = new Set(chosen.flatMap((e) => e.needed_by.filter((d) => !d.same_shelf).map((d) => `${d.fragment}:${d.code}`)))
      const byType = new Map<string, number>()
      newOnes.forEach((e) => byType.set(e.type, (byType.get(e.type) ?? 0) + 1))
      const toggle = (id: string) => setTakePicked((prev) => { const n = new Set(prev); if (n.has(id)) n.delete(id); else n.add(id); return n })
      const addExtra = (fragment: string, code: string) =>
        setExtras((prev) => ({ ...prev, [fragment]: new Set([...(prev[fragment] ?? []), code]) }))
      return (
      <div className="rr-cfg" style={{ position: 'fixed', right: 24, top: 96, width: 460, maxHeight: '80vh', overflow: 'auto', zIndex: 20, background: 'var(--bg)', border: '1px solid var(--hairline)', padding: 12 }}>
        <h4>
          Взять: {w.name}
          <button type="button" className="rr-assign" style={{ float: 'right' }} onClick={() => setTakeWin(null)}>закрыть</button>
        </h4>
        <div className="sp-ds">
          Данные полки полны, выбор — ваш: отмечен рекомендованный набор класса, взятое ранее — тоже
          (снятие отметки отменит его с историей). Зависимости считаются по ссылкам.
        </div>
        <div className="rr-col" style={{ marginTop: 6, display: 'flex', gap: 6 }}>
          <input placeholder="поиск по коду, имени, «зачем»" value={takeSearch} onChange={(e) => setTakeSearch(e.target.value)} style={{ flex: 1 }} />
          {w.elements.some((e) => e.level > 1) && (
            <select value={takeDepth ?? ''} onChange={(e) => setTakeDepth(e.target.value ? Number(e.target.value) : null)} title="фильтр уровней каркаса">
              <option value="">все уровни</option>
              <option value={3}>до L3</option><option value={4}>до L4</option><option value={5}>до L5</option>
            </select>
          )}
          <button type="button" className="np-linkish" onClick={() => setTakePicked(new Set(w.elements.map((e) => e.id)))}>все</button>
          <button type="button" className="np-linkish" onClick={() => setTakePicked(new Set(w.elements.filter((e) => e.default_take || e.taken).map((e) => e.id)))}>рекомендованное</button>
        </div>
        <div style={{ maxHeight: '38vh', overflow: 'auto', marginTop: 6, fontSize: 12 }}>
          {[...groups.entries()].map(([type, els]) => (
            <div key={type}>
              <div className="secondary" style={{ marginTop: 4 }}>{type} · {els.filter((e) => takePicked.has(e.id)).length} из {els.length}</div>
              {els.map((e) => {
                const miss = e.needs.filter((n) => !n.in_project && (n.internal ? !takePicked.has(n.payload_id ?? '') : true))
                const grey = takePicked.has(e.id) && miss.length > 0
                return (
                  <div key={e.id} style={{ opacity: grey ? 0.6 : 1, padding: '1px 0' }} title={e.why}>
                    <label>
                      <input type="checkbox" checked={takePicked.has(e.id)} onChange={() => toggle(e.id)} />{' '}
                      <span className="mono">{e.code || e.id}</span> {e.name}
                      {e.taken && <span className="chip" title={`уже в проекте: ${e.taken}`}>взято</span>}
                      {!e.default_take && !e.taken && <span className="secondary"> · вне рекомендованного</span>}
                    </label>
                    {e.needed_by.length > 0 && (
                      <span className="secondary" title={e.needed_by.map((d) => `${d.fragment_name}: ${d.code} ${d.name}`).join('\n')}>
                        {' '}· от него зависит: {e.needed_by.length}
                      </span>
                    )}
                    {grey && miss.map((n) => (
                      <div key={n.code} style={{ marginLeft: 22 }}>
                        требует <span className="mono">{n.code}</span> {n.name ?? ''}
                        {n.internal
                          ? <button type="button" className="np-linkish" style={{ marginLeft: 6 }} onClick={() => n.payload_id && toggle(n.payload_id)}>выбрать тоже</button>
                          : n.shelf
                            ? (extras[n.shelf]?.has(n.code)
                              ? <span className="chip">довзять из «{n.shelf_name}»</span>
                              : <button type="button" className="np-linkish" style={{ marginLeft: 6 }} onClick={() => addExtra(n.shelf!, n.code)}>взять «{n.code}» из полки «{n.shelf_name}»</button>)
                            : <span className="bad"> — нет ни в проекте, ни на полках</span>}
                      </div>
                    ))}
                  </div>
                )
              })}
            </div>
          ))}
        </div>
        <div className="sp-ds" style={{ marginTop: 8 }}>
          взять {newOnes.length}{[...byType.entries()].length > 0 && ` (${[...byType.entries()].map(([t, n]) => `${t}: ${n}`).join(' · ')})`}
          {Object.values(extras).reduce((a, b) => a + b.size, 0) > 0 && ` · довзять из других полок: ${Object.values(extras).reduce((a, b) => a + b.size, 0)}`}
          {unlocks.size > 0 && ` · станут доступны в других полках: ${unlocks.size}`}
          {w.elements.some((e) => e.taken && !takePicked.has(e.id)) && ` · снять: ${w.elements.filter((e) => e.taken && !takePicked.has(e.id)).length}`}
        </div>
        <button type="button" className="sp-take" style={{ marginTop: 8 }}
          disabled={busyFrag !== null || missing.some((m) => !m.need.internal && !(m.need.shelf && extras[m.need.shelf]?.has(m.need.code)))}
          title={missing.length > 0 ? 'у выбранного есть зависимости без пары: довзьмите их или снимите элемент' : 'взять выбранное'}
          onClick={takeSelected}>
          взять выбранное
        </button>
      </div>
      )
    })()}

    <div className="np-main">
      <div className="sp-work">
        <div className="sp-path">
          <h2>Начало проекта</h2>
          <button className="sp-skip" onClick={skip}>Пропустить — заполню руками</button>
        </div>
        <div className="sp-steps">
          {['Основные параметры', 'Библиотека и материалы', 'Замысел миссии', 'Запуск ИИ'].map((t, i) => (
            <button key={t} type="button"
              className={`sp-st${step === i + 1 ? ' sp-on' : ''}${step > i + 1 ? ' sp-done' : ''}`}
              onClick={() => toStep(i + 1)}
              disabled={!author || step === i + 1}
              title={!author
                ? 'представьтесь в шапке: переход пишет шаг в паспорт'
                : step === i + 1 ? 'вы на этом шаге' : `перейти к шагу «${t}»`}>
              <span className="sp-n">{i + 1}</span>{t}
            </button>
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
                      const subst = cls && constraints.length === 0 && cls.typical_constraints.length > 0
                      if (subst) setConstraints(cls.typical_constraints)
                      saveNow({
                        missionClass: v,
                        ...(subst ? { constraints: cls.typical_constraints } : {}),
                      })
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
                  <div className="sp-lim" key={`${c.code ?? ''}-${i}`}
                    style={c.removed ? { opacity: 0.55 } : undefined}>
                    <span className="sp-no"
                      title="код стабилен: на него ссылаются промпт службы и трассировки; дыры в нумерации законны">
                      {c.code ?? ''}
                    </span>
                    <span className="sp-tx"
                      style={c.removed ? { textDecoration: 'line-through' } : undefined}>
                      {c.text}
                      {c.category && <span className="secondary"> · {c.category}</span>}
                      {c.removed && (
                        <span className="secondary"
                          title="след решения: отклонённое ограничение ценно для точки; в запреты службы не уходит">
                          {' '}— отменено{c.removed_by ? ` (${c.removed_by}` : ''}{c.removed_at ? `, ${c.removed_at})` : c.removed_by ? ')' : ''}
                        </span>
                      )}
                    </span>
                    {c.removed ? (
                      <button className="sp-rm" title="вернуть ограничение в действующие"
                        onClick={() => restoreConstraint(i)}>
                        вернуть
                      </button>
                    ) : (
                      <button className="sp-rm" title="мягкая отмена с историей — след останется"
                        onClick={() => removeConstraint(i)}>
                        убрать
                      </button>
                    )}
                  </div>
                ))}
                <div className="sp-addrow" style={{ display: 'flex', gap: 6 }}>
                  <input value={adding} style={{ flex: 1 }}
                    placeholder="Добавить ограничение — например: «зоны обслуживания — статическими масками»"
                    onChange={(e) => setAdding(e.target.value)}
                    onKeyDown={(e) => { if (e.key === 'Enter') addConstraint() }} />
                  <button title="впишите текст ограничения — пустая строка кодом не становится" className="sp-open" disabled={!adding.trim()} onClick={addConstraint}>
                    добавить
                  </button>
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
                        {fragErr?.id === f.id && (
                          <div className="sp-ds" style={{ color: 'var(--error, #b3261e)' }}>
                            не взято: {fragErr.msg}
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
                          <>
                            <button className="sp-open" disabled={busyFrag !== null}
                              title="то же окно: взятое отмечено, невзятое — выбрать; снятие отметки — отмена с историей"
                              onClick={() => openTakeWindow(f.id)}>
                              открыть снова
                            </button>
                            <button className="sp-undo" disabled={busyFrag !== null}
                              title="удаляет созданное именно этим взятием; тронутое руками — отказ"
                              onClick={() => revertFragment(f.id)}>
                              {busyFrag === f.id ? 'отменяю…' : 'отменить'}
                            </button>
                          </>
                        )
                        : (
                          <button title="идёт взятие другого набора — дождитесь его окончания" className="sp-take" disabled={busyFrag !== null}
                            onClick={() => openTakeWindow(f.id)}>
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
                    <button title="отметьте наборы библиотеки галочками — берётся отмеченное" className="sp-open" disabled={picked.size === 0 || busy} onClick={take}>
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
                    отмеченные уходят в промпт службы на Ш4 — участие множественное
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
                          {/* Б5-03: виды извлечения по типу заменены единым
                              смысловым разбором (Д2) — прежние кнопки вели в
                              отказ «нет профиля службы». Справка в промпте
                              участвует, а извлечений не порождает. */}
                          {d.kind === 'reference'
                            ? (
                              <span className="sp-ds">
                                справка участвует в промпте, извлечений не порождает
                              </span>
                            )
                            : (
                              <>
                                <button
                                  className="np-btn"
                                  disabled={busy}
                                  title={busy
                                    ? 'идёт запись в паспорт — дождитесь её окончания'
                                    : 'смысловой разбор документа: кандидаты придут на акцепт во вкладку «Найдено в документе»'}
                                  onClick={() => onGo('docparse')}>
                                  Разобрать документ
                                </button>
                                <span className="sp-ds">кандидаты придут на акцепт</span>
                              </>
                            )}
                        </div>
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
                        // Б5-01: имя следует за ФАЙЛОМ, пока его не правили
                        // руками — иначе карточка носит имя прежнего выбора
                        if (!upName || upName === autoName.current) {
                          const имя = f.name.replace(/\.[^.]+$/, '')
                          autoName.current = имя
                          setUpName(имя)
                        }
                      }
                    }}
                  >
                    Перетащите файлы сюда — справочники, записки, регуляторные документы —{' '}
                    <label className="sp-take" style={{ cursor: 'pointer', display: 'inline-block' }}>
                      Выбрать файлы
                      <input type="file" style={{ display: 'none' }} onChange={(e) => {
                        const f = e.target.files?.[0] ?? null
                        setUpFile(f)
                        if (f && (!upName || upName === autoName.current)) {
                          const имя = f.name.replace(/\.[^.]+$/, '')
                          autoName.current = имя
                          setUpName(имя)
                        }
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
                        title={glossary[k]
                          ? [glossary[k].brief, glossary[k].extracts, glossary[k].card_hint]
                              .filter(Boolean).join(' ')
                          : undefined}
                        onClick={() => setUpKind(k)}>
                        {label('sd_kind', k)}
                      </button>
                    ))}
                  </div>
                  {glossary[upKind] && (
                    <div className="secondary" style={{ marginTop: 4 }}>
                      {glossary[upKind].brief}
                      {glossary[upKind].extracts && ` ${glossary[upKind].extracts}`}
                    </div>
                  )}
                  <div style={{ display: 'flex', gap: 6, marginTop: 6 }}>
                    <input placeholder="наименование карточки" value={upName}
                      title={upFile ? `карточка для файла: ${upFile.name}` : 'сначала выберите файл'}
                      onChange={(e) => setUpName(e.target.value)} style={{ flex: 1 }} />
                    <input placeholder="источник (организация)" value={upOrg}
                      onChange={(e) => setUpOrg(e.target.value)} style={{ width: 160 }} />
                    <button title="выберите файл и назовите карточку — материал ложится с именем" className="sp-open" disabled={busy || !upFile || !upName.trim()} onClick={upload}>
                      Загрузить
                    </button>
                  </div>
                </div>
              </div>
            </div>
            <div className="np-actions">
              <button className="np-btn" onClick={() => toStep(1)}>Назад</button>
              <button className="np-btn np-pri" onClick={() => toStep(3)}>
                Далее — замысел миссии
              </button>
            </div>
          </>
        )}

        {/* Ф-11: замысел — ПОСЛЕ материалов. Документы уже приложены на Ш2,
            поэтому «собрать из документов» здесь живая, а не серая по
            построению. Форма рукой остаётся запасным путём. */}
        {step === 3 && (
          <>
            <div className="np-row">
              <label className="np-label">Замысел миссии{' '}
                <span style={{ fontWeight: 400, color: 'var(--status-draft)' }}>
                  — без него генерация постановки заблокирована
                </span>
              </label>
              <div style={{ display: 'grid', gap: 6 }}>
                {([
                  ['for_whom', 'Для кого', 'перевозчики опасных грузов, операторы БПЛА'],
                  ['what', 'Что делает', 'передаёт короткие сообщения от датчиков'],
                  ['where', 'Где', 'Арктика, СМП, Сибирь и ДФО'],
                  ['horizon', 'Горизонт', 'к 2033 году, около 150 аппаратов'],
                ] as const).map(([field, label, hint]) => (
                  <div key={field} style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
                    <span className="secondary" style={{ minWidth: 108, alignSelf: 'flex-start', paddingTop: 6 }}>
                      {label}
                    </span>
                    {/* Замысел, собранный из документов, — связный абзац на
                        полтысячи знаков: однострочное поле его прячет. Поле
                        растёт по содержимому, но остаётся полем ввода. */}
                    <textarea className="np-name" placeholder={hint}
                      style={{
                        flex: 1, minHeight: 34, maxHeight: 220, resize: 'vertical',
                        lineHeight: 1.35, overflowY: 'auto',
                      }}
                      rows={(intent[field] ?? '').length > 120 ? 5 : 1}
                      value={intent[field] ?? ''}
                      onChange={(e) => setIntent({ ...intent, [field]: e.target.value })}
                      onBlur={() => saveNow({ intent })} />
                  </div>
                ))}
                <details>
                  <summary className="secondary" style={{ cursor: 'pointer' }}>
                    либо одним связным абзацем
                  </summary>
                  <textarea rows={3} style={{ width: '100%', marginTop: 4 }}
                    placeholder="Группировка передаёт телеметрию перевозчикам в Арктике; горизонт — 2033 год."
                    value={intent.text ?? ''}
                    onChange={(e) => setIntent({ ...intent, text: e.target.value })}
                    onBlur={() => void saveIntentText()} />
                  {packetNote && <div className="np-hint">{packetNote}</div>}
                </details>
                <div className="np-hint">
                  {intentReady
                    ? 'замысел задан — служба соберёт постановку по данным проекта'
                    : 'нужны все четыре поля либо связный абзац'}
                </div>
                {/* Ф-07: второй путь — собрать по разобранным документам */}
                <MissionIntent onNeedMaterials={() => toStep(2)} onNeedParse={() => onGo('docparse')} onAccepted={() => {
                  edit.object(project)
                    .then((o) => setIntent(
                      textOfIntent((o.doc as { mission_intent?: Record<string, unknown> }).mission_intent),
                    ))
                    .catch(() => undefined)
                }} />
              </div>
            </div>
            <div className="np-actions">
              <button className="np-btn" onClick={() => toStep(2)}
                title={author
                  ? 'вернуться к материалам — замысел сохранён в паспорте'
                  : 'представьтесь в шапке: переход пишет шаг в паспорт, а правка без автора не принимается'}
                disabled={!author}>
                Назад — к материалам
              </button>
              <button
                title={!author
                  ? 'представьтесь в шапке: сборка профиля пишется в паспорт на автора'
                  : busy ? 'идёт запись в паспорт — дождитесь её окончания'
                    : 'собрать профиль службы из ограничений и перейти к запуску'}
                className="np-btn np-pri" disabled={busy || !author} onClick={assembleAndGo}>
                Далее — запуск ИИ
              </button>
            </div>
          </>
        )}

        {step === 4 && profile && (
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
              и наборов (Ш2)</b>; замысел — <b>с Ш3</b>. Промпт собирает служба; руками он не пишется.
            </div>

            <div className="sp-blk sp-profile">
              <div className="sp-src">Из ограничений проекта · Ш1</div>
              <pre>{[
                ...constraints.filter((c) => !c.removed)
                  .map((c) => `— ${c.text}${c.code ? ` (${c.code})` : ''}`),
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
                : 'материалы не выбраны — промпт возьмёт замысел, полки класса и принятое'}</pre>
            </div>
            {promptFull && (
              <div className="sp-blk">
                <div className="sp-src" style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  Промпт целиком
                  <button className="rr-assign" onClick={copyPrompt}>
                    {copied ? 'скопирован ✓' : 'скопировать'}
                  </button>
                  {/* П-01: выделять 44 тысячи знаков мышью — не путь; файл
                      несёт шапку с видом пакета и схемой ответа, чтобы ответ
                      внешнего контура вставлялся без правок */}
                  <button className="rr-assign" onClick={downloadPrompt}
                    title="файл .md с шапкой: вид пакета, отпечаток знаний, схема ответа">
                    скачать .md
                  </button>
                </div>
                <pre>{promptFull}</pre>
              </div>
            )}

            {failure && <div className="np-err"><b>Не выполнено:</b> {failure}</div>}
            <div className="np-actions">
              <button className="np-btn" onClick={() => setStep(2)}>Назад</button>
              {/* Ф-05: без замысла кнопка заблокирована С ПРИЧИНОЙ —
                  предупреждения мелким шрифтом тут мало */}
              <button className="np-btn np-pri" disabled={busy || !intentReady} onClick={run}
                title={intentReady
                  ? 'служба соберёт постановку по данным проекта'
                  : 'нет замысла — генерация даст общие места: заполните замысел на шаге 3'}>
                {busy ? 'Генерация…' : 'Запустить генерацию целей и нужд'}
              </button>
              {!intentReady && (
                <>
                  <button className="np-linkish" onClick={() => toStep(3)}
                    title="шаг 3 «Замысел миссии»: четыре поля либо связный абзац">
                    нет замысла — заполнить рукой →
                  </button>
                  <button className="np-linkish" onClick={() => toStep(3)}
                    title="шаг 3 «Замысел миссии»: постановка уже приложена и разобрана — собрать замысел по документам">
                    собрать из документов →
                  </button>
                </>
              )}
              <button className="np-linkish" onClick={showPrompt}>показать промпт целиком</button>
              <button className="np-linkish" onClick={copyPrompt}>
                {copied ? 'скопирован ✓' : 'скопировать промпт'}
              </button>
              <span className="sp-sp" />
              <span className="np-hint" style={{ margin: 0 }}>
                Предложения придут на акцепт — в модель ИИ не пишет.
              </span>
            </div>

            {/* Принесённое генерацией: список ждёт решения инженера. Пока он
                не принял — в модели ничего нет, и это видно. */}
            {harvest && (
              <div className="sp-blk" style={{ marginTop: 8 }}>
                <div className="sp-src">
                  Служба предложила: {harvest.items.length}
                  {harvest.model ? ` · ${harvest.model}` : ''} — отметьте, что принять
                </div>
                <table className="grid">
                  <thead>
                    <tr><th style={{ width: 24 }}></th><th>Код</th><th>Что предлагается</th></tr>
                  </thead>
                  <tbody>
                    {harvest.items.map((item, i) => (
                      <tr key={i}>
                        <td>
                          <input type="checkbox" checked={pickedItems.has(i)}
                            onChange={() => {
                              const next = new Set(pickedItems)
                              if (next.has(i)) next.delete(i); else next.add(i)
                              setPickedItems(next)
                            }} />
                        </td>
                        <td className="mono">{String(item.id ?? '—')}</td>
                        <td>{String(item.statement ?? item.name ?? '')}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="np-actions" style={{ marginTop: 6 }}>
                  <button className="np-btn np-pri" onClick={acceptHarvest}
                    disabled={busy || pickedItems.size === 0 || !author}
                    title={!author
                      ? 'представьтесь в шапке: акцепт пишется в модель на автора'
                      : pickedItems.size === 0
                        ? 'отметьте хотя бы одно предложение — принимается отмеченное'
                        : 'принять отмеченные предложения в модель проекта'}>
                    {busy ? 'Принимаю…' : `Принять отмеченные (${pickedItems.size})`}
                  </button>
                  <button className="np-btn" onClick={() => setHarvest(null)}
                    title="отказаться от предложений — в модели ничего не изменится">
                    отклонить всё
                  </button>
                </div>
              </div>
            )}
          </>
        )}

        {step !== 4 && failure && <div className="np-err"><b>Не выполнено:</b> {failure}</div>}
        {step === 4 && !profile && <div className="empty">Сборка профиля…</div>}
      </div>
    </div>
    </>
  )
}
