// Экран 2 шипа 2 — «Проекты»: портфель двумя равными колонками.
//
// Экран отвечает на один вопрос — «куда идти»: рабочие проекты и примеры
// строками-карточками, в каждой имя ПОЛНОСТЬЮ, фаза, ближайшая точка с датой
// и блокирующими, день последней активности и руководитель инициалами. Клик по
// карточке открывает проект, кнопка справа сверху заводит новый. Дашбордов,
// сеток-бумаги и декоративных строк здесь нет: каждый элемент либо открывает
// проект, либо говорит, чего в колонке ещё нет.
//
// Ни одного вердикта из сравнения величин: колонку, число блокирующих и день
// активности называет сервер (`/v2/projects`), экран их показывает.
import { useEffect, useState } from 'react'
import './projects.css'
import { портфель, type КарточкаПроекта } from './projects.api'
import { Маркер } from './markers'

/** Колонки портфеля: группа проекта — поле записи, не догадка экрана. */
const КОЛОНКИ = [
  { группа: 'work', имя: 'Рабочие', пусто: 'Рабочих проектов пока нет' },
  { группа: 'example', имя: 'Примеры', пусто: 'Примеров пока нет' },
] as const

/** Дата точки — днём и месяцем: год в портфеле один и тот же. */
function датаКратко(дата: string): string {
  const [, м, д] = дата.split('-')
  return д && м ? `${д}.${м}` : дата
}

/** День активности — полной датой: правка могла быть и в прошлом году. */
function датаДнём(дата: string): string {
  const [г, м, д] = дата.split('-')
  return г && м && д ? `${д}.${м}.${г}` : дата
}

/** Инициалы руководителя: «Чернов Д.» → «ЧД»; полное имя остаётся в подсказке. */
function инициалы(имя: string): string {
  const слова = имя.replace(/^tg:/, '').split(/[\s.·]+/).filter(Boolean)
  const буквы = слова.slice(0, 2).map((с) => с[0]?.toUpperCase() ?? '').join('')
  return буквы || имя.slice(0, 2).toUpperCase()
}

export function ProjectsScreen({ onOpen, onNew }: {
  /** Открыть проект портфеля: оболочка делает его текущим. */
  onOpen: (code: string) => void
  /** Перейти к форме заведения (экран 3). */
  onNew: () => void
}) {
  const [проекты, setПроекты] = useState<КарточкаПроекта[] | null>(null)
  const [отказ, setОтказ] = useState<string | null>(null)

  useEffect(() => {
    let живо = true
    портфель()
      .then((ответ) => { if (живо) setПроекты(ответ.items) })
      .catch((е) => { if (живо) setОтказ(String(е.message ?? е)) })
    return () => { живо = false }
  }, [])

  return (
    <div className="v2-pf">
      <div className="v2-pf__top">
        <h1 className="v2-pf__h">Проекты</h1>
        <button type="button" className="v2-primary v2-pf__new" onClick={onNew}
          title="завести проект: название, класс миссии, руководитель и даты точек фазы">
          Новый проект
        </button>
      </div>
      {/* Портфель не прочитан — говорим это словами сервера и не рисуем колонок:
          «примеров пока нет» при непрочитанном портфеле было бы неправдой. */}
      {отказ ? (
        <div className="v2-locked">Портфель не прочитан: {отказ}</div>
      ) : (
        <div className="v2-pf__cols">
          {КОЛОНКИ.map((колонка) => {
            const свои = (проекты ?? []).filter((п) => (п.group === 'example' ? 'example' : 'work') === колонка.группа)
            return (
              <section className="v2-pf__col" key={колонка.группа}>
                <h2 className="v2-pf__colh">{колонка.имя}</h2>
                {проекты === null ? (
                  <div className="v2-empty">Читаю портфель…</div>
                ) : свои.length === 0 ? (
                  <div className="v2-empty">{колонка.пусто}</div>
                ) : (
                  свои.map((проект) => <Карточка key={проект.code} проект={проект} onOpen={onOpen} />)
                )}
              </section>
            )
          })}
        </div>
      )}
    </div>
  )
}

/**
 * Строка-карточка проекта. Имя не усекается: длинное встаёт в две строки, и
 * подсказка ему не нужна. Точка — ромбом с состоянием: блокирующие красным,
 * и то же словом в подсказке маркера.
 */
function Карточка({ проект, onOpen }: { проект: КарточкаПроекта; onOpen: (code: string) => void }) {
  const точка = проект.gate
  return (
    <button type="button" className="v2-pf__card" onClick={() => onOpen(проект.code)}
      title={`открыть проект «${проект.name}»`}>
      <span className="v2-pf__name">{проект.name}</span>
      <span className="v2-pf__line">
        <span className="v2-chip" title="фаза проекта">{проект.phase}</span>
        {точка ? (
          <span className="v2-pf__gate">
            <Маркер род="точка" состояние={точка.blocking > 0 ? 'блок' : 'текущее'} подпись={точка.title} />
            {' '}{точка.title}
            {/* Дата и блокирующие — цельными кусками: перенос строки не должен
                оставлять «·» висеть в конце строки. */}
            {точка.planned_date && <span className="v2-pf__nb"> · {датаКратко(точка.planned_date)}</span>}
            {точка.blocking > 0 && <span className="v2-bad v2-pf__nb"> · блокирует {точка.blocking}</span>}
          </span>
        ) : (
          <span className="v2-dim">ближайшей точки нет</span>
        )}
      </span>
      <span className="v2-pf__side">
        {проект.last_activity && (
          <span className="v2-pf__when" title="последняя правка записей проекта">{датаДнём(проект.last_activity)}</span>
        )}
        {проект.manager ? (
          <span className="v2-pf__who" title={`руководитель проекта · ${проект.manager}`}>{инициалы(проект.manager)}</span>
        ) : (
          <span className="v2-dim" title="руководителя называют в паспорте проекта">руководитель не назван</span>
        )}
      </span>
    </button>
  )
}
