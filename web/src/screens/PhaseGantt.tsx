// Круг 5 «Работы»: настоящий Гант. Полотно рисует БИБЛИОТЕКА frappe-gantt
// (1.2.2, MIT) — решение владельца: полосы, стрелки зависимостей,
// перетаскивание дат, шкала и режимы День/Неделя/Месяц идут из коробки, свои
// SVG-стрелки и drag не пишутся. Форк и патчи ядра запрещены: чего не хватает
// из коробки — оверлей поверх либо отказ от хотелки.
//
// Наше здесь — только данные и правила: строки приходят с сервера уже в форме
// библиотеки (id · name · start · end · dependencies · custom_class), даты
// берутся из ПЛАНА руководителя, а где плана нет — из расчётной сетки, серой
// и подписанной. Перетаскивание полосы = правка плана (право — руководитель
// проекта). Прогресс отключён: процентов выполнения у задач не существует.
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

export function PhaseGanttChart({ onOpen, onLead }: {
  onOpen: (taskId: string) => void
  onLead?: (taskId: string) => void
}) {
  const { author } = useSession()
  const host = useRef<HTMLDivElement>(null)
  const [view, setView] = useState<PhaseGanttView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const mode = useRef<string>('Week')

  const load = useCallback(() => {
    api.phaseGantt().then(setView).catch((e) => setError(String(e)))
  }, [])
  useEffect(load, [load])

  useEffect(() => {
    const box = host.current
    if (!view || !box || view.tasks.length === 0) return
    box.innerHTML = ''
    // библиотека дописывает в задачи служебные поля — отдаём копии
    const rows = view.tasks.map((t) => ({ ...t })) as unknown as GanttTask[]
    const byId = new Map(view.tasks.map((t) => [t.id, t]))

    const сохранить = (task: GanttTask, start: Date, end: Date) => {
      const строка = byId.get(String(task.id))
      // веха — не задача: её дата живёт в паспорте, а не в плане работ
      if (!строка || строка.kind === 'gate') { load(); return }
      if (!author) {
        setNotice('представьтесь в шапке: план подписывается автором')
        load()
        return
      }
      api.phaseWorkPlan({ task: строка.id, start: iso(start), end: iso(end), author })
        .then((v) => {
          setNotice(`план задачи «${строка.title}»: ${iso(start)} — ${iso(end)}`)
          setView(v)
        })
        .catch((e) => {
          // отказ права называет право; полоса возвращается на место
          setNotice(String(e))
          load()
        })
    }

    new Gantt(box, rows, {
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
      on_date_change: сохранить,
      on_view_change: (m: unknown) => {
        const имя = (m as { name?: string })?.name
        if (имя) mode.current = имя
      },
      popup: ({ task, set_title, set_subtitle, set_details, add_action }) => {
        const r = byId.get(String(task.id))
        if (!r) return false
        set_title(r.kind === 'gate' ? `◆ ${esc(r.name)}` : esc(r.name))
        set_subtitle(esc(
          r.kind === 'gate'
            ? (r.held ? 'точка пройдена' : 'точка не пройдена')
            : `${r.status_text ?? ''}${r.artifact ? ` · выход: ${r.artifact}` : ''}`,
        ))
        set_details(
          [
            r.window_why,
            r.waits_on ? `ждёт: ${r.waits_on}` : '',
            (r.gaps ?? 0) > 0 ? `разрывы задачи: ${r.gaps}` : '',
            r.alarm ?? '',
            r.conflict
              ? 'конфликт плана со стрелкой зависимости: преемник начинается раньше, ' +
                'чем кончается предшественник. Соседей система не двигает — сдвиньте сами'
              : '',
            r.why ?? '',
          ].filter(Boolean).map((s) => `<div>${esc(s)}</div>`).join(''),
        )
        if (r.kind === 'task') {
          add_action(
            (r.steps_done ?? 0) < (r.steps_total ?? 0)
              ? `шаг ${(r.steps_done ?? 0) + 1} из ${r.steps_total ?? 0} → вести`
              : 'шаги пройдены → вести',
            (t) => onLead?.(String(t.id)),
          )
          add_action('карточка →', (t) => onOpen(String(t.id)))
        }
        return undefined
      },
    })
  }, [view, author, load, onOpen, onLead])

  if (error) return <div className="empty">Ошибка обращения к API: {error}</div>
  if (!view) return <div className="empty">Загрузка…</div>
  if (view.tasks.length === 0) {
    return <div className="empty">{view.empty_why ?? 'План ставить нечему.'}</div>
  }

  return (
    <div className="card">
      {notice && (
        <div className="secondary pw-gt__notice" role="status">{notice}</div>
      )}
      <div className="pw-gt" ref={host} />
      <div className="secondary pw-gt__legend">
        полосы с планом — сплошные, границы = план руководителя; серые —
        расчётная сетка интервала до точки по порядку зависимостей, план не
        задан (потяните полосу, чтобы задать). Красная окантовка — выход не
        готов к плановому концу либо к близкой точке; оранжевая — конфликт
        плана со стрелкой: соседей система не двигает, решает человек.
        Ромбы — точки фазы; процентов выполнения у задач не существует.
        {!view.can_plan && ` План правит руководитель проекта: ${view.right}.`}
      </div>
    </div>
  )
}
