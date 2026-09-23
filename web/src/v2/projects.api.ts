// Вызовы экранов 2 и 3 шипа 2 («Проекты» и «Новый проект»).
//
// Типы ответа живут рядом с вызовом: экран не угадывает поля и не считает
// сам — группу колонки, число блокирующих у точки и день активности решает
// сервер (SceneRoutes.портфель), а ключи и заголовки точек фазы приходят с
// полки шаблона, не из кода клиента.
import { вызов } from './api'

/** Ближайшая непройденная точка проекта: число блокирующих считает сервер. */
export interface ТочкаКарточки {
  key: string
  title: string
  /** Плановая дата, ГГГГ-ММ-ДД; пусто — даты у точки нет. */
  planned_date: string
  blocking: number
}

/**
 * Строка портфеля — карточка проекта. `group` — колонка («work» либо
 * «example»); точки может не быть вовсе, и тогда поля нет: карточка говорит
 * об этом словами, а не рисует ноль.
 */
export interface КарточкаПроекта {
  code: string
  name: string
  standard: string
  phase: string
  manager: string
  group: string
  /** День последней правки любой записи проекта, ГГГГ-ММ-ДД. */
  last_activity: string
  gate?: ТочкаКарточки
}

export const портфель = () => вызов<{ items: КарточкаПроекта[] }>('/projects')

/** Класс миссии с полки: код записи и имя для человека. */
export interface КлассМиссии {
  code: string
  name: string
}

export async function классыМиссии(): Promise<КлассМиссии[]> {
  const ответ = await вызов<{ items: { code: string; doc: { name?: string } }[] }>('/shelves?kind=mission_class')
  return ответ.items.map((запись) => ({ code: запись.code, name: запись.doc.name ?? запись.code }))
}

/** Точка шаблона фазы: ключ и заголовок — с полки, а не из кода экрана. */
export interface ТочкаШаблона {
  key: string
  title: string
}

export async function точкиШаблона(шаблон: string): Promise<ТочкаШаблона[]> {
  const ответ = await вызов<{
    items: { code: string; doc: { points?: { key?: string; title?: string }[] } }[]
  }>('/shelves?kind=phase_template')
  const свой = ответ.items.find((запись) => запись.code === шаблон)
  const точки: ТочкаШаблона[] = []
  ;(свой?.doc.points ?? []).forEach((точка) => {
    if (точка.key && точка.title) точки.push({ key: точка.key, title: точка.title })
  })
  return точки
}

/** Учётка стенда — для выбора руководителя; свободный ввод остаётся. */
export interface УчёткаСтенда {
  login: string
  display_name: string
}

export async function учётки(): Promise<УчёткаСтенда[]> {
  // Учётки живут у первой версии (`/api/auth`), не под `/api/v2`: вызов
  // прямой, как на экране паспорта.
  const ответ = await fetch('/api/auth/users')
  if (!ответ.ok) return []
  const тело = (await ответ.json()) as { users?: УчёткаСтенда[] }
  return тело.users ?? []
}

/** Тело заведения проекта: даты точек уходят вместе с ним, одной записью. */
export interface ТелоЗаведения {
  name: string
  mission_class?: string
  manager?: string
  author: string
  template: string
  gate_dates: { gate: string; date: string }[]
}

/**
 * Завести проект. Ответ несёт фазу целиком; экрану нужен код проекта — по
 * нему оболочка открывает заведённый проект.
 */
export const завестиПроект = (тело: ТелоЗаведения) =>
  вызов<{ project: string }>('/projects', { method: 'POST', body: JSON.stringify(тело) })
