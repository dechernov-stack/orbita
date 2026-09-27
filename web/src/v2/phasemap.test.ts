// Карта фазы строками (27.09, владелец: «работать с такой картинкой невозможно»;
// эталон reference-phase-map-gantt.html). Проверяется то, на чём сломалась
// станционная карта: месяцы вместо недель, экземпляры одной строкой, точки
// коротким именем с датой и числом держащих условий, «держит SRR» у сцены,
// окна по умолчанию — штриховкой с «утвердить план», мероприятий на карте нет.
import { describe, expect, it } from 'vitest'
import type { Gate, Phase, Scene } from './api'
import {
  деньМесяц, держит, короткоеИмя, месяцыОси, место, полосыКарты, рамкиКарты, строкиКарты,
} from './phasemap.data'
import карта from './phasemap.tsx?raw'
import работа from './work.tsx?raw'

const условие = (check: string, passed: boolean) => ({ title: check, check, passed, why: passed ? null : 'не выполнено', blocking: true })

function сцена(key: string, над: Partial<Scene> = {}): Scene {
  return {
    key, title: `Сцена ${key}`, order: 0, role: 'lead_se', question: '', state: 'open', blockers: [], entry: [],
    exit: [условие('a', true), условие('b', false)], output: '', awaited_by: [], input_flows: [], activities: [],
    track: 'design', ...над,
  } as Scene
}

function точка(key: string, title: string, дата: string, над: Partial<Gate> = {}): Gate {
  return {
    key, title, order: 0, planned_date: дата, passed: false, blocking: [], role: 'da_review', opens_phase: null,
    checklist_of: null, legend_note: null, criteria: [], findings: [], matrix: [], ...над,
  } as Gate
}

const фаза: Phase = {
  project: 'PJ-1', standard: 'NASA-7120', phase: 'Phase A', current_scene: 'A5', lanes: [],
  gates: [
    точка('SRR', 'SRR — обзор системных требований', '2027-01-24', { criteria: [условие('scene_done:A5', false)], blocking: ['A5', 'A2'] }),
    точка('SDR', 'SDR/MDR — обзор облика системы', '2027-04-14', { criteria: [условие('scene_done:A4', false)] }),
    точка('internal_review_a', 'Внутренний обзор Phase A', '2026-11-25', { passed: true }),
  ],
  scenes: [
    сцена('A1', { track: 'management', role: 'lead', state: 'done', window: { start: '2026-09-21', end: '2026-10-05' } }),
    сцена('A4:EL-SC', { instance_of: 'A4', title: 'Аванпроект элемента Космический аппарат (элемент)', state: 'done', default_window: { start: '2026-11-01', end: '2027-04-01' } }),
    сцена('A4:EL-UT', { instance_of: 'A4', title: 'Аванпроект элемента Абонентский терминал (элемент)', default_window: { start: '2026-11-01', end: '2027-04-01' } }),
    сцена('A5', { title: 'Архитектура системы', default_window: { start: '2026-10-01', end: '2027-01-10' } }),
  ],
}
const строки = строкиКарты(фаза, () => 'Иванов И.', '2026-09-27')

describe('данные карты фазы', () => {
  it('экземпляры аванпроекта — одна строка «N из M», раскрытие даёт их по элементам', () => {
    const а4 = строки.find((с) => с.key === 'A4')!
    expect(а4.title).toBe('A4 · Аванпроект элементов')
    expect(а4.счёт).toBe('1 из 2')
    expect(а4.экземпляры.map((э) => э.title)).toEqual(['A4:EL-SC · Космический аппарат (элемент)', 'A4:EL-UT · Абонентский терминал (элемент)'])
    expect(а4.кто).toBe('')
    expect(строки.map((с) => с.key)).toEqual(['A1', 'A4', 'A5'])
  })

  it('«держит SRR» — у сцены, которую ждёт непройденная точка; пройденная не держит', () => {
    expect(строки.find((с) => с.key === 'A5')!.держит).toEqual(['SRR'])
    expect(держит('A4', фаза.gates)).toEqual(['SDR/MDR'])
    expect(держит('A1', фаза.gates)).toEqual([])
  })

  it('окно плана — сплошное, окно по умолчанию — помечено для штриховки', () => {
    expect(строки.find((с) => с.key === 'A1')!.окно).toEqual({ start: '2026-09-21', end: '2026-10-05', поУмолчанию: false })
    expect(строки.find((с) => с.key === 'A5')!.окно).toEqual({ start: '2026-10-01', end: '2027-01-10', поУмолчанию: true })
  })

  it('прогресс — доля выполненных блокирующих условий выхода', () => {
    expect(строки.find((с) => с.key === 'A5')!.прогресс).toBe(0.5)
  })

  it('полосы дорожек со словом роли; точки — коротким именем', () => {
    const полосы = полосыКарты(строки, (р) => ({ lead: 'руководитель', lead_se: 'ведущий СИ' }[р] ?? р))
    expect(полосы.map((п) => [п.title, п.строки.length, п.роль])).toEqual([
      ['Планирование и управление', 1, 'руководитель'], ['Проектирование', 2, 'ведущий СИ'],
    ])
    expect(короткоеИмя(фаза.gates[0])).toBe('SRR')
    expect(короткоеИмя(фаза.gates[1])).toBe('SDR/MDR')
    expect(короткоеИмя(фаза.gates[2])).toBe('Внутр. обзор')
    expect(деньМесяц('2027-01-24')).toBe('24.01')
  })

  it('ось — месяцами (у января год), рамка держит окна, точки и сегодня', () => {
    const рамки = рамкиКарты(строки, фаза.gates, '2026-09-27')
    expect(рамки.начало <= '2026-09-21').toBe(true)
    expect(рамки.конец >= '2027-04-14').toBe(true)
    const месяцы = месяцыОси(рамки)
    expect(месяцы[0].подпись).toBe('окт')
    expect(месяцы.some((м) => м.подпись === 'янв 2027')).toBe(true)
    expect(месяцы.length).toBeLessThan(10)
    expect(место(рамки.начало, рамки)).toBe(0)
    expect(место(рамки.конец, рамки)).toBe(100)
  })
})

describe('экран карты фазы', () => {
  it('три вида одной строкой вкладок; план не утверждён — янтарная полоса с «утвердить план»', () => {
    expect(карта).toContain("{ key: 'держащие', word: 'только держащие точку'")
    expect(карта).toContain('<b>План фазы не утверждён.</b>')
    expect(карта).toContain('Штриховка — окна по умолчанию от длительностей шаблона между точками')
    expect(карта).toContain('api.setPlan(project, {')
    // окна по умолчанию приходят в самом виде фазы — второго тяжёлого вызова нет
    expect(карта).toContain('const д = с.default_window')
    expect(карта).not.toContain('api.planDefaults(')
  })

  it('ромб точки ведёт в её карточку; клик по строке — сцена; мероприятий на карте нет', () => {
    expect(карта).toContain('onClick={() => onPoint?.(т.key)}')
    expect(карта).toContain('onClick={() => onScene(куда)}')
    expect(карта).not.toContain('activities')
    expect(работа).toContain("onGoPoint={onGoPoint}".replace('onGoPoint={onGoPoint}', 'onPoint={onGoPoint}'))
  })
})
