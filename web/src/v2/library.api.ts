// Библиотека (шип 2, экран 10) — вызовы своего экрана: каталог полок и окно
// взятия. Форма ответа лежит рядом с разметкой, а не в общем api.ts.
import { вызов } from './api'

export interface СтрокаКаталога {
  /** Код полки: контейнер — своим кодом, плоская — именем вида. */
  code: string
  kind: string
  title: string
  count: number
  version: number
  /** Сколько записей этой полки уже в проекте. */
  taken: number
  what: string
  /** С полки берут в проект; нет — сказано словами в `why`. */
  takeable: boolean
  /** Взятие идёт своим механизмом (каркас состава, каркас работ). */
  mechanism: string | null
  why: string | null
}

export interface ЗаписьВзятия {
  code: string
  title: string
  parent: string | null
  level: number
  /** Отмечена по умолчанию: правило самой полки, не догадка экрана. */
  recommended: boolean
  /** Уже в проекте: повтор ничего не удваивает. */
  taken: boolean
  /** Чего не хватает, чтобы взять: родитель, которого нет ни в проекте, ни в наборе. */
  needs: { code: string; title: string }[]
}

export interface ОкноВзятия {
  shelf: string
  kind: string
  title: string
  what: string
  takeable: boolean
  mechanism: string | null
  why: string | null
  items: ЗаписьВзятия[]
  /** Счёт считает сервер: возьмём · уже есть · заперто родителем. */
  take: number
  already: number
  blocked: number
  /** Что станет доступно вместе со взятым. */
  unlocks: { kind: string; count: number }[]
  note: string
}

export interface ИтогВзятия {
  shelf: string
  /** Счёт ведёт сервер: заведено и сколько уже было. */
  created: number
  already: number
  note: string
}

export const библиотекаApi = {
  каталог: (project: string) =>
    вызов<{ items: СтрокаКаталога[]; empty: string[]; empty_note: string }>(
      `/library/catalog?project=${encodeURIComponent(project)}`),

  окно: (project: string, shelf: string, items?: string[]) =>
    вызов<ОкноВзятия>(
      `/library/take/preview?project=${encodeURIComponent(project)}&shelf=${encodeURIComponent(shelf)}`
      + (items && items.length > 0 ? `&items=${encodeURIComponent(items.join(','))}` : ''),
    ),

  взять: (project: string, shelf: string, items: string[], author = 'инженер') =>
    вызов<ИтогВзятия>(`/library/take?project=${encodeURIComponent(project)}`,
      { method: 'POST', body: JSON.stringify({ shelf, items, author }) }),
}
