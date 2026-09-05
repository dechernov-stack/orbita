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

  addGoal: (project: string, тело: Record<string, unknown>) =>
    вызов<{ id: string; code: string }>(
      `/goals?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) },
    ),

  passGate: (project: string, gate: string, author: string) =>
    вызов<Phase>(`/gates/${encodeURIComponent(gate)}/pass?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ author }) }),
}
