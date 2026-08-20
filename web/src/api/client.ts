// Единственный источник данных клиента — HTTP API ядра (core/com).
// Других источников нет и быть не должно: данные приходят посчитанными,
// клиент их отображает (ADR-010, STEP-6 §3).
import type {
  ComparisonView,
  ComponentSpecification,
  RequirementCard,
  RequirementTreeView,
  SystemOverview,
  UnitLabels,
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
}
