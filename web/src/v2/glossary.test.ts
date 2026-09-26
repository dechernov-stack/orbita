// Словарь (шип 4 §2): экран с кандидатами и тремя решениями, поиск по
// синонимам, добавление термина; рейка знает раздел.
import { describe, expect, it } from 'vitest'
import экран from './glossary.tsx?raw'
import оболочка from './shell.tsx?raw'

describe('экран словаря', () => {
  it('кандидаты — принять · отклонить с причиной · слить синонимом', () => {
    // Шип 5 §7: решения — строкой пиктограммами и массово; причина и цель слияния — окном.
    expect(экран).toContain("решить(коды, 'accept')")
    expect(экран).toContain("решить(коды, 'reject', причина)")
    expect(экран).toContain("решить(коды, 'merge', undefined, куда)")
    expect(экран).toContain("input: { label: 'почему отклонить', required: true }")
    expect(экран).toContain("choice: { label: 'принятый термин'")
  })
  it('поиск — по термину, синониму, коду объекта и определению; класс — словом сервера', () => {
    expect(экран).toContain('placeholder="термин, синоним, код объекта, определение"')
    expect(экран).toContain('т.class_word ?? классы[т.class]')
  })
  it('раздел «Словарь» есть в рейке и открывает экран', () => {
    expect(оболочка).toContain("{ key: 'glossary', title: 'Словарь'")
    expect(оболочка).toContain("section === 'glossary'")
  })
})
