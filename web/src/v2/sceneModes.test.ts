// Сцена 9 наконец имеет поверхность: режимы и операционные сценарии.
//
// Проход владельца 20.09: «не даёт завершить: машины режимов нет — назовите
// режимы аппарата в сцене 9». Маршруты на сервере были, экрана не было вовсе:
// сцена 9 отсутствовала в перечне поверхностей. Проверяется устройство —
// поля из истины схем, отказы словами, ничего не заводится само.
import { describe, expect, it } from 'vitest'
import экран from './modes.tsx?raw'
import работа from './work.tsx?raw'
import апи from './api.ts?raw'

describe('поверхность сцены 9', () => {
  it('сцена 9 открывается экраном режимов', () => {
    expect(работа).toContain("текущая.key === '9' && <SceneModes")
    expect(работа).toContain("import { SceneModes } from './modes'")
  })

  it('машина режимов собирается полями истины', () => {
    // state_machine: owner · states[{code,name,kind}] · initial · transitions[{from,to,trigger}]
    expect(экран).toContain('api.saveModes(project, {')
    expect(экран).toContain('owner: владелец')
    expect(экран).toContain('states: годные.map')
    expect(экран).toContain('initial: начальное')
    expect(экран).toContain('trigger: { [п.вид]: п.чем.trim() }')
    expect(апи).toContain("`/modes?project=")
  })

  it('кнопка держится, пока режимов меньше двух или у перехода нет причины', () => {
    expect(экран).toContain('годные.length >= 2')
    expect(экран).toContain('одно состояние — не режим, а постоянное поведение')
    expect(экран).toContain('у перехода нет причины: режим не меняется сам по себе')
  })

  it('сценарий требует имени и шагов с участником', () => {
    expect(экран).toContain('api.addScenario(project, { name: имя.trim(), steps: годные')
    expect(экран).toContain('сценарий без шагов — это название, а не сценарий')
    expect(экран).toContain('— участник —')
    expect(апи).toContain("`/scenarios?project=")
  })

  it('поля величины не заворачиваются в один label (сторож 19.09)', () => {
    // Те же грабли, что с единицей измерения: три контрола под одной подписью.
    const без = экран.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
    let от = без.indexOf('<label')
    while (от >= 0) {
      const до = без.indexOf('</label>', от)
      expect(без.slice(от, до)).not.toContain('v2-measure')
      от = без.indexOf('<label', до)
    }
  })
})
