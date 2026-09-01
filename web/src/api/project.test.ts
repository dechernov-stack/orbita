// Находка живого прохода ПМИ-3: полка А2 показывалась пустой, хотя профили
// на ней лежали. Запрос уходил с ДВУМЯ параметрами — «?project=LIB&project=
// PJ-0003» — и сервер брал последний, то есть искал профили полки в проекте.
//
// Библиотечная область имеет свой контейнер, и запрос к ней называет его
// сам: явно указанный проект обёртка перебивать не вправе.
import { describe, expect, it, beforeEach } from 'vitest'
import { selectProject, withProject } from './project'

// Выбор проекта живёт в localStorage браузера; в узле его нет — подставляем
// простейшее хранилище, чтобы проверять саму логику, а не среду.
const хранилище = new Map<string, string>()
globalThis.localStorage = {
  getItem: (k: string) => хранилище.get(k) ?? null,
  setItem: (k: string, v: string) => void хранилище.set(k, v),
  removeItem: (k: string) => void хранилище.delete(k),
  clear: () => хранилище.clear(),
  key: () => null,
  length: 0,
} as unknown as Storage

describe('проектный контекст запроса', () => {
  beforeEach(() => selectProject('PJ-0003'))

  it('дописывает выбранный проект, когда его нет в пути', () => {
    expect(withProject('/api/views/readiness')).toBe('/api/views/readiness?project=PJ-0003')
    expect(withProject('/api/objects?type=need')).toBe('/api/objects?type=need&project=PJ-0003')
  })

  it('не перебивает явно указанный проект — иначе полка ищется в проекте', () => {
    const путь = '/api/objects?type=stakeholder_profile&project=LIB'
    expect(withProject(путь)).toBe(путь)
    expect(withProject('/api/objects?project=LIB&type=typical_risk'))
      .toBe('/api/objects?project=LIB&type=typical_risk')
  })

  it('без выбранного проекта путь не меняется', () => {
    selectProject(null)
    expect(withProject('/api/views/readiness')).toBe('/api/views/readiness')
  })
})
