// Экран 3 шипа 2 — «Новый проект»: одна форма двумя колонками.
//
// Слева — чем проект называется: название, класс миссии с полки, руководитель
// из учёток стенда (свободный ввод остаётся) и дата старта. Справа — точки
// фазы: ключи и заголовки приходят С ПОЛКИ шаблона, а не из кода экрана;
// даты предлагаются от старта и правятся все. Внизу два действия и больше на
// экране ничего: «Создать проект» и «Отмена».
//
// Даты уходят вместе с заведением — телом `gate_dates`: они ложатся в сами
// точки, и паспорт показывает те же. Второго места для этих дат нет.
import { useEffect, useState } from 'react'
import './projects.css'
import {
  завестиПроект, классыМиссии, точкиШаблона, учётки,
  type КлассМиссии, type ТочкаШаблона, type УчёткаСтенда,
} from './projects.api'
import { useАвтор } from './research'

/** Шаблон фазы, с которого начинается любой проект: Pre-Phase A по NASA-7120. */
const ШАБЛОН = 'PHT-9001'

/**
 * Умолчания дат точек — дни от старта (задание шипа 2: +24 / +72 / +88).
 * Полка шаблона несёт свои сдвиги (30 / 60 / 90) для точек, заведённых от
 * сегодняшнего дня без формы; здесь предложены дни задания, и правятся все.
 */
const СДВИГИ = [24, 72, 88]

/** Дата как её принимает сервер и `input type=date`: ГГГГ-ММ-ДД. */
function вДату(д: Date): string {
  const месяц = String(д.getMonth() + 1).padStart(2, '0')
  const день = String(д.getDate()).padStart(2, '0')
  return `${д.getFullYear()}-${месяц}-${день}`
}

function сегодня(): string {
  return вДату(new Date())
}

/**
 * Дата через N дней от данной. Считается днями календаря, а не подстановкой
 * года в строку; ISO-строка UTC сдвинула бы день на московском полуночном
 * времени, поэтому дата собирается по местным полям.
 */
function плюсДней(дата: string, дней: number): string {
  const д = new Date(`${дата}T00:00:00`)
  д.setDate(д.getDate() + дней)
  return вДату(д)
}

export function NewProjectScreen({ onCreated, onCancel }: {
  /** Проект заведён: оболочка делает его текущим и открывает работу. */
  onCreated: (code: string) => void
  /** Вернуться к портфелю, ничего не заводя. */
  onCancel: () => void
}) {
  const [имя, setИмя] = useState('')
  const [класс, setКласс] = useState('')
  const [руководитель, setРуководитель] = useState('')
  const [старт, setСтарт] = useState(сегодня)
  /** Даты, тронутые руками: остальные идут умолчанием от старта. */
  const [даты, setДаты] = useState<Record<string, string>>({})
  const [классы, setКлассы] = useState<КлассМиссии[]>([])
  const [люди, setЛюди] = useState<УчёткаСтенда[]>([])
  const [точки, setТочки] = useState<ТочкаШаблона[]>([])
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)
  const [автор] = useАвтор()

  useEffect(() => {
    let живо = true
    классыМиссии().then((с) => { if (живо) setКлассы(с) }).catch(() => undefined)
    учётки().then((с) => { if (живо) setЛюди(с) }).catch(() => undefined)
    точкиШаблона(ШАБЛОН).then((т) => { if (живо) setТочки(т) }).catch(() => undefined)
    return () => { живо = false }
  }, [])

  // Руководителем предлагается тот, кто заводит: он и отвечает за проект,
  // пока не назначит другого. Тронутое руками не перебивается.
  useEffect(() => {
    setРуководитель((прежний) => (прежний.trim() ? прежний : автор))
  }, [автор])

  /** Дата точки: тронутая руками либо умолчание от старта. */
  const датаТочки = (точка: ТочкаШаблона, i: number): string =>
    даты[точка.key] ?? плюсДней(старт, СДВИГИ[i] ?? СДВИГИ[СДВИГИ.length - 1])

  const помеха = !имя.trim() ? 'дайте проекту название — по нему его узнают в портфеле' : null

  const создать = () => {
    if (помеха) return
    setЗанято(true); setОтказ(null)
    завестиПроект({
      name: имя.trim(),
      mission_class: класс || undefined,
      manager: руководитель.trim() || undefined,
      author: автор,
      template: ШАБЛОН,
      gate_dates: точки.map((точка, i) => ({ gate: точка.key, date: датаТочки(точка, i) })),
    })
      .then((ответ) => onCreated(ответ.project))
      .catch((е) => setОтказ(String(е.message ?? е)))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-np">
      <h1 className="v2-np__h">Новый проект</h1>
      {отказ && <div className="v2-locked">Проект не заведён: {отказ}</div>}
      <div className="v2-np__cols">
        <section className="v2-np__col">
          <div className="v2-form">
            <label className="v2-field">
              <span className="v2-field__cap">Название</span>
              <input aria-label="Название" value={имя} autoComplete="off"
                onChange={(e) => setИмя(e.target.value)} />
            </label>
            <label className="v2-field">
              <span className="v2-field__cap">Класс миссии</span>
              <select aria-label="Класс миссии" value={класс} onChange={(e) => setКласс(e.target.value)}>
                <option value="">класс не выбран</option>
                {классы.map((к) => <option key={к.code} value={к.name}>{к.name}</option>)}
              </select>
            </label>
            {классы.length === 0 && (
              <div className="v2-empty__why">
                Классов миссии на полке нет: без класса проект заведётся, но рамки в него не придут.
              </div>
            )}
            <label className="v2-field">
              <span className="v2-field__cap">Руководитель</span>
              <input aria-label="Руководитель" list="v2-np-учётки" value={руководитель} autoComplete="off"
                onChange={(e) => setРуководитель(e.target.value)} />
            </label>
            <datalist id="v2-np-учётки">
              {люди.map((ч) => <option key={ч.login} value={ч.display_name}>{ч.login}</option>)}
            </datalist>
            <label className="v2-field">
              <span className="v2-field__cap">Дата старта</span>
              <input type="date" aria-label="Дата старта" value={старт}
                onChange={(e) => setСтарт(e.target.value)} />
            </label>
          </div>
        </section>

        <section className="v2-np__col">
          <h2 className="v2-np__colh">Точки фазы Pre-A</h2>
          {точки.length === 0 ? (
            <div className="v2-empty">
              Точки фазы не прочитаны с полки — проект заведётся с датами от сегодняшнего дня.
            </div>
          ) : (
            <>
              <div className="v2-form">
                {точки.map((точка, i) => (
                  <label className="v2-field" key={точка.key}>
                    <span className="v2-field__cap">{точка.title}</span>
                    <input type="date" aria-label={`дата точки: ${точка.title}`} value={датаТочки(точка, i)}
                      onChange={(e) => setДаты((д) => ({ ...д, [точка.key]: e.target.value }))} />
                  </label>
                ))}
              </div>
              <div className="v2-np__hint">по умолчанию +24 / +72 / +88 дней от даты старта; правится любая.</div>
            </>
          )}
        </section>
      </div>

      <div className="v2-form__actions v2-np__done">
        <button type="button" className="v2-primary" onClick={создать}
          disabled={Boolean(помеха) || занято}
          title={помеха ?? 'завести проект: фаза получит точки на этих датах'}>
          {занято ? 'Завожу…' : 'Создать проект'}
        </button>
        <button type="button" className="v2-chip" onClick={onCancel}
          title="вернуться к портфелю: ничего не заведётся">
          Отмена
        </button>
      </div>
    </div>
  )
}
