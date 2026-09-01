// Круг 5 «Работы»: настоящий Гант. Полотно рисует БИБЛИОТЕКА frappe-gantt
// (1.2.2, MIT) — решение владельца: полосы, стрелки зависимостей,
// перетаскивание дат, шкала и режимы День/Неделя/Месяц идут из коробки, свои
// SVG-стрелки и drag не пишутся. Форк и патчи ядра запрещены: чего не хватает
// из коробки — оверлей поверх либо отказ от хотелки.
//
// Круг 6 добавил поверх три вещи, и все три — данными, а не графикой:
//   · ТИП связи (FS · SS · FF · INPUT) приходит с полки: стрелку по-прежнему
//     рисует библиотека, а тип красит её классом (оверлей по её же DOM) и
//     называется в попапе словами;
//   · вехи — вертикалями через полотно: это подсветка колонок библиотеки
//     (holidays), а не наш SVG;
//   · шаги — подзадачами при раскрытии: библиотека плоская, поэтому шаги идут
//     обычными строками с отступом в имени. Планов шагам не заводят.
//
// Наше здесь — только данные и правила: строки приходят с сервера уже в форме
// библиотеки, даты берутся из ПЛАНА руководителя, а где плана нет — из
// расчётной сетки, серой и подписанной. Прогресс отключён: процентов
// выполнения у задач не существует.
import { useCallback, useEffect, useRef, useState } from 'react'
import Gantt from 'frappe-gantt'
import type { GanttTask } from 'frappe-gantt'
import 'frappe-gantt/css'
import { api } from '../api/client'
import type { PhaseGanttView } from '../api/types'
import { useSession } from '../ui/session'

/** Дата для сервера — местная, а не UTC: полночь по Москве в UTC — вчера. */
function iso(d: Date): string {
  const дд = `${d.getDate()}`.padStart(2, '0')
  const мм = `${d.getMonth() + 1}`.padStart(2, '0')
  return `${d.getFullYear()}-${мм}-${дд}`
}

function esc(text: string): string {
  return text.replace(/[&<>"]/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c] ?? c)
}

/** Цвет вертикали вехи — тот же токен, что у ромба на полотне. */
const MILESTONE_TINT = 'rgba(55, 53, 47, 0.5)'

export function PhaseGanttChart({ onOpen, onLead }: {
  onOpen: (taskId: string) => void
  /** Вести задачу; шаг — если ведут с конкретного шага (клик подзадачи). */
  onLead?: (taskId: string, step?: number) => void
}) {
  const { author } = useSession()
  const host = useRef<HTMLDivElement>(null)
  const [view, setView] = useState<PhaseGanttView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [expand, setExpand] = useState<readonly string[]>([])
  const mode = useRef<string>('Week')

  const load = useCallback((раскрыты: readonly string[]) => {
    api.phaseGantt([...раскрыты]).then(setView).catch((e) => setError(String(e)))
  }, [])
  useEffect(() => load(expand), [load, expand])

  useEffect(() => {
    const box = host.current
    if (!view || !box || view.tasks.length === 0) return
    box.innerHTML = ''
    // библиотека дописывает в задачи служебные поля — отдаём копии
    const rows = view.tasks.map((t) => ({ ...t })) as unknown as GanttTask[]
    const byId = new Map(view.tasks.map((t) => [t.id, t]))
    const связи = view.links ?? []

    const сохранить = (task: GanttTask, start: Date, end: Date) => {
      const строка = byId.get(String(task.id))
      // веха — не задача, шаг — не задача: сроки живут у задач и вех
      if (!строка || строка.kind !== 'task') {
        setNotice(строка?.kind === 'step'
          ? 'планов шагам не заводят: у шагов — порядок, сроки у задач и вех'
          : 'дата точки живёт в паспорте проекта, а не в плане работ')
        load(expand)
        return
      }
      if (!author) {
        setNotice('представьтесь в шапке: план подписывается автором')
        load(expand)
        return
      }
      api.phaseWorkPlan({ task: строка.id, start: iso(start), end: iso(end), author }, [...expand])
        .then((v) => {
          setNotice(`план задачи «${строка.title}»: ${iso(start)} — ${iso(end)}`)
          setView(v)
        })
        .catch((e) => {
          // отказ права называет право; полоса возвращается на место
          setNotice(String(e))
          load(expand)
        })
    }

    /**
     * Тип связи — классом на стрелке, которую нарисовала библиотека. Это
     * оверлей по её же DOM: своей графики мы не создаём, стрелку не трогаем.
     */
    const покраситьСтрелки = (g: { arrows?: Array<{ element?: SVGElement }> }) => {
      const дуги = g.arrows ?? []
      дуги.forEach((дуга) => {
        const el = дуга.element
        if (!el) return
        const from = el.getAttribute('data-from')
        const to = el.getAttribute('data-to')
        const связь = связи.find((l) => l.from === from && l.to === to)
        if (!связь) return
        el.classList.add(`pw-link-${связь.type.toLowerCase()}`)
        if (связь.conflict) el.classList.add('pw-link-conflict')
      })
    }

    const gantt = new Gantt(box, rows, {
      view_mode: mode.current,
      view_mode_select: true,
      language: 'ru',
      bar_height: 22,
      padding: 14,
      readonly_progress: true,
      move_dependencies: false,
      infinite_padding: false,
      lines: 'vertical',
      popup_on: 'click',
      today_button: true,
      scroll_to: 'today',
      // вехи — вертикалями через полотно: подсветка колонок библиотеки
      holidays: {
        [MILESTONE_TINT]: (view.milestone_lines ?? []).map((m) => ({ date: m.date, name: m.name })),
        'var(--g-weekend-highlight-color)': 'weekend',
      },
      on_date_change: сохранить,
      on_view_change: (m: unknown) => {
        const имя = (m as { name?: string })?.name
        if (имя) mode.current = имя
        // перерисовка сбрасывает наши классы — возвращаем их
        setTimeout(() => покраситьСтрелки(gantt as never), 0)
      },
      on_click: (task: GanttTask) => {
        const r = byId.get(String(task.id))
        if (r?.kind === 'step' && r.parent) onLead?.(r.parent, r.step_index)
      },
      popup: ({ task, set_title, set_subtitle, set_details, add_action }) => {
        const r = byId.get(String(task.id))
        if (!r) return false
        const входящие = связи.filter((l) => l.to === r.id)
        const исходящие = связи.filter((l) => l.from === r.id)
        set_title(r.kind === 'gate' ? `◆ ${esc(r.name)}` : esc(r.name))
        set_subtitle(esc(
          r.kind === 'gate'
            ? (r.held ? 'точка пройдена' : 'точка не пройдена')
            : r.kind === 'step'
              ? (r.done ? 'шаг сделан' : 'шаг не сделан')
              : `${r.status_text ?? ''}${r.artifact ? ` · выход: ${r.artifact}` : ''}`,
        ))
        set_details(
          [
            r.kind === 'task' && r.gate ? `зреет к: ${r.gate}` : '',
            r.window_why,
            ...входящие.map((l) => `связь: ${l.words}${l.note ? ` — ${l.note}` : ''}`),
            ...(r.links ?? []).map((l) => `связь: ${l.words}`),
            исходящие.length > 0
              ? `кормит: ${исходящие.map((l) => byId.get(l.to)?.name ?? l.to).join(', ')}`
              : '',
            r.hint ?? '',
            r.tally ? `сделано: ${r.tally}` : '',
            (r.gaps ?? 0) > 0 ? `разрывы задачи: ${r.gaps}` : '',
            r.alarm ?? '',
            r.conflict
              ? 'конфликт плана со стрелкой зависимости: сроки спорят с типом связи. ' +
                'Соседей система не двигает — сдвиньте сами'
              : '',
            r.kind === 'task' ? (r.why ?? '') : '',
          ].filter(Boolean).map((s) => `<div>${esc(s)}</div>`).join(''),
        )
        if (r.kind === 'task') {
          const раскрыта = expand.includes(r.id)
          if ((r.steps_total ?? 0) > 0) {
            add_action(
              раскрыта ? 'скрыть шаги ▴' : `шаги ▾ (${r.steps_total})`,
              (t) => setExpand((было) => раскрыта
                ? было.filter((id) => id !== String(t.id))
                : [...было, String(t.id)]),
            )
          }
          add_action(
            (r.steps_done ?? 0) < (r.steps_total ?? 0)
              ? `шаг ${(r.steps_done ?? 0) + 1} из ${r.steps_total ?? 0} → вести`
              : 'шаги пройдены → вести',
            (t) => onLead?.(String(t.id)),
          )
          add_action('карточка →', (t) => onOpen(String(t.id)))
        }
        if (r.kind === 'step' && r.parent) {
          add_action('вести с этого шага →', () => onLead?.(r.parent!, r.step_index))
        }
        return undefined
      },
    })
    покраситьСтрелки(gantt as never)
  }, [view, author, load, onOpen, onLead, expand])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!view) return <div className="empty">Загрузка…</div>
  if (view.tasks.length === 0) {
    return <div className="empty">{view.empty_why ?? 'План ставить нечему.'}</div>
  }

  return (
    <div className="card">
      {notice && <div className="secondary pw-gt__notice" role="status">{notice}</div>}
      {view.gate_conflict && <div className="warn pw-gt__notice">{view.gate_conflict}</div>}
      <div className="pw-gt" ref={host} />
      <div className="secondary pw-gt__legend">
        полосы с планом — сплошные, границы = план руководителя; серые —
        расчётная сетка интервала до точки по порядку зависимостей, план не
        задан (потяните полосу, чтобы задать). Красная окантовка — выход не
        готов к плановому концу либо к близкой точке; оранжевая — конфликт
        плана со стрелкой: соседей система не двигает, решает человек.
        Стрелки: сплошная — после окончания (FS), штриховая — вместе, после
        старта (SS), точечная — закончить не раньше (FF), тонкая серая —
        нужен выход-артефакт (INPUT); тип берётся с полки и назван словами в
        попапе. Вертикали — точки фазы, ромбами они же строками. Шаги
        раскрываются из попапа задачи: их окна — порядок, а не сроки.
        Процентов выполнения у задач не существует.
        {!view.can_plan && ` План правит руководитель проекта: ${view.right}.`}
      </div>
    </div>
  )
}
