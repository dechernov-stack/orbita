// Мостик и рабочий лист (шип 2, экран 6) — вызовы своего экрана: держать их
// здесь, а не в общем api.ts, значит видеть форму ответа рядом с разметкой.
import { вызов } from './api'

/** Куда ведёт строка мостика: раздел продукта, сцена и код записи. */
export interface Куда {
  section: string
  scene?: string
  code?: string
}

export interface СтрокаРешения {
  kind: string
  text: string
  action: string
  where: Куда
}

export interface Блокер {
  scene: string
  scene_title: string
  why: string
  /** Сцена ещё закрыта: срок по ней не тикает. */
  waiting: boolean
  assignment?: { code: string; assignee: string; due_point: string }
}

export interface Поручение {
  code: string
  status: string
  done: boolean
  target: { kind: string; ref: string }
  scene: string
  scene_title: string
  assignee: string
  assigned_by: string
  due_point: string
  due_point_title: string
  due_date: string
  /** Просрочку считает сервер: на экране вердиктов из сравнения дат не бывает. */
  overdue: boolean
  what?: string
  blocks?: string
}

export interface Сигнал {
  name: string
  value: string
  limit: string | null
  /** За рамкой — красное; решает сервер, экран только показывает. */
  over: boolean
  note: string
  where: Куда
}

export interface Мостик {
  project: string
  route: {
    phase: string
    scene: { key: string; title: string }
    gate: { key: string; title: string; planned_date: string; blocking: number }
    days_to_gate: number | null
  }
  decide: СтрокаРешения[]
  /** Что не влезло в семь строк — числом, а не молчанием. */
  decide_more: number
  blockers: Блокер[]
  team: Поручение[]
  signals: Сигнал[]
  /** Точки фазы — пикеру срока у кнопки «Поручить». */
  points: { key: string; title: string; planned_date: string }[]
}

export interface МояРабота {
  project: string
  assignments: Поручение[]
  open_activity: { scene: string; scene_title: string; activity: string; activity_name: string } | null
  note: string
}

export const мостикApi = {
  мостик: (project: string) => вызов<Мостик>(`/bridge?project=${encodeURIComponent(project)}`),

  мояРабота: (project: string, login: string) =>
    вызов<МояРабота>(`/mywork?project=${encodeURIComponent(project)}&login=${encodeURIComponent(login)}`),

  поручить: (
    project: string,
    тело: { target: { kind: string; ref: string }; assignee: string; due_point: string; what?: string; author: string },
  ) =>
    вызов<Поручение>(`/assignments?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify(тело) }),

  завершить: (project: string, code: string) =>
    вызов<Поручение>(`/assignments/${encodeURIComponent(code)}/done?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({}) }),
}
