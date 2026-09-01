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
//   · шаги — подзадачами: библиотека плоская, поэтому шаги идут обычными
//     строками. Круг 7 сделал их полноправными: нумерация «N.M», развёрнуто
//     по умолчанию, свернуть — шевроном (память — на сессию), план ставится
//     ШАГУ, а полоса задачи с шагами сводная и не тянется.
//
// Наше здесь — только данные и правила: строки приходят с сервера уже в форме
// библиотеки, даты берутся из ПЛАНА руководителя, а где плана нет — из
// расчётной сетки, серой и подписанной. Прогресс отключён: процентов
// выполнения у задач не существует.
import { useCallback, useEffect, useRef, useState } from 'react'
import type { CSSProperties } from 'react'
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

/** Свёрнутые задачи держатся на сессию: вкладка помнит, вечность — нет. */
const COLLAPSE_KEY = 'orbita.gantt.collapsed'

// Размеры строк библиотеки. Таблица слева — СОСЕД полотна, а не графика
// поверх него, и выравнивается по этим же числам: высота строки —
// bar_height + padding, шапка — upper + lower + 10 (так считает её CSS).
const BAR_H = 22
const PADDING = 14
const UPPER_H = 38
const LOWER_H = 26
const ROW_H = BAR_H + PADDING
const HEAD_H = UPPER_H + LOWER_H + 10

const СТАТУС: Record<string, string> = {
  in_progress: 'в работе',
  available: 'доступна',
  waiting: 'ожидает',
  done: 'выполнена',
}

function свёрнутыеИзСессии(): string[] {
  try {
    const raw = sessionStorage.getItem(COLLAPSE_KEY)
    return raw ? (JSON.parse(raw) as string[]) : []
  } catch {
    return []
  }
}

export function PhaseGanttChart({ onOpen, onLead, onGo }: {
  onOpen: (taskId: string) => void
  /** Вести задачу; шаг — если ведут с конкретного шага (клик подзадачи). */
  onLead?: (taskId: string, step?: number) => void
  /** Переход к месту: с попапа точки — на жизненный цикл, перенести дату. */
  onGo?: (screen: string) => void
}) {
  const { author } = useSession()
  const host = useRef<HTMLDivElement>(null)
  const [view, setView] = useState<PhaseGanttView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [collapse, setCollapse] = useState<readonly string[]>(свёрнутыеИзСессии)
  // режим шкалы: совет сервера по длине фазы, пока инженер не выбрал свой
  const mode = useRef<string | null>(null)

  const load = useCallback((свёрнуты: readonly string[]) => {
    api.phaseGantt([...свёрнуты]).then(setView).catch((e) => setError(String(e)))
  }, [])
  useEffect(() => load(collapse), [load, collapse])
  useEffect(() => {
    try {
      sessionStorage.setItem(COLLAPSE_KEY, JSON.stringify(collapse))
    } catch {
      // память сессии может быть закрыта настройками — полотно живёт и без неё
    }
  }, [collapse])

  const переключить = (id: string) =>
    setCollapse((было) => (было.includes(id) ? было.filter((x) => x !== id) : [...было, id]))

  useEffect(() => {
    const box = host.current
    if (!view || !box || view.tasks.length === 0) return
    box.innerHTML = ''
    // библиотека дописывает в задачи служебные поля — отдаём копии
    // Имена на полотне не рисуем (круг 8, ловушка 2) — они в таблице слева.
    // Библиотеке имя нужно: отдаём пустое, чтобы подпись не спорила с таблицей.
    const rows = view.tasks.map((t) => ({ ...t, name: '' })) as unknown as GanttTask[]
    const byId = new Map(view.tasks.map((t) => [t.id, t]))
    const связи = view.links ?? []

    const сохранить = (task: GanttTask, start: Date, end: Date) => {
      const строка = byId.get(String(task.id))
      if (!строка || строка.kind === 'gate') {
        setNotice('дата точки живёт в паспорте проекта: перенесите её на жизненном цикле')
        load(collapse)
        return
      }
      // сводную полосу не тянут: она вычисляется из шагов
      if (строка.summary) {
        setNotice(`полоса задачи ${строка.order} сводная — она вычислена из шагов. План ставится шагам`)
        load(collapse)
        return
      }
      if (!author) {
        setNotice('представьтесь в шапке: план подписывается автором')
        load(collapse)
        return
      }
      const что = строка.kind === 'step' ? `шага ${строка.number}` : `задачи «${строка.title}»`
      api.phaseWorkPlan({ task: строка.id, start: iso(start), end: iso(end), author }, [...collapse])
        .then((v) => {
          setNotice(`план ${что}: ${iso(start)} — ${iso(end)}`)
          setView(v)
        })
        .catch((e) => {
          // отказ права называет право; полоса возвращается на место
          setNotice(String(e))
          load(collapse)
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
      view_mode: mode.current ?? view.view_mode ?? 'Week',
      view_mode_select: true,
      language: 'ru',
      bar_height: BAR_H,
      padding: PADDING,
      upper_header_height: UPPER_H,
      lower_header_height: LOWER_H,
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
        if (r?.kind === 'step' && r.parent) { onLead?.(r.parent, r.step_index); return }
        // клик по сводной полосе — свернуть или развернуть её шаги
        if (r?.summary) { gantt.hide_popup?.(); переключить(r.id) }
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
            r.gate_overrun ?? '',
            r.conflict && !r.gate_overrun
              ? 'конфликт плана со стрелкой зависимости: сроки спорят с типом связи. ' +
                'Соседей система не двигает — сдвиньте сами'
              : '',
            r.kind === 'task' ? (r.why ?? '') : '',
          ].filter(Boolean).map((s) => `<div>${esc(s)}</div>`).join(''),
        )
        if (r.kind === 'gate') {
          add_action('перенести точку →', () => onGo?.('lifecycle'))
        }
        if (r.kind === 'task') {
          if ((r.steps_total ?? 0) > 0) {
            add_action(
              r.collapsed ? `развернуть шаги ▾ (${r.steps_total})` : 'свернуть шаги ▴',
              (t) => переключить(String(t.id)),
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
  }, [view, author, load, onOpen, onLead, onGo, collapse])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!view) return <div className="empty">Загрузка…</div>
  if (view.tasks.length === 0) {
    return <div className="empty">{view.empty_why ?? 'План ставить нечему.'}</div>
  }

  return (
    <div className="card">
      {notice && <div className="secondary pw-gt__notice" role="status">{notice}</div>}
      {view.gate_conflict && <div className="warn pw-gt__notice">{view.gate_conflict}</div>}
      <div
        className="pw-gt-wrap"
        style={{ '--pw-row-h': `${ROW_H}px`, '--pw-head-h': `${HEAD_H}px` } as CSSProperties}
      >
        <ТаблицаСтрок view={view} collapse={collapse} onToggle={переключить}
          onNotice={setNotice} onReload={setView} author={author} />
        <div className="pw-gt" ref={host} />
      </div>
      <div className="secondary pw-gt__legend">
        полосы с планом — сплошные, границы = план руководителя; серые —
        расчётная сетка интервала до точки по порядку зависимостей, план не
        задан (потяните полосу, чтобы задать). Красная окантовка — выход не
        готов к плановому концу либо к близкой точке; оранжевая — конфликт
        плана со стрелкой: соседей система не двигает, решает человек.
        Стрелки: сплошная — после окончания (FS), штриховая — вместе, после
        старта (SS), точечная — закончить не раньше (FF), тонкая серая —
        нужен выход-артефакт (INPUT); тип берётся с полки и назван словами в
        попапе. Вертикали — точки фазы: каждая закрывает свой интервал, и
        задачи без плана размечены внутри него. Шаги — строки «N.M», развёрнуты
        по умолчанию; свернуть — шевроном в таблице слева, память — на сессию.
        Тянут шаги: полоса задачи с шагами сводная. Длительность считается из
        плана рабочими днями; введённое число — тот же план другими руками.
        Процент задачи вычислен из закрытых шагов и мышью не двигается:
        ручного процента не существует.
        {!view.can_plan && ` План правит руководитель проекта: ${view.right}.`}
      </div>
    </div>
  )
}

/**
 * Круг 8: таблица строк — СОСЕД полотна, а не графика поверх него: своей
 * графики на канве не появляется, сторож библиотеки не нарушен. Выравнивание —
 * по тем же числам, которыми живёт библиотека (высота строки, высота шапки).
 *
 * Колонки: № · имя (с отступом и шевроном), ответственный, длительность,
 * статус ТЕКСТОМ — цвет не единственный носитель смысла.
 */
function ТаблицаСтрок({ view, collapse, onToggle, onNotice, onReload, author }: {
  view: PhaseGanttView
  collapse: readonly string[]
  onToggle: (id: string) => void
  onNotice: (text: string | null) => void
  onReload: (v: PhaseGanttView) => void
  author: string
}) {
  const [правка, setПравка] = useState<{ row: string; поле: 'кто' | 'дни' } | null>(null)
  const [значение, setЗначение] = useState('')

  const назначить = (id: string, кто: string) => {
    if (!author) { onNotice('представьтесь в шапке: назначение подписывается автором'); return }
    api.phaseWorkAssign({ task: id, who: кто.trim(), clear: пусто(кто), author }, [...collapse])
      .then((v) => { onReload(v); onNotice(кто.trim() ? `ответственный: ${кто.trim()}` : 'ответственный снят') })
      .catch((e) => onNotice(String(e)))
      .finally(() => setПравка(null))
  }

  const длительность = (row: PhaseGanttView['tasks'][number], дней: number) => {
    if (!author) { onNotice('представьтесь в шапке: план подписывается автором'); return }
    api.phaseWorkPlan({ task: row.id, start: row.start, duration_days: дней, author }, [...collapse])
      .then((v) => { onReload(v); onNotice(`длительность ${row.title ?? row.name}: ${дней} раб. дн.`) })
      .catch((e) => onNotice(String(e)))
      .finally(() => setПравка(null))
  }

  return (
    <div className="pw-gt-side">
      <div className="pw-gt-side__head">
        <span>№ · работа</span>
        <span>ответственный</span>
        <span>дней</span>
        <span>статус</span>
      </div>
      {view.tasks.map((r) => {
        const шаг = r.kind === 'step'
        const точка = r.kind === 'gate'
        const статус = точка
          ? (r.held ? 'точка пройдена' : 'точка')
          : шаг
            ? (r.done ? 'сделан' : 'не сделан')
            : (r.status_text ?? СТАТУС[r.status ?? ''] ?? '')
        return (
          <div key={r.id} className={`pw-gt-side__row${шаг ? ' pw-gt-side__row--step' : ''}`}>
            <span className="pw-gt-side__name" title={`${r.name}\n${r.window_why}`}>
              {r.summary && (
                <button className="np-linkish pw-gt-side__chev" onClick={() => onToggle(r.id)}
                  title={r.collapsed ? 'развернуть шаги задачи' : 'свернуть шаги задачи'}>
                  {r.collapsed ? '▸' : '▾'}
                </button>
              )}
              {точка ? `◆ ${r.name}` : r.name}
            </span>

            {точка ? <span className="secondary">—</span> : правка?.row === r.id && правка.поле === 'кто'
              ? <input autoFocus className="pw-gt-side__edit" value={значение}
                  onChange={(e) => setЗначение(e.target.value)}
                  onBlur={() => назначить(r.id, значение)}
                  onKeyDown={(e) => { if (e.key === 'Enter') назначить(r.id, значение) }} />
              : <button className="np-linkish pw-gt-side__cell" onClick={() => {
                  setПравка({ row: r.id, поле: 'кто' }); setЗначение(r.assignee ?? '')
                }}
                  title={r.assignee
                    ? (r.assignee_own ? `ведёт ${r.assignee}` : `наследует от задачи: ${r.assignee}`)
                    : 'назначить ответственного — это делает руководитель проекта'}>
                  {r.assignee
                    ? <span className={r.assignee_own ? '' : 'secondary'}>{инициалы(r.assignee)} {r.assignee}</span>
                    : <span className="secondary">назначить…</span>}
                </button>}

            {точка ? <span className="secondary">—</span> : правка?.row === r.id && правка.поле === 'дни'
              ? <input autoFocus type="number" min={1} className="pw-gt-side__edit" value={значение}
                  onChange={(e) => setЗначение(e.target.value)}
                  onBlur={() => длительность(r, Number(значение))}
                  onKeyDown={(e) => { if (e.key === 'Enter') длительность(r, Number(значение)) }} />
              : <button className="np-linkish pw-gt-side__cell" disabled={r.summary}
                  onClick={() => { setПравка({ row: r.id, поле: 'дни' }); setЗначение(String(r.duration_days ?? 1)) }}
                  title={r.summary
                    ? 'длительность задачи складывается из шагов — правьте их'
                    : r.duration_planned
                      ? 'длительность из плана, рабочих дней. Число двигает конец плана'
                      : 'плана нет: длительность считана с расчётного окна. Число задаст план'}>
                  <span className={r.duration_planned ? '' : 'secondary'}>{r.duration_days ?? '—'}</span>
                </button>}

            <span className="secondary pw-gt-side__status" title={r.alarm ?? r.window_why}>
              {статус}
              {(r.gaps ?? 0) > 0 && <span className="warn"> · разрывы {r.gaps}</span>}
            </span>
          </div>
        )
      })}
    </div>
  )
}

/** Пустое имя — снятие ответственного, а не назначение пустоты. */
function пусто(кто: string): boolean {
  return кто.trim().length === 0
}

/** Инициалы для аватарки: «Чернов Дмитрий» → «ЧД». */
function инициалы(имя: string): string {
  return имя.split(/[\s.]+/).filter(Boolean).slice(0, 2).map((ч) => ч[0].toUpperCase()).join('')
}
