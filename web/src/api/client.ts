// Единственный источник данных клиента — HTTP API ядра (core/com).
// Других источников нет и быть не должно: данные приходят посчитанными,
// клиент их отображает (ADR-010, STEP-6 §3).
import type {
  AnswerReport,
  CoverageView,
  BottlenecksReport,
  GeneratedDocumentView,
  DocumentIssuesView,
  GatesView,
  GlobeView,
  GroundSuggestView,
  MaskScheduleView,
  ProtocolAdapterView,
  MaturityView,
  StaleResultRow,
  UnacceptedAiRow,
  TraceMatrixView,
  ValidationRow,
  VerificationMatrixFlatView,
  ComparisonView,
  ComponentSpecification,
  DemandLayersRequest,
  DemandMapView,
  PresetRow,
  ReferenceScenarioRow,
  RequirementCard,
  RequirementTreeView,
  SavedViewDoc,
  NeedRow,
  PromptPackage,
  ReadinessView,
  RiskRegisterView,
  ServiceRow,
  SpacecraftView,
  SystemOverview,
  UnitLabels,
  WizardStep,
  DocumentParseMap,
  DocumentHarvestView,
  DataRequestsView,
  MissionIntentDraftView,
  NormativeReadiness,
  NormativeCandidatesPacket,
  KnowledgeExportView,
  StatementPathView,
  LinkMappingView,
  ReviewChecklistView,
  PhaseWorkView,
  SectionProseInput,
  ResultsView,
  PhaseGanttView,
  PhaseFlowView,
  StakeholderCoverageView,
  CompositionTree,
  TraceGraphView,
  FunctionMatrixView,
  ExternalModelView,
} from './types'

import { withProject } from './project'

const BASE = '/api'

export interface OperationRow {
  code: string
  name: string
  executor: string
  gate: string | null
  required_status: string | null
  state: string
  objects: number
  screen?: string
  returned_to?: boolean
  docs?: string[]
  /** Шаблоны документов операции: переход открывает СВОЙ документ. */
  templates?: string[]
  /** МВП-П1: входы-предшественники — порядок работы только данными. */
  inputs?: string[]
}

export interface OperationsView {
  project: string
  phase: string
  next_gate: string | null
  operations: OperationRow[]
}

export interface GateIssuesView {
  gate: string
  ready: boolean
  next_gate: string | null
  issues: string[]
}

export interface GatePassResult {
  passed: boolean
  gate: string
  decision: string
  next_gate: string | null
}

export interface BatchProblemRow {
  index: number
  id: string | null
  path: string | null
  rule: string | null
  message: string
  /**
   * Как строка называлась в пакете инженера, если её id перебивался при
   * акцепте (занятый черновой id получает свежий). Снимать отметку и
   * показывать причину нужно по НЕМУ: нового имени в списке нет.
   */
  source_id?: string | null
}

export interface BatchReport {
  written: number
  problems: BatchProblemRow[]
}

export interface AiRunReport {
  call?: number
  model?: string
  proposed: number
  no_source: number
  shown: Array<{ item: Record<string, unknown> }>
  rework?: { proposed: number; rejected: number; rework: Array<{ item: Record<string, unknown>; issues: string[] }> }
  by_rule?: Record<string, number>
  failed?: boolean
  reason?: string
}

export interface AiJournalCall {
  pk: number
  at: string
  kind: string
  profile: string | null
  profile_version: string | null
  transport: string
  model: string | null
  tokens_in: number | null
  tokens_out: number | null
  cost_usd: string | null
  proposed: number
  filtered: number
  no_source: number
  accepted: number
  accepted_by: string | null
  failure: string | null
  prompt: string
  author: string
}

export interface AiJournal {
  totals: {
    calls: number
    proposed: number
    filtered: number
    no_source: number
    accepted: number
    tokens_in: number
    tokens_out: number
    cost_usd: string
  }
  calls: AiJournalCall[]
}

export interface PromoteBatchReport {
  promoted: string[]
  failed: Array<{ id: string; reason: string }>
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly path: string,
    /** Тело ответа как есть — сервер на отказ пачки (422) шлёт тот же BatchReport,
     * что и на успех (201), только written: 0. Разбор тела заново — через body. */
    readonly body: string,
  ) {
    super(`${path}: ${status} ${body}`)
  }
}

/**
 * Пачка (accept-batch, import/objects) отвечает 422 с тем же телом BatchReport,
 * что и успех 201 — только written: 0. Общий post() бросает ApiError и теряет
 * разобранное тело; вызывающий, которому нужен отчёт построчно (а не голая
 * строка), достаёт его отсюда вместо String(e).
 */
export function asBatchReport(e: unknown): BatchReport | null {
  if (!(e instanceof ApiError)) return null
  try {
    const parsed = JSON.parse(e.body) as unknown
    const r = parsed as Record<string, unknown>
    if (Array.isArray(r.problems)) return parsed as BatchReport
  } catch {
    // тело не JSON (сетевой отказ, HTML страница ошибки и т.п.) — не наш случай
  }
  return null
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(`${BASE}${withProject(path)}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    throw new ApiError(response.status, path, await response.text())
  }
  return (await response.json()) as T
}

/** Замена значения целиком (авторский текст раздела): тот же контур, что post. */
async function put<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(`${BASE}${withProject(path)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    throw new ApiError(response.status, path, await response.text())
  }
  return (await response.json()) as T
}

async function get<T>(path: string): Promise<T> {
  const response = await fetch(`${BASE}${withProject(path)}`, { headers: { Accept: 'application/json' } })
  if (!response.ok) {
    throw new ApiError(response.status, path, await response.text())
  }
  return (await response.json()) as T
}

export const api = {
  // ---------- блок D: спина процесса и пачки ----------
  /** Состояние операций фазы проекта (ADR-029): считает сервер. */
  operations: () => get<OperationsView>('/views/operations'),
  /** Предпросмотр незакрытого точки — тем же расчётом, что прохождение. */
  gateIssues: (gate: string) => get<GateIssuesView>(`/gates/${encodeURIComponent(gate)}/issues`),
  gatePass: (gate: string, author: string, rationale: string) =>
    post<GatePassResult>(`/gates/${encodeURIComponent(gate)}/pass`, { author, rationale }),
  gateReturn: (gate: string, author: string, reason: string, to: string[]) =>
    post<Record<string, unknown>>(`/gates/${encodeURIComponent(gate)}/return`, { author, reason, to }),
  gateReturnResolve: (author: string, note: string) =>
    post<Record<string, unknown>>('/gates/return/resolve', { author, note }),
  // ---------- П5: служба ИИ (профиль → промпт → вызов → фильтр → журнал) ----------
  /** Промпт собирает служба из профиля и состояния модели; клиент его читает. */
  /** Полки: объекты библиотечной области (круг 2 §3 — раздел «Библиотека»). */
  libraryObjects: (type: string) =>
    get<Array<{ id: string; title?: string }>>(
      `/objects?type=${encodeURIComponent(type)}&project=LIB`,
    ),
  /** Полки библиотеки (§4 Ш2): фрагменты с живыми счётчиками и манифестом. */
  libraryShelves: () =>
    get<Array<{
      id: string; name: string; shelf: string; mission_class_ref: string; summary: string
      counters: Record<string, number>
      origin: { project?: string; author?: string; date?: string }
      /** Взятие видно после перезахода — по связям «применяет». */
      applied?: { count: number; by_type: Record<string, number> }
    }>>('/library/shelves'),
  /** Ф-03: глоссарий — смысловые подсказки данными полки LIB. */
  glossary: () =>
    get<Array<{
      term: string; brief: string; sd_kind?: string
      extracts?: string; card_hint?: string; not_to_confuse?: string
    }>>('/library/glossary'),
  /** Ф-03: справочник единиц просмотром — как лежит в UR. */
  unitRegistry: () =>
    get<Array<{
      name: string; canon: string; conversion: string
      inputs?: Array<{ unit: string; factor?: number; offset?: number; note?: string }>
    }>>('/library/unit-registry'),
  /** Классы миссии (§4 Ш1) — полка Б4. */
  missionClasses: () =>
    get<Array<{ id: string; name: string; typical_constraints: Array<{ code?: string; text: string }> }>>(
      '/library/mission-classes',
    ),
  /** Применение фрагмента: экземпляры со связью «применяет» и родословной.
   * Идемпотентно: повторное нажатие возвращает existing, второй набор
   * не создаётся (круг 3 §1). */
  libraryApply: (fragment: string, author: string) =>
    post<{ created: Array<{ from: string; id: string }>; existing: string[] }>(
      `/library/fragments/${encodeURIComponent(fragment)}/apply`, { author },
    ),
  /** Отмена взятия — до конца пути; тронутое руками — отказ с перечнем. */
  libraryRevert: (fragment: string, author: string) =>
    post<{ removed: string[] }>(
      `/library/fragments/${encodeURIComponent(fragment)}/revert`, { author },
    ),
  /** Предпросмотр «Сохранить как шаблон»: резы поимённо до записи. */
  libraryPreview: (sel: { kind?: string; ids?: string[]; root?: string }) =>
    post<{
      objects: Array<{ id: string; type: string; name: string }>
      counters: Record<string, number>
      cuts: string[]
      value_candidates: Array<{ object: string; path: string; value: string }>
    }>('/library/fragments/preview', sel),
  /** Запись фрагмента — только с подтверждёнными резами. */
  librarySave: (body: {
    kind?: string; ids?: string[]; root?: string
    name: string; shelf: string; mission_class_ref?: string
    acknowledged_cuts: string[]; replacements?: Record<string, string[]>
    author: string
  }) => post<{ id: string; version: string }>('/library/fragments', body),
  /** Наполнение полки: объект в область библиотеки. */
  libraryPut: (type: string, doc: Record<string, unknown>, author: string) =>
    post<{ id: string; type: string }>('/library/objects', { type, doc, author }),
  /** Мастер-путь Ш2: исходные документы других проектов — библиотека текущего. */
  libraryDocs: () =>
    get<Array<{ id: string; project: string; name: string; kind: string; summary: string; has_text: boolean }>>(
      '/views/library/source-documents',
    ),
  /** Взятие из библиотеки: копия в текущий проект с провенансом imported. */
  libraryTake: (ids: string[], author: string) =>
    post<{ taken: Array<{ from: string; id: string }> }>('/views/library/take', { ids, author }),
  /** Мастер-путь Ш3: профиль службы из ограничений паспорта — собирает сервер. */
  startPathProfile: (author: string) =>
    post<{ id: string; version: string; name: string; prohibitions: number }>(
      '/views/start-path/profile', { author },
    ),
  /** Круг 2: файл исходного документа + карточка; текст извлекает сервер. */
  sdUpload: async (file: File, meta: {
    name: string; kind: string; org?: string; doc_date?: string; author: string; area?: string
  }) => {
    const q = new URLSearchParams({ filename: file.name, ...meta })
    const response = await fetch(withProject(`/api/sd-files?${q.toString()}`), {
      method: 'POST',
      headers: { 'Content-Type': 'application/octet-stream' },
      body: file,
    })
    const text = await response.text()
    if (!response.ok) throw new ApiError(response.status, '/sd-files', text)
    return JSON.parse(text) as { id: string; file: string; text_extracted: boolean }
  },
  /** Круг 2: файл карточки — обратно (ссылка скачивания). */
  sdFileUrl: (id: string) => withProject(`/api/sd-files/${encodeURIComponent(id)}`),
  /** Д1: карта разбора документа — структура, числа каноном, находки. */
  sdParse: (id: string) => get<DocumentParseMap>(`/sd-parse/${encodeURIComponent(id)}`),
  /** Д1: MD-канон — единственный носитель текста документа. */
  sdCanonUrl: (id: string) => withProject(`/api/sd-parse/${encodeURIComponent(id)}/canon`),
  /** Д1: переразбор (документы до Д1 и смена версии разборщика). */
  sdReparse: (id: string) => post<{ id: string; parsed: string }>(`/sd-parse/${encodeURIComponent(id)}`, {}),
  /** Ф-06: запросы данных — анкеты характеристик с состоянием заполнения. */
  dataRequests: () => get<DataRequestsView>('/views/data-requests'),
  /** Ф-06: анкеты полки — просмотром в «Справочниках» (правятся пачкой). */
  propertyForms: () =>
    get<Array<{
      id: string; name: string; role: string; note?: string
      fields: Array<{ key: string; name: string; unit?: string; required?: boolean; hint?: string }>
    }>>('/library/property-forms'),
  /** Ф-07: есть ли из чего собирать замысел — и по каким документам. */
  missionIntentReadiness: () =>
    get<{
      documents: number; parsed: number; harvested: number
      can_compose: boolean; why: string
      sources: Array<{ document: string; name: string; harvest: boolean }>
    }>('/views/mission-intent/readiness'),
  /** Ф-07: промпт сборки замысла — собирает система (закрытый контур). */
  missionIntentPrompt: () =>
    get<{ profile: string; kind: string; text: string }>('/views/mission-intent/prompt'),
  /** Ф-07: предложение замысла пакетом — проверяется схемой, в паспорт не пишется. */
  /** Ф-07 + живой канал: система САМА спрашивает службу и приносит замысел. */
  missionIntentCompose: (author?: string) =>
    post<{ call: number; model?: string; profile: string; draft: MissionIntentDraftView }>(
      '/views/mission-intent/compose', author ? { author } : {},
    ),
  missionIntentDraft: (raw: string) => post<MissionIntentDraftView>('/views/mission-intent/draft', { raw }),
  /** Ф-07: акцепт замысла — правкой паспорта, с якорями происхождения. */
  missionIntentAccept: (draft: MissionIntentDraftView, author: string) =>
    post<{ project: string; version: string; mission_intent: Record<string, unknown> }>(
      '/views/mission-intent/accept', { draft, author },
    ),
  /** Д3: поиск по материалам проекта — по канонам, с координатой блока. */
  documentSearch: (q: string) =>
    get<{
      query: string
      hits: number
      results: Array<{
        document: string; document_name: string; anchor: string
        section?: string; fragment: string; by: string
      }>
    }>(`/views/document-search?q=${encodeURIComponent(q)}`),
  /** Д2: промпт смыслового разбора — собирает система (правила + карточка + выжимка). */
  sdHarvestPrompt: (id: string) =>
    get<{
      document: string; profile: string; kind: string
      blocks: Array<{ source: string; title: string; text: string }>
      text: string
    }>(`/sd-parse/${encodeURIComponent(id)}/harvest/prompt`),
  /** Д2: приём урожая пакетом (закрытый контур, шаг Б2 ПМИ). */
  sdHarvestPut: (id: string, raw: string) =>
    post<{ document: string; items: number; summary: Record<string, number> }>(
      `/sd-parse/${encodeURIComponent(id)}/harvest`, { raw },
    ),
  /** Д2: урожай документа с адресами раскладки. */
  sdHarvest: (id: string) => get<DocumentHarvestView>(`/sd-parse/${encodeURIComponent(id)}/harvest`),
  /** Д2: акцепт урожая по адресам — дозаполнение приходит от инженера. */
  sdHarvestAccept: (id: string, selected: Array<{ index: number; filled?: Record<string, string> }>, author: string) =>
    post<{
      document: string
      created: Array<{ index: number; class: string; id: string; where: string }>
      refused: Array<{ index: number; class?: string; why: string }>
    }>(`/sd-parse/${encodeURIComponent(id)}/harvest/accept`, { selected, author }),
  /** В3: кто я — режим учёток, пользователь и его роли по проектам. */
  whoami: () =>
    get<{
      enabled: boolean
      /** stand — приёмочный стенд: учётки без паролей, переключение селектором */
      mode?: string
      user?: { login: string; display_name: string; roles: Record<string, string> }
      stand_users?: Array<{ login: string; display_name: string; roles: Record<string, string> }>
    }>(
      '/auth/whoami',
    ),
  /** Режим приёмочного стенда: вход учёткой без пароля (ORBITA_AUTH_MODE=stand на сервере). */
  standLogin: (login: string) => post<{ login: string; display_name: string }>('/auth/stand-login', { login }),
  login: (login: string, password: string) =>
    post<{ login: string; display_name: string }>('/auth/login', { login, password }),
  logout: () => post<{ ok: boolean }>('/auth/logout', {}),
  registerUser: (login: string, password: string, display_name: string) =>
    post<{ login: string }>('/auth/register', { login, password, display_name }),
  /** В3 §2.2: карта «строка автора → учётка» — история неприкосновенна. */
  authorMap: () => get<Record<string, string>>('/auth/author-map'),
  mapAuthor: (author: string, login: string) =>
    post<{ ok: boolean }>('/auth/author-map', { author, login }),
  rolesOf: (project: string) =>
    get<Record<string, string>>(`/auth/roles/${encodeURIComponent(project)}`),
  setRole: (project: string, login: string, role: string) =>
    post<{ ok: boolean }>('/auth/roles', { project, login, role }),
  /** ADR-046: граф трассировки — узлы и рёбра без координат, impact группами, путь — считает сервер. */
  traceGraph: (focus?: string, depth = 2, to?: string) => {
    const q = new URLSearchParams()
    if (focus) q.set('focus', focus)
    q.set('depth', String(depth))
    if (to) q.set('to', to)
    return get<TraceGraphView>(`/views/trace-graph?${q.toString()}`)
  },
  /** ADR-044: дерево состава по вхождениям, узлы КА и построения ×N — считает сервер. */
  compositionTree: () => get<CompositionTree>('/views/composition/tree'),
  /** ADR-044: из каких узлов собран контракт аппарата и чего не хватает. */
  spacecraftAssembly: (nodeId: string) =>
    get<{ spacecraft: Record<string, unknown>; problems: string[]; nodes: string[]; computed?: string[] }>(
      `/views/spacecraft/${nodeId}/assembly`,
    ),
  /** В2.1: свёртка бюджетов по вхождениям с кратностью — считает сервер. */
  compositionBudgets: () =>
    get<{ rows: Array<Record<string, unknown>>; totals: Record<string, { value: number; unit: string }> }>(
      '/views/composition/budgets',
    ),
  /** В2.2: стоимость и сроки по дереву работ — считает сервер. */
  wbsRollup: () =>
    get<{ elements: Array<Record<string, unknown>> }>('/views/wbs/rollup'),
  /** Печать (В1.4/О-8): файл отдаёт сервер; путь ссылки живёт здесь одним местом. */
  printUrl: (code: string, fmt: 'docx' | 'pdf', issue?: string) =>
    withProject(`/api/export/documents/${code}/print.${fmt}${issue ? `?issue=${encodeURIComponent(issue)}` : ''}`),
  /** Ответ несёт и blocks — атрибуцию источников для предпросмотра (О-4). */
  /** Печатный текст выпуска — вход для «обобщить в образец». */
  issueText: (code: string, issue: string) =>
    get<{ text: string; lines: string[] }>(`/export/documents/${code}/issues/${issue}/text`),
  /** Библиотека → «Результаты»: выпуски документов карточками с авторством. */
  results: () => get<ResultsView>('/views/results'),
  /** Связный текст раздела: вход собирает сервер из данных вставок раздела (шип 1). */
  sectionProseInput: (code: string, section: number) =>
    get<SectionProseInput>(`/export/documents/${code}/sections/${section}/prose-input`),
  /** В1.2: авторский текст раздела — правка инженера, принятие явное. */
  saveSectionText: (code: string, section: number, text: string, author: string) =>
    put<{ id: string; version: string; inserts_fingerprint: string }>(
      `/export/documents/${code}/sections/${section}/text`, { text, author },
    ),
  aiCompose: (kind: string, profile: string, statement: string) =>
    post<{
      profile: string; profile_version: string; transport: string
      require_source: boolean; prompt: string
      blocks?: Array<{ source: string; title: string; text: string }>
      /** Ф-05: состав промпта по источникам — со счётчиками и пустыми. */
      sources?: Array<{ key: string; title: string; count: number; empty: boolean; note?: string }>
    }>('/ai/compose', { kind, profile, statement }),
  /** Прямой вызов провайдера — основной транспорт. */
  aiAsk: (kind: string, profile: string, statement: string, author: string) =>
    post<AiRunReport>('/ai/ask', { kind, profile, statement, author }),
  /** Закрытый контур: ответ владельца файлом — тем же разбором и журналом. */
  aiSubmit: (kind: string, profile: string, statement: string, raw: string, author: string) =>
    post<AiRunReport>('/ai/submit', { kind, profile, statement, raw, author }),
  /** Б-01: заготовленный пакет — без вызова модели; вид из самого пакета,
   * журнал — «пакет». */
  aiPacket: (raw: string, author: string) =>
    post<AiRunReport & { kind: string; profile: string }>('/ai/packet', { raw, author }),
  /** Журнал вызовов: «сколько и почём». */
  aiJournal: () => get<AiJournal>('/ai/journal'),
  /** МВП-П1: назначение заданий по разрывам — пачкой, идемпотентно. */
  tasksAssign: (body: {
    gate: string
    gaps: Array<{ id: string; title: string; place?: string | null }>
    assignee: string
    due?: string
    note?: string
    author: string
  }) => post<{ created: string[]; existing: string[] }>('/tasks/assign', body),
  /** «Мои задания» — личный разрез готовности; без assignee — все. */
  myTasks: (assignee?: string) =>
    get<{
      tasks: Array<{
        id: string; gap_ref: string; gate: string; title: string; assignee: string
        due?: string; note?: string; state: 'active' | 'waiting' | 'done'
        waits_on?: string; place?: string; overdue?: boolean
      }>
      /** Круг 8: работы фазы, где я ответственный — «моё» не только задания. */
      works: Array<{
        id: string; kind: 'task' | 'step'; name: string; status: string
        task?: string; step_index?: number; gate?: string; gaps?: number
        next_step?: string; place?: string; done?: boolean
      }>
      counts: { active: number; overdue: number; waiting: number; works: number }
    }>(`/views/my-tasks${assignee ? `?assignee=${encodeURIComponent(assignee)}` : ''}`),
  /** Учётки поимённо — пикеру исполнителя. */
  authUsers: () => get<{ users: Array<{ login: string; display_name: string }> }>('/auth/users'),
  /** Акцепт пачкой с привязкой к вызову журнала. Занятые id пакета сервер
   * переназначает (TZ-MOD-007) и возвращает соответствие в remapped. */
  /**
   * Снятые правилом основания предложения — ПОД ОТВЕТСТВЕННОСТЬ инженера.
   * Правило требует явного решения человека; здесь оно и принимается: в
   * происхождение каждой неподкреплённой величины уходит имя и время.
   */
  acceptRework: (by: string, items: unknown[]) =>
    post<BatchReport & { signed_by?: string; note?: string; remapped?: Array<{ from: string; to: string }> }>(
      '/ai/accept-rework', { author: by, items },
    ),
  acceptBatchOfCall: (
    call: number | null, llm: string, by: string, items: unknown[],
    /** Г-01: подтверждённое инженером сопоставление чужих ссылок. */
    linkMapping?: Record<string, string>,
  ) =>
    post<BatchReport & { remapped?: Array<{ from: string; to: string }> }>(
      '/ai/accept-batch',
      { call, llm, by, items, ...(linkMapping ? { link_mapping: linkMapping } : {}) },
    ),
  /** Дозаполнение: применить частичные правки к существующим требованиям. */
  enrichApply: (call: number | null, by: string, items: unknown[]) =>
    post<BatchReport & { demoted?: string[] }>('/ai/enrich-apply', { call, by, items }),

  /** Блок E: акцепт предложений пачкой — порядок разрешает сервер. */
  acceptBatch: (packageId: string, llm: string, by: string, items: unknown[]) =>
    post<BatchReport>('/ai/accept-batch', { package_id: packageId, llm, by, items }),
  /** Массовое действие реестра: перевод статуса пачкой, отказы поимённо. */
  promoteBatch: (ids: string[], status: string, author: string) =>
    post<PromoteBatchReport>('/objects/promote-batch', { ids, status, author }),
  /** Загрузка пачкой (ADR-024): проверка до записи, всё или ничего. */
  importObjects: (payload: unknown) => post<BatchReport>('/import/objects', payload),
  /** Выгрузка проекта тем же форматом — для ссылки скачивания. */
  exportObjectsUrl: () => `/api${'/export/objects'}`,

  /** О-11: готовность точки — агрегаты группами, tailoring неприменимости. */
  gateReadiness: (gate?: string) =>
    get<{
      gate: string; label: string; due?: string
      open_total: number; blocking_open: number; total: number; na_total: number
      groups: Array<{
        key: string; title: string; open: number
        checks: Array<{
          id: string; title: string; state: 'open' | 'closed' | 'na'
          blocking: boolean; note: string; place?: string
          na_rationale?: string; na_author?: string; na_at?: string
        }>
      }>
    }>(`/views/gate-readiness${gate ? `?gate=${encodeURIComponent(gate)}` : ''}`),
  gateReadinessNa: (check: string, rationale: string, author: string) =>
    post<{ ok: boolean }>('/views/gate-readiness/na', { check, rationale, author }),
  gateReadinessNaRemove: (check: string, author: string) =>
    post<{ ok: boolean }>('/views/gate-readiness/na', { check, remove: true, author }),
  /** О-9: портфель одним запросом — сервер собрал и отсортировал. */
  portfolio: () =>
    get<{ projects: Array<{
      id: string; name: string; phase: string; owner: string
      gate: { name: string; label: string; open_count?: number } | null
      return: { reason: string } | null
      start_path: { status: string; step: number } | null
      last_activity: { at: string; author?: string; what?: string; service?: boolean } | null
    }> }>('/views/portfolio'),
  requirementTree: () => get<RequirementTreeView>('/views/requirement-tree'),
  /** Т-1: сохранённые виды реестра — сервер фильтрует личные по учётке. */
  reqViews: () => get<{ views: SavedViewDoc[] }>('/views/req-views'),
  saveReqView: (doc: SavedViewDoc, author: string) =>
    post<{ id: string }>('/views/req-views', { author, doc }),
  /** Матрицы живут на экране требований — там принимается решение (шаг 16 §2.4). */
  traceMatrix: () => get<TraceMatrixView>('/reports/trace-matrix'),
  /** ADR-048: внешняя модель и обновление снимков из адаптера (только чтение модели). */
  externalModel: () => get<ExternalModelView>('/views/external-model'),
  capellaRefresh: () => post<{ created: number; updated: number; model_id: string }>('/library/capella/refresh', {}),
  /** ADR-047: функции × узлы — считает сервер. */
  functionMatrix: () => get<FunctionMatrixView>('/reports/function-matrix'),
  verificationMatrix: () => get<VerificationMatrixFlatView>('/reports/verification-matrix'),
  validationMatrix: () => get<ValidationRow[]>('/reports/validation-matrix'),
  requirementCard: (id: string) => get<RequirementCard>(`/views/requirements/${id}`),
  componentSpecification: (id: string) => get<ComponentSpecification>(`/views/components/${id}`),
  unitLabels: () => get<UnitLabels>('/unit-labels'),
  systemOverview: () => get<SystemOverview>('/views/system'),
  /** Вариант = сценарий с выполненным прогоном; оси — из фактических (§3.5). */
  comparison: (axes?: string[]) =>
    get<ComparisonView>(
      '/views/comparison' +
        (axes && axes.length > 0 ? `?axes=${axes.map(encodeURIComponent).join(',')}` : ''),
    ),
  /** Прогон потоков (Монте-Карло) от хранимых входов сценария; долгий. */
  flowsRun: (scenario: string) =>
    post<{ result_pk: number; runs: number; passes: number; service_passes: number; populations: number; kpi: Record<string, unknown> }>(
      '/views/flows/run', { scenario },
    ),
  /** Устаревшие результаты: пометка живёт на экране сравнения (шаг 16 §2.4). */
  staleResults: () => get<StaleResultRow[]>('/reports/stale-results'),
  /** Зрелость пакета к точке — основная таблица экрана готовности. */
  maturity: (gate: string) => get<MaturityView>(`/reports/maturity?gate=${gate}`),
  /** Требования, чей источник моложе их самих, — перечень к рассмотрению. */
  reviewCandidates: () => get<string[]>('/reports/review-candidates'),
  /** Неакцептованные предложения ИИ — список на экране предложения (TZ-AI). */
  unacceptedAi: () => get<UnacceptedAiRow[]>('/reports/unaccepted-ai'),
  /**
   * Глобус от модели проекта (шаг 16 §2.3): конфигурация — из хранимой
   * группировки по ссылке сценария, зашитой строки параметров больше нет.
   * Траектории считает пропагатор на сервере.
   */
  globe: (scenario: string) => get<GlobeView>(`/views/globe?scenario=${encodeURIComponent(scenario)}`),
  /**
   * Карта покрытия от ХРАНИМЫХ объектов по ссылкам сценария (шаг 16 §2.2).
   * Сценарий обязателен: умолчания нет, его отсутствие — 400 с объяснением.
   */
  /** МВП-М2: сравнение построений — метрики группами из интеграла §5. */
  constellationCompare: (body: {
    scenario: string
    variants: string[]
    axes?: string[]
    thresholds?: Array<{ metric: string; value: number; label?: string }>
  }) => post<import('./types').ConstellationCompareView>('/views/constellation-compare', body),
  /** МВП-М2 §1: смена рабочего варианта — явным действием с основанием. */
  setWorkingConstellation: (scenario: string, constellation: string, author: string) =>
    post<{ working: string }>('/scenarios/working-constellation', { scenario, constellation, author }),
  /** §6 МВП-М1: трассы подгрупп (виток) — сверка рисунка глазами. */
  groundTracks: (scenario: string) =>
    get<import('./types').GroundTracksView>(`/views/ground-tracks?scenario=${encodeURIComponent(scenario)}`),
  /** §6: маски зон приёма и сброса — слой карты. */
  geoMasks: (scenario: string) =>
    get<import('./types').GeoMasksView>(`/views/geo-masks?scenario=${encodeURIComponent(scenario)}`),
  /** §3: сводка построения для формы — сервер считает, клиент показывает. */
  calcConstellationSummary: (subgroups: unknown[]) =>
    post<{
      total_sats: number
      formula: string
      warnings: string[]
      subgroups: Array<{ index: number; sats: number; computed_inclination_deg?: number }>
    }>('/calc/constellation-summary', { subgroups }),
  coverage: (scenario: string, horizon: 'orbit' | 'day' | 'run') =>
    get<CoverageView>(`/views/coverage?scenario=${encodeURIComponent(scenario)}&horizon=${horizon}`),
  needs: () => get<NeedRow[]>('/views/needs'),
  services: () => get<ServiceRow[]>('/views/services'),
  readiness: (gate: string) => get<ReadinessView>(`/views/readiness?gate=${gate}`),
  wizard: () => get<WizardStep[]>('/views/wizard'),
  risks: () => get<RiskRegisterView>('/views/risks'),
  /** Библиотека референсных сценариев — слой 3 карты спроса (TZ-USR-006). */
  demandLibrary: () => get<ReferenceScenarioRow[]>('/views/demand/library'),
  /** Ячейки, веса и пик карты считает сервер: клиент отдаёт только слои. */
  demand: (layers: DemandLayersRequest) => post<DemandMapView>('/views/demand', layers),
  /** Хранимая карта спроса (ADR-021): ячейки и веса из сохранённого документа. */
  demandStored: (id: string) => get<DemandMapView>(`/views/demand/${id}`),
  platformPresets: () => get<PresetRow[]>('/views/spacecraft/presets'),
  /** Циклограмма из масок (TZ-KA-009): подстановка в модель — решение инженера. */
  maskSchedule: () => get<MaskScheduleView>('/views/spacecraft/mask-schedule'),
  /** Параметры канала отдаёт только адаптер (TZ-NET-001, TZ-NET-006). */
  protocolAdapter: () => get<ProtocolAdapterView>('/protocol-adapter'),
  /** Узкие места из сохранённого прогона потоков (шаг 16 §2.4). */
  bottlenecks: (scenario: string) =>
    get<BottlenecksReport>(`/views/bottlenecks?scenario=${encodeURIComponent(scenario)}`),
  /** Проверка отображения перед выгрузкой ReqIF (ADR-023). */
  reqifCheck: () =>
    get<{ mapping_issues: string[]; flattened: string[] }>('/export/reqif/check'),
  /** Адреса выгрузок (TZ-OUT-005): маршруты живут в слое API, не в разметке. */
  exportUrls: {
    reqif: `${BASE}/export/reqif`,
    /** ADR-049: StrictDoc-канал — .sdoc по грамматике Орбиты и ReqIF от StrictDoc. */
    sdoc: `${BASE}/export/sdoc`,
    sdocGrammar: `${BASE}/export/sdoc?grammar=1`,
    sdocReqif: `${BASE}/export/sdoc/reqif`,
    csv: `${BASE}/export/exchange?format=csv`,
    exchangeJson: `${BASE}/export/exchange?format=json`,
  },
  /** ADR-049: снимок базирования .sdoc в каталоге файлов проекта. */
  sdocBaseline: () => post<{ dir: string; sdoc: string; sgra: string; at: string }>('/export/sdoc/baseline', {}),
  /** ADR-049: импорт .sdoc — кандидаты с чужими полями в foreign_attributes; в модель канал не пишет. */
  importSdoc: (sdoc: string) => post<{ drafts: unknown[]; count: number }>('/import/sdoc', { sdoc }),
  /** Импорт ReqIF (ADR-024): файл разбирает служба обмена, назад — черновики. */
  importReqif: async (xml: string) => {
    const response = await fetch(`${BASE}/import/reqif`, { method: 'POST', body: xml })
    if (!response.ok) throw new ApiError(response.status, '/import/reqif', await response.text())
    return (await response.json()) as { drafts: unknown[]; source_title: string; relations: number }
  },
  /** Контрольные точки: из хранимого проекта, без него — из реестра ворот. */
  gates: () => get<GatesView>('/views/gates'),
  /** Выпуск документа: слепок текущей генерации становится объектом (C5). */
  issueDocument: (code: string, issuedAt: string, author: string) =>
    post<Record<string, unknown>>(`/export/documents/${code}/issue`, {
      issued_at: issuedAt,
      author,
    }),
  documentIssues: (code: string) => get<DocumentIssuesView>(`/export/documents/${code}/issues`),
  /** Перечень шаблонов документов БП-PA и сборка документа из модели. */
  documentTemplates: () =>
    get<Array<{ code: string; title: string; source: string }>>('/export/documents'),
  document: (code: string) => get<GeneratedDocumentView>(`/export/documents/${code}`),
  /** Подбор станций поверх ХРАНИМЫХ ручных: они не переписываются (шаг 12.1). */
  groundSuggest: (request: {
    candidates: Array<{ id: string; name: string; lat_deg: number; lon_deg: number }>
    k: number
    inclination_deg?: number
    alt_km?: number
  }) => post<GroundSuggestView>('/ground/suggest', request),
  /** Импорт записи каталога устройств — по одной, по действию инженера (ADR-024). */
  importTerminalProfile: (request: {
    source: string
    dataset_version: string
    retrieved_at: string
    item_ref?: string
    device: Record<string, unknown>
    profile: Record<string, unknown>
  }) => post<Record<string, unknown>>('/import/terminal-profile', request),
  /** Пакет передачи (TZ-OUT-006): сценарий обязателен. */
  transferPackage: async (scenario: string) => {
    const response = await fetch(`${BASE}/export/package?scenario=${encodeURIComponent(scenario)}`, {
      headers: { Accept: 'application/json' },
    })
    if (!response.ok) throw new ApiError(response.status, '/export/package', await response.text())
    return response.text()
  },
  /**
   * Бюджеты ХРАНИМОЙ модели аппарата. Ведомость масс и циклограмма — часть
   * модели (CR-006, CR-007), а не состояние экрана, поэтому телом запроса
   * они не передаются: экран задаёт только условия оценки.
   */
  spacecraftStored: (id: string, conditions: Record<string, number> = {}) => {
    const query = new URLSearchParams(
      Object.entries(conditions).map(([k, v]) => [k, String(v)]),
    ).toString()
    return get<SpacecraftView>(`/views/spacecraft/${id}${query ? `?${query}` : ''}`)
  },
  /** Пакет для копирования во внешний интерфейс LLM: генерации в системе нет. */
  buildPackage: (kind: string, context: unknown, task: string) =>
    post<PromptPackage>('/ai/packages', { kind, context, task }),
  /** Разбор ответа модели и структурный фильтр — на сервере. */
  submitAnswer: (kind: string, context: unknown, task: string, raw: string) =>
    post<AnswerReport>('/ai/answers', { kind, context, task, raw }),
  /**
   * Акцепт: применяются ТОЛЬКО перечисленные поля одного предложения.
   * Массового акцепта нет намеренно (TZ-AI-004): «улучшить тысячу требований»
   * одной кнопкой — обход управления конфигурацией.
   */
  acceptProposal: (request: {
    target_id: string
    proposal: unknown
    selected: string[]
    package_id: string
    llm: string
    by: string
  }) => post<Record<string, unknown>>('/ai/accept', request),

  /** Ф-14: кто брал объект полки — нитка «взято/брали» в обратную сторону. */
  libraryUsage: (id: string) =>
    get<{
      id: string; takers: number; note: string
      projects: Array<{ project: string; name: string; objects: number; ids: string[] }>
    }>(`/views/library/usage?id=${encodeURIComponent(id)}`),
  /** Носители, названные в нуждах словами, — объектами проекта: имя из
   *  документа, роль называет инженер. */
  stakeholdersFromNeeds: (carriers: Array<{ name: string; role: string }>, author: string) =>
    post<{ count: number; created: Array<{ id: string; name: string; role: string; needs: number }> }>(
      '/views/stakeholders/from-needs', { carriers, author },
    ),
  /** Ф-14: обобщить отмеченных одним движением — с отчётом поимённо. */
  generalizeBatch: (ids: string[], author: string) =>
    post<{
      created: number; summary: string
      profiles: Array<{ from: string; profile: string; name: string }>
      skipped: Array<{ id: string; why: string }>
    }>('/views/stakeholders/generalize-batch', { ids, author }),
  /** Ф-14: обобщить проектного стейкхолдера в профиль полки А2 — со следом. */
  generalizeStakeholder: (id: string, author: string) =>
    post<{ profile: string; from: string; project: string; note: string }>(
      `/views/stakeholders/${encodeURIComponent(id)}/generalize`, { author },
    ),
  /** Ф-15: объём последствий правки справочника — считает сервер, до сохранения. */
  registryImpact: (type: 'unit_registry' | 'glossary') =>
    get<{ type: string; documents: number; parsed: number; harvested: number; warning: string }>(
      `/views/registry-impact?type=${type}`,
    ),
  /** Ф-13: матрица «стейкхолдер × нужды» — состояния считает сервер. */
  stakeholderCoverage: () => get<StakeholderCoverageView>('/views/stakeholder-coverage'),
  /** «Работа фазы»: задачи регламента со статусами, шагами и окнами — считает сервер. */
  phaseWork: () => get<PhaseWorkView>('/views/phase-work'),
  /** Круг 4: схема потока фазы — узлы, рёбра-артефакты и точки с готовностью. */
  phaseFlow: () => get<PhaseFlowView>('/views/phase-flow'),
  /** Круг 5: строки полотна Ганта — план руководителя либо расчётная сетка. */
  phaseGantt: (collapse: string[] = []) =>
    get<PhaseGanttView>('/views/phase-gantt' + (collapse.length ? `?collapse=${collapse.join(',')}` : '')),
  /** План задачи: даты ставит руководитель — перетаскиванием либо полями. */
  /** Круг 8: ответственный за работу — назначает руководитель проекта. */
  phaseWorkAssign: (
    body: { task: string; who?: string; clear?: boolean; author: string },
    collapse: string[] = [],
  ) => post<PhaseGanttView>(
    '/views/phase-work/assignee' + (collapse.length ? `?collapse=${collapse.join(',')}` : ''), body,
  ),
  phaseWorkPlan: (
    body: {
      task: string; start?: string; end?: string
      /** Длительность числом — тот же план другими руками: конец = старт + N. */
      duration_days?: number
      clear?: boolean; author: string
    },
    collapse: string[] = [],
  ) => post<PhaseGanttView>(
    '/views/phase-work/plan' + (collapse.length ? `?collapse=${collapse.join(',')}` : ''), body,
  ),
  /** Инспекция обзора: чек-листы полки с состоянием пунктов. */
  reviewChecklist: (gate?: string) =>
    get<ReviewChecklistView>(`/views/review-checklist${gate ? `?gate=${encodeURIComponent(gate)}` : ''}`),
  /** Отметка пункта инспекции — с автором и временем; повторный клик снимает. */
  reviewCheck: (checklist: string, item: string, author: string, checked: boolean, note?: string) =>
    post<ReviewChecklistView>('/views/review-checklist/check', {
      checklist, item, author, uncheck: checked, ...(note ? { note } : {}),
    }),
  /** Г-01: чужие ссылки пакета — разбор и предложение замены по смыслу. */
  linkMapping: (items: unknown[]) => post<LinkMappingView>('/views/link-mapping', { items }),
  /** Ф-12: путь постановки — состояние цепочки и следующий шаг, считает сервер. */
  statementPath: () => get<StatementPathView>('/views/statement-path'),
  /** Профиль под вид операции: подбирается системой, а не инженером. */
  profileForKind: (kind: string) =>
    get<{ profile: string; kind: string; ensured: boolean }>(
      `/views/ai/profile-for?kind=${encodeURIComponent(kind)}`,
    ),
  /** Ф-10: состав выгрузки знаний и её отпечаток — до скачивания видно, что уйдёт. */
  knowledgeExport: (parts?: string[]) =>
    get<KnowledgeExportView>(
      `/views/knowledge-export${parts && parts.length ? `?parts=${encodeURIComponent(parts.join(','))}` : ''}`,
    ),
  /** Ф-10: ссылка на сам пакет — архив MD-файлов с отпечатком в шапке каждого. */
  knowledgeBundleUrl: (parts: string[]) =>
    withProject(`/api/views/knowledge-export/bundle.zip?parts=${encodeURIComponent(parts.join(','))}`),

  /** Ф-09: что полка знает — нормативы своими пунктами и разобранными документами. */
  normativeReadiness: () => get<NormativeReadiness>('/views/normative-candidates/readiness'),
  /** Ф-09: промпт «норматив → кандидаты» — собирает служба, не клиент. */
  normativePrompt: () =>
    get<{ profile: string; kind: string; text: string }>('/views/normative-candidates/prompt'),
  /** Ф-09: ворота пакета кандидатов — до модели дело ещё не дошло. */
  normativeDraft: (raw: string) =>
    post<{ kind: string; items: number; packet: NormativeCandidatesPacket; knowledge_warning?: string }>(
      '/views/normative-candidates/draft', { raw },
    ),
  /** Ф-09: акцепт выбранных кандидатов — требования объектами, ограничения Р-кодами. */
  normativeAccept: (packet: NormativeCandidatesPacket, selected: number[], author: string) =>
    post<{
      accepted: number
      requirements: Array<{ id: string; statement: string; basis: string }>
      constraints: Array<{ code: string; text: string; source: string }>
    }>('/views/normative-candidates/accept', { packet, selected, author }),
}
