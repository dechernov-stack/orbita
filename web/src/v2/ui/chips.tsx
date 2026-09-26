// Чипы отбора (шип 5 §2): значения из данных с числом рядом. Внутри группы
// чипы складываются «или» (заказчик или регулятор), между группами — «и»
// (регулятор и без нужд). Чип, под который не попадает ни одной строки,
// не показывается, пока он не включён: пустой отбор — шум, а не отбор.
import type { ReactNode } from 'react'

export interface ЧипОтбора<R> {
  key: string
  /** Слово значения: «без нужд», «заказчик», «TBR». */
  word: string
  /** Группа: внутри — «или», между группами — «и». */
  group: string
  test: (r: R) => boolean
  /** Подсказка: что отбирает и почему это важно. */
  hint?: string
}

/** Строки под включённые чипы: «или» внутри группы, «и» между группами. */
export function отобрать<R>(строки: R[], чипы: ЧипОтбора<R>[], включены: ReadonlySet<string>): R[] {
  const активные = чипы.filter((ч) => включены.has(ч.key))
  if (активные.length === 0) return строки
  const группы = new Map<string, ЧипОтбора<R>[]>()
  активные.forEach((ч) => группы.set(ч.group, [...(группы.get(ч.group) ?? []), ч]))
  return строки.filter((р) => [...группы.values()].every((г) => г.some((ч) => ч.test(р))))
}

/** Сколько строк под чип — число рядом со словом. */
export function подЧип<R>(строки: R[], чип: ЧипОтбора<R>): number {
  return строки.filter((р) => чип.test(р)).length
}

/** Поиск по строке: без регистра, «ё» равно «е». */
export function найдено(текст: string, игла: string): boolean {
  const норм = (с: string) => с.toLowerCase().replace(/ё/g, 'е')
  return норм(текст).includes(норм(игла.trim()))
}

export function Чипы<R>({ строки, чипы, включены, onToggle, label, children }: {
  строки: R[]
  чипы: ЧипОтбора<R>[]
  включены: ReadonlySet<string>
  onToggle: (ключ: string) => void
  /** Имя ряда для чтения экрана: «отбор сторон». */
  label: string
  /** Правый край ряда: поиск, переключатель, «добавить». */
  children?: ReactNode
}) {
  return (
    <div className="v2-chips" role="group" aria-label={label}>
      {чипы.map((ч) => {
        const n = подЧип(строки, ч)
        const вкл = включены.has(ч.key)
        if (n === 0 && !вкл) return null
        return (
          <button key={ч.key} type="button" className={вкл ? 'v2-chip v2-chip--on' : 'v2-chip'} aria-pressed={вкл}
            title={ч.hint ?? (вкл ? `снять отбор: ${ч.word}` : `показать: ${ч.word}`)}
            onClick={() => onToggle(ч.key)}>
            {ч.word} <span className="v2-chip__n">{n}</span>
          </button>
        )
      })}
      <span className="v2-chips__sp" />
      {children}
    </div>
  )
}
