// §2.3: подсказка обязана нести расшифровку, а не повторять сама себя.
// Ловушка живого прогона: статусная точка показывала машинное имя статуса
// («Draft») — формально подсказка была, пользы ноль.
import { describe, expect, it } from 'vitest'
import { STATUS_MEANING } from './maturity'

describe('подсказки статусов', () => {
  it('каждый статус объясняется словами, а не повторяет своё имя', () => {
    const статусы = Object.keys(STATUS_MEANING)
    expect(статусы.length).toBeGreaterThan(0)
    статусы.forEach((s) => {
      const текст = STATUS_MEANING[s]
      expect(текст, `${s}: расшифровка пуста`).toBeTruthy()
      expect(текст.trim().toLowerCase(), `${s}: расшифровка повторяет имя статуса`)
        .not.toBe(s.toLowerCase())
      expect(текст.length, `${s}: расшифровка слишком коротка, чтобы что-то объяснить`)
        .toBeGreaterThan(10)
    })
  })
})
