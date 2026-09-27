// Данные карты фазы строками (27.09, «работать с такой картинкой невозможно»):
// из фазы движка, плана и окон по умолчанию — строки, полосы дорожек, месяцы
// оси и места точек. Правил зрелости и дат плана здесь нет: окна по умолчанию
// считает сервер (`/v2/plan/defaults`), состояние сцен — движок. Здесь только
// раскладка: какая строка где стоит и какой ширины полоса.
import type { Gate, Phase, Scene } from './api'

export type Состояние = 'done' | 'cur' | 'debt' | 'idle'

export interface ОкноСтроки { start: string; end: string; поУмолчанию: boolean }

export interface Строка {
  /** Ключ сцены; у группы экземпляров — ключ сцены шаблона. */
  key: string
  title: string
  state: Состояние
  кто: string
  /** Короткие имена непройденных точек, которые сцена держит. */
  держит: string[]
  окно: ОкноСтроки | null
  /** Доля выполненных блокирующих условий выхода, 0…1. */
  прогресс: number
  просрочено: boolean
  /** Экземпляры сцены по узлам: у обычной строки пусто. */
  экземпляры: Строка[]
  /** «0 из 17» — у группы экземпляров. */
  счёт?: string
  track: string
  role: string
}

export interface Полоса { key: string; title: string; роль: string; строки: Строка[] }

/** Дорожки сцен шаблона фазы словами. */
export const ДОРОЖКА: Record<string, string> = {
  design: 'Проектирование',
  management: 'Планирование и управление',
  modeling: 'Моделирование',
}

const МЕСЯЦ = ['янв', 'фев', 'мар', 'апр', 'май', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек']

/** Короткое имя точки на оси: «SRR», «SDR/MDR», «Внутр. обзор». */
export function короткоеИмя(точка: Pick<Gate, 'key' | 'title'>): string {
  const голова = точка.title.split(' — ')[0].trim()
  if (/^внутренний обзор/i.test(голова)) return 'Внутр. обзор'
  return голова || точка.key
}

/** Непройденные точки, чьи критерии ждут эту сцену («сцена прожита») невыполненными. */
export function держит(ключ: string, точки: Gate[]): string[] {
  return точки
    .filter((т) => !т.passed && т.criteria.some((у) => у.check === `scene_done:${ключ}` && !у.passed))
    .map(короткоеИмя)
}

function доля(сцена: Scene): number {
  const условия = сцена.exit.filter((у) => у.blocking !== false)
  if (условия.length === 0) return сцена.state === 'done' ? 1 : 0
  return условия.filter((у) => у.passed).length / условия.length
}

function состояние(сцена: Scene, просрочено: boolean): Состояние {
  if (сцена.state === 'done') return 'done'
  if (сцена.state === 'locked') return 'idle'
  return просрочено ? 'debt' : 'cur'
}

/** Имя узла экземпляра из заголовка сцены: «Аванпроект элемента КА (элемент)» → «КА (элемент)». */
function имяЭкземпляра(сцена: Scene): string {
  const i = сцена.title.indexOf(' элемента ')
  return `${сцена.key} · ${i >= 0 ? сцена.title.slice(i + ' элемента '.length) : сцена.title}`
}

/**
 * Строки карты по порядку фазы: экземпляры сцены (аванпроект по элементам)
 * свёрнуты в одну строку со счётом «N из M», раскрытие даёт их по узлам.
 */
export function строкиКарты(
  фаза: Phase,
  ответственный: (сцена: Scene) => string,
  сегодня: string,
): Строка[] {
  // Окно плана — сплошное; нет его — окно по умолчанию, которое сервер кладёт в вид фазы.
  const окно = (сцена: Scene): ОкноСтроки | null => {
    if (сцена.window) return { ...сцена.window, поУмолчанию: false }
    return сцена.default_window ? { ...сцена.default_window, поУмолчанию: true } : null
  }
  const строка = (сцена: Scene, заголовок: string): Строка => {
    const о = окно(сцена)
    const просрочено = Boolean(о && сцена.state !== 'done' && о.end < сегодня)
    return {
      key: сцена.key, title: заголовок, state: состояние(сцена, просрочено), кто: ответственный(сцена),
      держит: держит(сцена.instance_of ?? сцена.key, фаза.gates), окно: о, прогресс: доля(сцена),
      просрочено, экземпляры: [], track: сцена.track ?? 'design', role: сцена.role,
    }
  }
  const итог: Строка[] = []
  const группы = new Map<string, Строка>()
  фаза.scenes.forEach((с) => {
    if (!с.instance_of) { итог.push(строка(с, `${с.key} · ${с.title}`)); return }
    const экземпляр = строка(с, имяЭкземпляра(с))
    const группа = группы.get(с.instance_of)
    if (группа) { группа.экземпляры.push(экземпляр); return }
    const новая: Строка = { ...экземпляр, key: с.instance_of, title: `${с.instance_of} · Аванпроект элементов`, экземпляры: [экземпляр] }
    группы.set(с.instance_of, новая)
    итог.push(новая)
  })
  группы.forEach((г) => {
    const всего = г.экземпляры.length
    const готово = г.экземпляры.filter((э) => э.state === 'done').length
    г.счёт = `${готово} из ${всего}`
    // Число экземпляров — в счёте «0 из 17»: в заголовке второй раз не повторяется.
    г.title = `${г.key} · Аванпроект элементов`
    г.state = готово === всего ? 'done' : г.экземпляры.some((э) => э.state === 'debt') ? 'debt'
      : г.экземпляры.some((э) => э.state === 'cur') ? 'cur' : 'idle'
    г.прогресс = всего === 0 ? 0 : готово / всего
    г.просрочено = г.экземпляры.some((э) => э.просрочено)
    // Ответственные — у экземпляров (раскрытие); у свёрнутой строки место отдано имени.
    г.кто = ''
  })
  return итог
}

/** Полосы дорожек: сцены группами «Проектирование 4 · ведущий СИ». */
export function полосыКарты(строки: Строка[], роль: (код: string) => string): Полоса[] {
  const полосы = new Map<string, Строка[]>()
  строки.forEach((с) => полосы.set(с.track, [...(полосы.get(с.track) ?? []), с]))
  return [...полосы.entries()].map(([key, свои]) => {
    const счёт = new Map<string, number>()
    свои.forEach((с) => счёт.set(с.role, (счёт.get(с.role) ?? 0) + 1))
    const главная = [...счёт.entries()].sort((а, б) => б[1] - а[1])[0]?.[0] ?? ''
    return { key, title: ДОРОЖКА[key] ?? key, роль: роль(главная), строки: свои }
  })
}

/** Даты, которые карта обязана показать: окна, точки, сегодня. */
export function рамкиКарты(строки: Строка[], точки: Gate[], сегодня: string): { начало: string; конец: string } {
  const даты = [
    сегодня,
    ...точки.map((т) => т.planned_date).filter((д): д is string => Boolean(д)).map((д) => д.slice(0, 10)),
    ...строки.flatMap((с) => [с, ...с.экземпляры]).flatMap((с) => (с.окно ? [с.окно.start, с.окно.end] : [])),
  ].sort()
  return { начало: сдвиг(даты[0], -7), конец: сдвиг(даты[даты.length - 1], 14) }
}

function сдвиг(дата: string, дней: number): string {
  const д = new Date(`${дата}T00:00:00Z`)
  д.setUTCDate(д.getUTCDate() + дней)
  return д.toISOString().slice(0, 10)
}

/** Место даты на оси, % ширины поля времени. */
export function место(дата: string, рамки: { начало: string; конец: string }): number {
  const t = Date.parse(`${дата.slice(0, 10)}T00:00:00Z`)
  const t0 = Date.parse(`${рамки.начало}T00:00:00Z`)
  const t1 = Date.parse(`${рамки.конец}T00:00:00Z`)
  return t1 === t0 ? 0 : ((t - t0) / (t1 - t0)) * 100
}

/** Месяцы оси: первое число каждого месяца в рамке; у января — год. */
export function месяцыОси(рамки: { начало: string; конец: string }): { дата: string; подпись: string }[] {
  const итог: { дата: string; подпись: string }[] = []
  const д = new Date(`${рамки.начало.slice(0, 7)}-01T00:00:00Z`)
  д.setUTCMonth(д.getUTCMonth() + 1)
  while (д.toISOString().slice(0, 10) < рамки.конец) {
    const м = д.getUTCMonth()
    итог.push({ дата: д.toISOString().slice(0, 10), подпись: м === 0 ? `${МЕСЯЦ[м]} ${д.getUTCFullYear()}` : МЕСЯЦ[м] })
    д.setUTCMonth(м + 1)
  }
  return итог
}

/** «24.01» — дата точки на оси. */
export function деньМесяц(дата: string): string {
  return `${дата.slice(8, 10)}.${дата.slice(5, 7)}`
}

/**
 * Ряды подписей точек на оси: близкие точки (ближе, чем подпись шириной) —
 * через ряд, чтобы «SDR/MDR 14.04» и «KDP-B 24.05» не налезали друг на друга.
 */
export function рядыТочек(места: number[], порог = 16): number[] {
  const ряды: number[] = []
  места.forEach((м, i) => {
    ряды.push(i > 0 && м - места[i - 1] < порог && ряды[i - 1] === 0 ? 1 : 0)
  })
  return ряды
}
