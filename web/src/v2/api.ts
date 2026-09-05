// Клиент API v2. Все решения принимает сервер: здесь только вызовы и типы.
// Ни одного вычисления состояния — состояние сцен считает движок.

export type SceneState = 'locked' | 'open' | 'done'

export interface Step {
  title: string
  place: string
  hint: string
  done: boolean
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

export const api = {
  phase: (project: string) => вызов<Phase>(`/phase?project=${encodeURIComponent(project)}`),

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

  passGate: (project: string, gate: string, author: string) =>
    вызов<Phase>(`/gates/${encodeURIComponent(gate)}/pass?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ author }) }),
}
