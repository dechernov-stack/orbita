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
  NeedRow,
  PromptPackage,
  ReadinessView,
  RiskRegisterView,
  ServiceRow,
  SpacecraftView,
  SystemOverview,
  UnitLabels,
  WizardStep,
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
}

export interface BatchReport {
  written: number
  problems: BatchProblemRow[]
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly path: string,
    message: string,
  ) {
    super(`${path}: ${status} ${message}`)
  }
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
  /** Загрузка пачкой (ADR-024): проверка до записи, всё или ничего. */
  importObjects: (payload: unknown) => post<BatchReport>('/import/objects', payload),
  /** Выгрузка проекта тем же форматом — для ссылки скачивания. */
  exportObjectsUrl: () => `/api${'/export/objects'}`,

  requirementTree: () => get<RequirementTreeView>('/views/requirement-tree'),
  /** Матрицы живут на экране требований — там принимается решение (шаг 16 §2.4). */
  traceMatrix: () => get<TraceMatrixView>('/reports/trace-matrix'),
  verificationMatrix: () => get<VerificationMatrixFlatView>('/reports/verification-matrix'),
  validationMatrix: () => get<ValidationRow[]>('/reports/validation-matrix'),
  requirementCard: (id: string) => get<RequirementCard>(`/views/requirements/${id}`),
  componentSpecification: (id: string) => get<ComponentSpecification>(`/views/components/${id}`),
  unitLabels: () => get<UnitLabels>('/unit-labels'),
  systemOverview: () => get<SystemOverview>('/views/system'),
  /** Сценарий обязателен (§3.2); оси — из фактически имеющихся (§3.5). */
  comparison: (scenario: string, axes?: string[]) =>
    get<ComparisonView>(
      `/views/comparison?scenario=${encodeURIComponent(scenario)}` +
        (axes && axes.length > 0 ? `&axes=${axes.map(encodeURIComponent).join(',')}` : ''),
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
    csv: `${BASE}/export/exchange?format=csv`,
    exchangeJson: `${BASE}/export/exchange?format=json`,
  },
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
}
