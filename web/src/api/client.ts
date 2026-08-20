// Единственный источник данных клиента — HTTP API ядра (core/com).
// Других источников нет и быть не должно: данные приходят посчитанными,
// клиент их отображает (ADR-010, STEP-6 §3).
import type {
  AnswerReport,
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

const BASE = '/api'

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
  const response = await fetch(`${BASE}${path}`, {
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
  const response = await fetch(`${BASE}${path}`, { headers: { Accept: 'application/json' } })
  if (!response.ok) {
    throw new ApiError(response.status, path, await response.text())
  }
  return (await response.json()) as T
}

export const api = {
  requirementTree: () => get<RequirementTreeView>('/views/requirement-tree'),
  requirementCard: (id: string) => get<RequirementCard>(`/views/requirements/${id}`),
  componentSpecification: (id: string) => get<ComponentSpecification>(`/views/components/${id}`),
  unitLabels: () => get<UnitLabels>('/unit-labels'),
  systemOverview: () => get<SystemOverview>('/views/system'),
  comparison: () => get<ComparisonView>('/views/comparison'),
  /** CZML-поток глобуса: траектории считает пропагатор на сервере. */
  globe: (query = 'total=8&planes=2&duration_s=5400') =>
    get<unknown[]>(`/views/globe?${query}`),
  needs: () => get<NeedRow[]>('/views/needs'),
  services: () => get<ServiceRow[]>('/views/services'),
  readiness: (gate: string) => get<ReadinessView>(`/views/readiness?gate=${gate}`),
  wizard: () => get<WizardStep[]>('/views/wizard'),
  risks: () => get<RiskRegisterView>('/views/risks'),
  /** Библиотека референсных сценариев — слой 3 карты спроса (TZ-USR-006). */
  demandLibrary: () => get<ReferenceScenarioRow[]>('/views/demand/library'),
  /** Ячейки, веса и пик карты считает сервер: клиент отдаёт только слои. */
  demand: (layers: DemandLayersRequest) => post<DemandMapView>('/views/demand', layers),
  platformPresets: () => get<PresetRow[]>('/views/spacecraft/presets'),
  /** Бюджеты аппарата: масса, энергетика, линии, маяк, TPM — всё с сервера. */
  spacecraft: (request: unknown) => post<SpacecraftView>('/views/spacecraft', request),
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
