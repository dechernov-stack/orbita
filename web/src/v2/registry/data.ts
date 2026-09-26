// Данные Постановки (шип 5 §2): один запрос на вид — для чисел вкладок,
// маркеров здоровья и самих реестров. Постановка и сцены 3–6 читают их ОДНИМ
// хуком: реестр один, копий нет, и числа вкладок сходятся с таблицами.
//
// Здесь только чтение и подсчёт по уже пришедшим признакам (есть ли связь,
// задано ли поле) — вердиктов покрытия клиент не считает: «покрыта» и «чего
// не хватает» приходят с сервера матрицей покрытия.
import { useCallback, useEffect, useState } from 'react'
import { api, type ApplicabilityMatrix, type CoverageMatrix, type CoverageNeed, type EntityRow, type FactRow, type KindSpec, type Phase } from '../api'
import type { Здоровье } from '../ui/tabs'

export interface ДанныеПостановки {
  стороны: EntityRow[]
  нужды: EntityRow[]
  цели: EntityRow[]
  сервисы: EntityRow[]
  ограничения: EntityRow[]
  /** Факты поля знаний: основания сторон и реестр допущений. */
  факты: FactRow[]
  покрытие: CoverageMatrix | null
  применимость: ApplicabilityMatrix | null
  /** Истина видов: подписи, перечни словами, виджеты граней. */
  виды: Record<string, KindSpec>
  /** Первое чтение ещё идёт. */
  читаю: boolean
}

const ПУСТО: ДанныеПостановки = {
  стороны: [], нужды: [], цели: [], сервисы: [], ограничения: [], факты: [],
  покрытие: null, применимость: null, виды: {}, читаю: true,
}

/** Виды постановки: у каждого своя вкладка и свой реестр. */
export const ВИДЫ = ['stakeholder', 'need', 'goal', 'service', 'constraint', 'fact'] as const

/**
 * Хук данных постановки. `факты` — основания карточки стороны и реестр
 * допущений (их читают Постановка и сцена 3); `применимость` — число и
 * маркер вкладки «Применимость» (только Постановка).
 */
export function useПостановка(project: string | null, опции: { факты?: boolean; применимость?: boolean } = {}) {
  const [данные, setДанные] = useState<ДанныеПостановки>(ПУСТО)
  const [отказ, setОтказ] = useState<string | null>(null)
  const { факты = false, применимость = false } = опции

  const перечитать = useCallback(() => {
    if (!project) return
    const список = (вид: string) => api.entities(project, вид).then((r) => r.items).catch(() => [] as EntityRow[])
    Promise.all([
      список('stakeholder'), список('need'), список('goal'), список('service'), список('constraint'),
      факты ? api.facts(project).then((r) => r.items).catch(() => [] as FactRow[]) : Promise.resolve([] as FactRow[]),
      api.coverage(project).catch((e) => { setОтказ(String((e as Error).message ?? e)); return null }),
      применимость ? api.opportunities(project).catch(() => null) : Promise.resolve(null),
    ]).then(([стороны, нужды, цели, сервисы, ограничения, фактыПоля, покрытие, матрица]) => {
      setДанные((было) => ({
        ...было, стороны, нужды, цели, сервисы, ограничения, факты: фактыПоля, покрытие, применимость: матрица, читаю: false,
      }))
    })
  }, [project, факты, применимость])

  useEffect(перечитать, [перечитать])
  // Истина видов не зависит от проекта: читается один раз.
  useEffect(() => {
    Promise.all(ВИДЫ.map((в) => api.kind(в).then((спец) => [в, спец] as const).catch(() => null)))
      .then((пары) => setДанные((было) => ({
        ...было, виды: Object.fromEntries(пары.filter((п): п is readonly [typeof ВИДЫ[number], KindSpec] => п !== null)),
      })))
  }, [])

  return { данные, отказ, перечитать }
}

/** Слово значения перечня — из истины вида; кода на экране не бывает. */
export function словом(вид: KindSpec | undefined, поле: string, значение: unknown): string {
  const код = String(значение ?? '')
  return вид?.enum_labels?.[поле]?.[код] ?? код
}

/** Имя записи для строки: наименование, формулировка или код. */
export function имяЗаписи(р: EntityRow): string {
  const д = р.doc
  return String(д.name ?? д.statement ?? д.title ?? р.code)
}

/** Носители нужды: связями owns (истина 24.09), прежнее строковое поле — пока копии не смигрированы. */
export function носителиНужды(нужда: EntityRow, стороны: EntityRow[]): EntityRow[] {
  const поСвязям = (нужда.owned_by ?? []).map((id) => стороны.find((с) => с.id === id)).filter((с): с is EntityRow => Boolean(с))
  if (поСвязям.length > 0) return поСвязям
  const прежний = нужда.doc.stakeholder
  const код = typeof прежний === 'string' ? прежний
    : прежний && typeof прежний === 'object' && 'code' in (прежний as object) ? String((прежний as { code?: string }).code ?? '') : ''
  return код ? стороны.filter((с) => с.code === код) : []
}

/** Нужды стороны — те, где она носитель. */
export function нуждыСтороны(сторона: EntityRow, нужды: EntityRow[], стороны: EntityRow[]): EntityRow[] {
  return нужды.filter((н) => носителиНужды(н, стороны).some((с) => с.id === сторона.id))
}

/** Чем закрыта нужда: записи, которые её покрывают (связь covers). */
export function закрывают(нужда: EntityRow, записи: EntityRow[]): EntityRow[] {
  const ид = new Set(нужда.covered_by ?? [])
  return записи.filter((з) => ид.has(з.id))
}

/** Нужды, которые запись (цель, сервис) закрывает. */
export function закрываемые(запись: EntityRow, нужды: EntityRow[]): EntityRow[] {
  return нужды.filter((н) => (н.covered_by ?? []).includes(запись.id))
}

/** Сила стороны: оценка человека 1–5, а без неё — предложение по роли (З-02). */
export function силаСтороны(с: EntityRow): { балл: number | null; предложена: boolean } {
  const своя = Number(с.doc.power)
  if (своя >= 1 && своя <= 5) return { балл: своя, предложена: false }
  const предложено = Number((с as EntityRow & { power_proposed?: number }).power_proposed)
  return предложено >= 1 && предложено <= 5 ? { балл: предложено, предложена: true } : { балл: null, предложена: false }
}

/** Строка матрицы покрытия по коду нужды: вердикт и «чего не хватает» — серверные. */
export function строкаПокрытия(покрытие: CoverageMatrix | null, код: string): CoverageNeed | null {
  return покрытие?.needs.find((н) => н.code === код) ?? null
}

/**
 * Класс обслуживания словами. До сцены 6 у нужды допустим TBR с
 * ответственным (решение владельца 12.09): показывается словом «TBR», а не
 * пустотой — инженер обязан видеть, что класса ещё нет.
 */
export function классСловами(значение: unknown): string {
  if (значение && typeof значение === 'object' && !Array.isArray(значение)) {
    const кто = String((значение as Record<string, unknown>).owner ?? '')
    return кто ? `TBR — за ${кто}` : 'TBR'
  }
  if (Array.isArray(значение)) return значение.map(String).join(' · ')
  return значение == null ? '' : String(значение)
}

/** Назначен ли класс: TBR и пусто — нет. */
export function классНазначен(значение: unknown): boolean {
  if (значение && typeof значение === 'object' && !Array.isArray(значение)) return false
  const текст = классСловами(значение).trim()
  return текст !== '' && текст.toUpperCase() !== 'TBR'
}

/** Классы обслуживания справочника — те же три, что и у сервиса. */
export const КЛАССЫ: [string, string][] = [
  ['A′', 'A′ — односторонний'],
  ['B′', 'B′ — с подтверждением'],
  ['C′', 'C′ — оперативного управления'],
]

/** Допущение без решения: помета «П» источника, диспозиции ещё нет (правило 23.09-b). */
export function допущениеНерешено(ф: FactRow): boolean {
  return ф.mark === 'П' && (!ф.disposition || ф.disposition === 'free')
}

/**
 * Строки реестра допущений (MCReport §10, SEMP — `assumption_register_rule`):
 * факты с диспозицией «допущение» — независимо от пометы — и факты с пометой
 * «П» любой диспозиции: у нерешённых кандидатов решение ещё впереди.
 */
export function строкиДопущений(факты: FactRow[]): FactRow[] {
  return факты.filter((ф) => ф.disposition === 'assumed' || ф.mark === 'П')
}

export type КлючВкладки = 'стороны' | 'нужды' | 'цели' | 'сервисы' | 'ограничения' | 'допущения' | 'применимость'

export interface СводкаВкладки {
  count: string | number
  health: Здоровье
  /** Подсказка: что здесь и, при долге, слово долга. ≤ 160 знаков. */
  hint: string
}

/** Держит ли сцену 4 то, что у нужд нет целей: сцена не прожита и её выход назван держащим. */
export function сцена4Держит(фаза: Phase | null | undefined): boolean {
  const сцена = фаза?.scenes.find((с) => с.key === '4')
  return Boolean(сцена && сцена.state !== 'done' && сцена.exit.some((у) => !у.passed && (у.blocking ?? true)))
}

/**
 * Числа и маркеры вкладок Постановки. Долг — «есть что закрыть»; красный —
 * «держит точку»: у нужд — когда без целей и сцена 4 этим держится.
 */
export function сводкаВкладок(д: ДанныеПостановки, держитСцену4: boolean): Record<КлючВкладки, СводкаВкладки> {
  const безНужд = д.стороны.filter((с) => нуждыСтороны(с, д.нужды, д.стороны).length === 0).length
  const всего = д.покрытие?.total ?? д.нужды.length
  const покрыто = д.покрытие?.covered ?? 0
  const непокрыто = всего - покрыто
  const безЦели = д.нужды.filter((н) => закрывают(н, д.цели).length === 0).length
  const целиБезНужд = д.цели.filter((ц) => закрываемые(ц, д.нужды).length === 0).length
  const допущения = строкиДопущений(д.факты)
  const нерешённых = допущения.filter(допущениеНерешено).length
  const вРеестре = допущения.filter((ф) => ф.disposition === 'assumed').length
  const надоДоработать = д.применимость?.by_verdict?.needs_work ?? 0
  const нужды: СводкаВкладки = непокрыто > 0
    ? {
        count: `${покрыто} из ${всего}`,
        health: держитСцену4 && безЦели > 0 ? 'block' : 'debt',
        hint: `покрыто ${покрыто} из ${всего}` + (безЦели > 0 ? `; без цели ${безЦели}` + (держитСцену4 ? ' — держит сцену 4' : '') : ''),
      }
    : { count: `${покрыто} из ${всего}`, health: 'ok', hint: `покрыты все ${всего}: у каждой нужды есть чем её закрыть` }
  return {
    стороны: безНужд > 0
      ? { count: д.стороны.length, health: 'debt', hint: `стороны, влияние и сила; без нужд ${безНужд} — сцена 3 не закроется` }
      : { count: д.стороны.length, health: 'ok', hint: 'стороны, их влияние и сила; у каждой есть нужда' },
    нужды,
    цели: целиБезНужд > 0
      ? { count: д.цели.length, health: 'debt', hint: `цели с показателями; без нужд ${целиБезНужд} — цель ни к чему не ведёт` }
      : { count: д.цели.length, health: 'ok', hint: 'цели миссии с показателями (MOE) и нуждами, которые они закрывают' },
    сервисы: { count: д.сервисы.length, health: 'ok', hint: 'сервисы с классом обслуживания и целевым показателем' },
    ограничения: { count: д.ограничения.length, health: 'ok', hint: 'ограничения Р: типовые с полки класса и свои; отменённые — с историей' },
    допущения: нерешённых > 0
      ? { count: допущения.length, health: 'debt', hint: `реестр допущений по диспозиции: в реестре ${вРеестре}, без решения ${нерешённых}` }
      : { count: допущения.length, health: 'ok', hint: `реестр допущений по диспозиции: в реестре ${вРеестре}` },
    применимость: надоДоработать > 0
      ? { count: д.применимость?.rows.length ?? 0, health: 'debt', hint: `чужие нужды из обстановки; надо доработать ${надоДоработать}` }
      : { count: д.применимость?.rows.length ?? '', health: 'ok', hint: 'чужие нужды и цели из обстановки: чем наша система им полезна' },
  }
}
