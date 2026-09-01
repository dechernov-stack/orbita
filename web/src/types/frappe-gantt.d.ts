// Библиотека Ганта типов не поставляет (frappe-gantt 1.2.2, MIT): объявляем
// ровно то, чем пользуемся. Форк и патчи ядра запрещены заданием — здесь
// только описание чужого API, не его изменение.
declare module 'frappe-gantt' {
  export interface GanttTask {
    id: string
    name: string
    start: string
    end: string
    progress: number
    dependencies?: string
    custom_class?: string
    [extra: string]: unknown
  }

  export interface GanttPopupContext {
    task: GanttTask
    set_title: (html: string) => void
    set_subtitle: (html: string) => void
    set_details: (html: string) => void
    add_action: (html: string, handler: (task: GanttTask) => void) => void
  }

  export interface GanttOptions {
    view_mode?: string
    view_mode_select?: boolean
    language?: string
    bar_height?: number
    padding?: number
    upper_header_height?: number
    lower_header_height?: number
    column_width?: number
    readonly?: boolean
    readonly_dates?: boolean
    readonly_progress?: boolean
    move_dependencies?: boolean
    infinite_padding?: boolean
    today_button?: boolean
    lines?: 'none' | 'vertical' | 'horizontal' | 'both'
    scroll_to?: string
    popup_on?: 'click' | 'hover'
    /** Подсветка колонок: цвет → 'weekend' либо список дат с именами. */
    holidays?: Record<string, 'weekend' | Array<{ date: string; name: string }>>
    popup?: (ctx: GanttPopupContext) => string | false | undefined
    on_click?: (task: GanttTask) => void
    on_date_change?: (task: GanttTask, start: Date, end: Date) => void
    on_view_change?: (mode: unknown) => void
  }

  export default class Gantt {
    /** Стрелки, которые нарисовала библиотека: их DOM мы только помечаем. */
    arrows?: Array<{ element?: SVGElement }>
    constructor(target: HTMLElement | string, tasks: GanttTask[], options?: GanttOptions)
    change_view_mode(mode: string, maintain_pos?: boolean): void
    update_options(options: GanttOptions): void
    scroll_current(): void
    hide_popup?(): void
  }
}
