// Точка называет условие — и показывает, ГДЕ оно чинится.
//
// Владелец 20.09: «и где же у нас прячутся риски? На каком экране?» Риски
// живут поверхностью сцены 11, но точка MCR про это молчала: было названо
// условие («открытых рисков со сроком к MCR»), а идти с ним некуда.
import { describe, expect, it } from 'vitest'
import точки from './points.tsx?raw'
import оболочка from './shell.tsx?raw'

describe('переход «к месту» с точки', () => {
  it('сцена ищется по ключу проверки, а не по догадке экрана', () => {
    expect(точки).toContain('function где(phase: Phase | null, check: string)')
    expect(точки).toContain("const корень = check.split(':')[0].split('_')[0]")
    expect(точки).toContain('с.exit.some((у) => у.check')
  })

  it('кнопка стоит только у незакрытого условия', () => {
    expect(точки).toContain('{!у.passed && сцена && onGoScene && (')
    expect(точки).toContain('слово={`к месту: сцена ${сцена.key}`}')
  })

  it('оболочка открывает работу на этой сцене', () => {
    expect(оболочка).toContain("onGoScene={(сцена) => { setWantScene(сцена); setSection('work') }}")
  })
})
