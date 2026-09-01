// Проектный контекст клиента (ADR-022, блок D): выбранный на портфеле проект
// живёт в браузере и уходит каждым запросом параметром ?project=. Без выбора
// параметр не передаётся — сервер работает в единственном проекте портфеля,
// а при нескольких честно требует выбора.
const PROJECT_KEY = 'orbita.project'

export function currentProject(): string | null {
  try {
    return localStorage.getItem(PROJECT_KEY)
  } catch {
    return null
  }
}

export function selectProject(id: string | null) {
  try {
    if (id) localStorage.setItem(PROJECT_KEY, id)
    else localStorage.removeItem(PROJECT_KEY)
  } catch {
    // приватное окно: выбор живёт до перезагрузки
  }
}

/**
 * Дописывает проект контекста в путь запроса.
 *
 * ЯВНО УКАЗАННЫЙ проект не перебивается: у библиотечной области свой
 * контейнер (LIB), и запрос «дай профили полки» уже несёт его сам. Раньше
 * обёртка добавляла второй параметр — `?project=LIB&project=PJ-0003` — и
 * сервер брал последний: полка А2 показывалась пустой, хотя профили на ней
 * лежали (находка живого прохода ПМИ-3).
 */
export function withProject(path: string): string {
  const project = currentProject()
  if (!project) return path
  if (/[?&]project=/.test(path)) return path
  return path + (path.includes('?') ? '&' : '?') + 'project=' + encodeURIComponent(project)
}
