// Клиент API v2. Все решения принимает сервер: здесь только вызовы и типы.
// Ни одного вычисления состояния — состояние сцен считает движок.

export type SceneState = 'locked' | 'open' | 'done'

export interface Step {
  title: string
  place: string
  hint: string
  done: boolean
}

/** Условие сцены: заголовок для человека, состояние и причина отказа. */
export interface Condition {
  title: string
  check: string
  passed: boolean
  why: string | null
}

export interface Scene {
  key: string
  title: string
  order: number
  role: string
  question: string
  state: SceneState
  /** Чего не хватает — словами, с именами объектов. */
  blockers: string[]
  steps: Step[]
  /** Условия входа и выхода целиком: панель показывает и ✓, и ☐. */
  entry: Condition[]
  exit: Condition[]
  /** Что сцена даёт — для нити потока. */
  output: string
  /** Кто ждёт эту сцену: сцены и точки. */
  awaited_by: string[]
  /** Входные потоки процесса ЖЦ — данными полки. */
  input_flows: string[]
  /** Окно плана работ фазы; нет — «план не задан». */
  window?: { start: string; end: string }
}

export interface Gate {
  key: string
  title: string
  order: number
  planned_date: string | null
  passed: boolean
  blocking: string[]
}

export interface Phase {
  project: string
  standard: string
  phase: string
  current_scene: string | null
  scenes: Scene[]
  gates: Gate[]
}

/** Задание — адресованный разрыв сцены, а не отдельная сущность. */
export interface TaskRow {
  scene: string
  scene_title: string
  role: string
  what: string
  /** Сцена ещё закрыта: работать нельзя, ждём предыдущую. */
  waiting: boolean
}

/** Факт — атом знания с якорем и меткой достоверности. */
export interface FactRow {
  id: string
  subject: string
  predicate: string
  value: string
  unit: string | null
  anchor: string | null
  /** И — наш документ · В — внешний, проверенный на дату · П — допущение. */
  mark: 'И' | 'В' | 'П'
  material: string
}

export interface PlanAction {
  kind: string
  title: string
  /** Что появится в модели — словами, до нажатия. */
  effect: string
}

export interface IntakeResult {
  task: string
  material: string
  intent: string
  note: string
  facts: FactRow[]
  plan: PlanAction[]
}

export interface CoverageNeed {
  code: string
  statement: string
  owner: string | null
  covered: boolean
  gap: string | null
  goals: string[]
  services: string[]
}

export interface CoverageMatrix {
  total: number
  covered: number
  summary: string
  needs: CoverageNeed[]
  stakeholders_without_needs: string[]
}

/** Помета линта: правило, что не так и почему это важно. */
export interface LintNote {
  rule: string
  what: string
  why: string
}

/** Строка реестра требований: шесть колонок наружу, остальное — в карточке. */
export interface RequirementRow {
  id: string
  code: string
  level: string
  title: string
  statement: string
  category: string
  ears: string
  carrier: string | null
  carrier_kind: string | null
  measure: string | null
  verification_method: string | null
  status: string
  version: number
  template_ref: string | null
  applicability: string | null
  /** Версия выше зафиксированной снимком — «изменено после утверждения». */
  after_baseline_changed: boolean
  sources: string[]
  notes: LintNote[]
}

/** Помеха базированию: объект, правило и что именно не так. */
export interface Blocker {
  code: string
  rule: string
  what: string
}

export interface BaselineItem {
  ref: string
  code: string
  kind: string
  version: number
  firmness: 'firm' | 'conditional'
  conditional_on: string | null
  why: string | null
}

export interface BaselineRow {
  name: string
  kind: string
  gate: string
  by: string
  at: string
  firm: number
  items: BaselineItem[]
}

export interface SuspectRow {
  link: string
  type: string
  from: string
  to: string
  why: string
}

export interface ComponentRow {
  id: string
  code: string
  name: string
  level: number
  nature: 'node' | 'behaviour'
  kind: string
  parent: string | null
  external: boolean
  version: number
}

export interface FacetLine {
  what: string
  ref: string | null
}

/** Грань карточки компонента; пустая говорит, чего ждут и к какой точке. */
export interface Facet {
  key: string
  title: string
  required_to: string | null
  expected: string | null
  lines: FacetLine[]
}

export interface ComponentCard {
  code: string
  name: string
  nature: string
  level: number
  gate: string
  facets: Facet[]
  gaps: { facet: string; gate: string; what: string }[]
}

export interface ArchLayer {
  key: string
  title: string
  lines: FacetLine[]
}

/** Базовый вариант построения: выбор с обоснованием и отклонёнными. */
export interface ConceptRow {
  code: string
  variant: string
  rationale: string
  decided_by: string
  at: string
  rejected: { variant: string; reason: string }[]
}

export interface ParameterRow {
  key: string
  name: string
  target: string
  origin: string
  maturity_class: string
  reserve_percent: number
  uncertainty: number
  required_to: string
  version: number
  measure: unknown
}

export interface EntityRow {
  id: string
  code: string
  status: string
  doc: Record<string, unknown>
  owned_by?: string[]
  covered_by?: string[]
}

async function вызов<T>(путь: string, настройки?: RequestInit): Promise<T> {
  const ответ = await fetch(`/api/v2${путь}`, {
    headers: { 'Content-Type': 'application/json' },
    ...настройки,
  })
  const текст = await ответ.text()
  if (!ответ.ok) {
    // Отказ сервера — не «что-то пошло не так»: причина приходит словами
    // и показывается инженеру как есть.
    let причина = текст
    try {
      причина = (JSON.parse(текст) as { error?: string }).error ?? текст
    } catch {
      /* тело не JSON — покажем как есть */
    }
    throw new Error(причина)
  }
  return текст ? (JSON.parse(текст) as T) : ({} as T)
}

/** Строка портфеля: проект, который можно открыть заново. */
export interface ProjectRow {
  code: string
  name: string
  standard: string
  lead: string
  phase: string
}

export const api = {
  phase: (project: string) => вызов<Phase>(`/phase?project=${encodeURIComponent(project)}`),

  projects: () => вызов<{ items: ProjectRow[] }>('/projects'),

  plan: (project: string) =>
    вызов<{ planned: boolean; note?: string; gate_dates?: { gate: string; date: string }[]; scene_windows?: { scene: string; start: string; end: string }[] }>(
      `/plan?project=${encodeURIComponent(project)}`),

  setPlan: (project: string, тело: Record<string, unknown>) =>
    вызов<{ code: string; version: number }>(`/plan?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  glossary: (q?: string) =>
    вызов<{ items: { term_nasa: string; ru_equivalent: string; en_full: string; romanov: string }[] }>(
      `/glossary${q ? `?q=${encodeURIComponent(q)}` : ''}`),

  openProject: (тело: Record<string, unknown>) =>
    вызов<Phase>('/projects', { method: 'POST', body: JSON.stringify(тело) }),

  intent: (project: string, тело: Record<string, unknown>) =>
    вызов<{ id: string; status: string; phase: Phase }>(
      `/intent?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) },
    ),

  entities: (project: string, kind: string) =>
    вызов<{ items: EntityRow[] }>(
      `/entities?project=${encodeURIComponent(project)}&kind=${encodeURIComponent(kind)}`,
    ),

  addStakeholder: (project: string, тело: Record<string, unknown>) =>
    вызов<{ id: string; code: string }>(
      `/stakeholders?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) },
    ),

  addNeed: (project: string, тело: Record<string, unknown>) =>
    вызов<{ id: string; code: string }>(
      `/needs?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) },
    ),

  addConstraint: (project: string, тело: Record<string, unknown>) =>
    вызов<{ id: string; code: string }>(
      `/constraints?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) },
    ),

  addService: (project: string, тело: Record<string, unknown>) =>
    вызов<{ id: string; code: string }>(
      `/services?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) },
    ),

  addGoal: (project: string, тело: Record<string, unknown>) =>
    вызов<{ id: string; code: string }>(
      `/goals?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) },
    ),

  putMaterial: (project: string, тело: Record<string, unknown>) =>
    вызов<{ code: string }>(`/materials?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  intake: (project: string, material: string, intent: string) =>
    вызов<IntakeResult>(`/intake?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ material, intent }) }),

  facts: (project: string) => вызов<{ items: FactRow[] }>(`/facts?project=${encodeURIComponent(project)}`),

  coverage: (project: string) => вызов<CoverageMatrix>(`/coverage?project=${encodeURIComponent(project)}`),

  myTasks: (project: string, role?: string) =>
    вызов<{ project: string; items: TaskRow[]; note: string }>(
      `/my-tasks?project=${encodeURIComponent(project)}${role ? `&role=${encodeURIComponent(role)}` : ''}`,
    ),

  // --- волна 3: требования, базирование, архитектура ---

  requirements: (project: string) =>
    вызов<{ items: RequirementRow[] }>(`/requirements?project=${encodeURIComponent(project)}`),

  addRequirement: (project: string, тело: Record<string, unknown>) =>
    вызов<RequirementRow>(`/requirements?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  lint: (statement: string, ears: string) =>
    вызов<{ notes: LintNote[]; clean: boolean }>('/requirements/lint',
      { method: 'POST', body: JSON.stringify({ statement, ears }) }),

  baselines: (project: string) =>
    вызов<{ items: BaselineRow[] }>(`/baselines?project=${encodeURIComponent(project)}`),

  baselineBlockers: (project: string, kind: string, gate: string) =>
    вызов<{ items: Blocker[]; note: string }>(
      `/baselines/blockers?project=${encodeURIComponent(project)}&kind=${kind}&gate=${encodeURIComponent(gate)}`,
    ),

  baseline: (project: string, тело: Record<string, unknown>) =>
    вызов<BaselineRow>(`/baselines?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  suspects: (project: string) =>
    вызов<{ items: SuspectRow[] }>(`/suspects?project=${encodeURIComponent(project)}`),

  confirmSuspect: (project: string, link: string, author: string) =>
    вызов<{ confirmed: string }>(
      `/suspects/${encodeURIComponent(link)}/confirm?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ author }) },
    ),

  components: (project: string) =>
    вызов<{ items: ComponentRow[] }>(`/components?project=${encodeURIComponent(project)}`),

  addComponent: (project: string, тело: Record<string, unknown>) =>
    вызов<{ code: string; id: string }>(`/components?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  componentCard: (project: string, code: string, gate: string) =>
    вызов<ComponentCard>(
      `/components/${encodeURIComponent(code)}?project=${encodeURIComponent(project)}&gate=${gate}`,
    ),

  deploy: (project: string, тело: Record<string, unknown>) =>
    вызов<{ behaviour: string; node: string }>(`/components/deploy?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  concept: (project: string) =>
    вызов<{ items: ConceptRow[] }>(`/concept?project=${encodeURIComponent(project)}`),

  setConcept: (project: string, тело: Record<string, unknown>) =>
    вызов<{ code: string }>(`/concept?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  architecture: (project: string) =>
    вызов<{ layers: ArchLayer[] }>(`/architecture?project=${encodeURIComponent(project)}`),

  parameters: (project: string, component?: string) =>
    вызов<{ items: ParameterRow[] }>(
      `/parameters?project=${encodeURIComponent(project)}` +
      (component ? `&component=${encodeURIComponent(component)}` : ''),
    ),

  addParameter: (project: string, тело: Record<string, unknown>) =>
    вызов<{ code: string; id: string }>(`/parameters?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  passGate: (project: string, gate: string, author: string) =>
    вызов<Phase>(`/gates/${encodeURIComponent(gate)}/pass?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ author }) }),
}
