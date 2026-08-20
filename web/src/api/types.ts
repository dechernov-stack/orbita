// Формы ответов API ядра (core/out/ScreenViews.kt).
//
// Это ЗЕРКАЛО серверных структур, а не отдельная модель: клиент ничего не
// достраивает и не пересчитывает. Если поля здесь не хватает — его добавляют
// на сервере, а не вычисляют на месте (STEP-6 §3.2, ловушка 5).

/** Условие требования: оператор, число, код единицы и готовая строка. */
export interface ConditionView {
  name: string | null
  operator: string | null
  value: number | null
  valueMax: number | null
  tolerance: number | null
  /** Код СИ — то, что хранится в модели. */
  unit: string | null
  /** Подпись для отображения; подставлена сервером. */
  unitLabel: string | null
  /** Готовая строка условия, напр. «≤ 60 кг». */
  rendered: string | null
}

export interface BudgetSegment {
  label: string
  value: number
  reserve: boolean
}

/** Полоса бюджета, посчитанная сервером: клиент её только рисует. */
export interface BudgetBar {
  segments: BudgetSegment[]
  used: number
  limit: number
  remaining: number
  overrun: boolean
  overrunValue: number | null
  /** Доля заполнения в процентах — посчитана сервером, клиент её не делит. */
  fillPercent: number
}

export interface RequirementRow {
  id: string
  depth: number
  hasChildren: boolean
  statement: string
  category: string | null
  status: string
  condition: ConditionView | null
  budget: BudgetBar | null
  budgetOverrun: boolean
  verificationState: string
  method: string | null
  approach: string | null
  planIssues: string[]
}

export interface RequirementTreeView {
  roots: string[]
  children: Record<string, string[]>
  rows: RequirementRow[]
}

export interface EventView {
  id: string
  method: string | null
  kind: string | null
  phase: string | null
  level: string | null
  closes: boolean
  status: string | null
  approach: string | null
  means: string | null
  evidenceRef: string | null
  evidenceStale: boolean
  issues: string[]
}

export interface RequirementCard {
  row: RequirementRow
  successCriterion: string | null
  sources: string[]
  allocatedTo: string[]
  events: EventView[]
}

export interface SpecificationRow {
  id: string
  statement: string
  condition: ConditionView | null
  source: string | null
  derivationKind: string | null
  verificationState: string
  eventsDone: number
  eventsTotal: number
  status: string
}

export interface ComponentSpecification {
  componentId: string
  rows: SpecificationRow[]
  budgets: Record<string, BudgetBar>
}

/** Подписи единиц: подстановка на стороне представления, коды СИ в модели. */
export type UnitLabels = Record<string, string>

// ---------- экран 7: сравнение вариантов ----------

export interface RadarOption {
  name: string
  values: Record<string, number>
}

export interface RadarSeriesEntry {
  name: string
  values: number[]
}

/** Диаграмма несёт состав набора, по которому нормирована: без него её сравнят с чужой. */
export interface RadarChart {
  axes: string[]
  series: RadarSeriesEntry[]
  normalizedOver: string[]
}

export interface ComparisonView {
  options: RadarOption[]
  radar: RadarChart
  paretoFront: string[]
  axes: string[]
}

// ---------- экран 12: система в целом ----------

export interface RiskCell {
  probability: number
  impact: number
  /** Критичность клетки посчитана сервером: раскраска — следствие правила. */
  criticality: string
  risks: string[]
}

export interface RegisterSummary {
  total: number
  active: number
  distribution: Record<string, number>
  escalate: string[]
  closedRetained: string[]
}

export interface SystemOverview {
  requirements: number
  components: number
  verification: Record<string, number>
  budgets: Record<string, BudgetBar>
  budgetsOverrun: string[]
  riskSummary: RegisterSummary
  riskMatrix: RiskCell[]
  problems: string[]
}
