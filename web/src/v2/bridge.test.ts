// Экран 6 шипа 2 — два входа: Мостик ведущего и рабочий лист специалиста.
// Ведущий не открывает реестр ради обзора, специалист не видит карту.
import { describe, expect, it } from 'vitest'
import мостик from './bridge.tsx?raw'
import работа from './mywork.tsx?raw'
import вызовы from './bridge.api.ts?raw'
import оболочка from './shell.tsx?raw'

describe('Мостик ведущего', () => {
  it('пять блоков и не больше: маршрут, решить, блокирует, команда, сигналы', () => {
    expect(мостик).toContain('карта фазы')
    expect(мостик).toContain('<h3>\n          Решить')
    expect(мостик).toContain('Блокирует ${т.title}')
    expect(мостик).toContain('Команда')
    expect(мостик).toContain('<h3>Сигналы<span className="v2-cnt">{вид.signals.length}</span></h3>')
  })

  it('очередь решений ведёт в место решения одной кнопкой, потолок назван числом', () => {
    expect(мостик).toContain('onClick={() => onGo(с.where)}')
    expect(мостик).toContain('ещё {вид.decide_more}')
    expect(вызовы).toContain('decide_more: number')
  })

  it('блокер поручается: исполнитель и срок-точка, оба обязательны', () => {
    expect(мостик).toContain('function ФормаПоручения')
    expect(мостик).toContain('aria-label="исполнитель поручения"')
    expect(мостик).toContain('aria-label="срок поручения — точка фазы"')
    expect(мостик).toContain("const помеха = !исполнитель ? 'исполнитель не выбран' : !срок ? 'срок-точка не выбран' : null")
    expect(мостик).toContain("target: { kind: 'gap', ref: блокер.scene }")
  })

  it('сигналы красные только за рамкой, и это решает сервер', () => {
    expect(мостик).toContain("с.over ? 'v2-signal v2-signal--over' : 'v2-signal'")
    expect(мостик).toContain('предела нет')
    // Пустых сигналов не выдумываем: нет свёртки — сказано словами.
    expect(мостик).toContain('Нет свёртки — нет и числа.')
  })
})

describe('Моя работа специалиста', () => {
  it('поручения по сроку с «что мешает» и «Завершил»; карты фазы здесь нет', () => {
    expect(работа).toContain('<th>Что</th><th>Сцена</th><th>Срок</th><th>От кого</th><th>Что мешает</th>')
    expect(работа).toContain('Завершил')
    expect(работа).toContain('отчёта писать не нужно')
    expect(работа).not.toContain('карта фазы')
  })

  it('поверхность мероприятия не дублируется — отсюда уходят в «Работу»', () => {
    expect(работа).toContain('открыть мероприятие')
    expect(работа).toContain("onGo({ section: 'work', scene:")
  })
})

describe('оба входа подключены в рейке', () => {
  it('«Мостик» и «Моя работа» ведут в свои экраны', () => {
    expect(оболочка).toContain("<BridgeScreen project={project}")
    expect(оболочка).toContain('<MyWorkScreen project={project} учётка={я}')
  })
})
