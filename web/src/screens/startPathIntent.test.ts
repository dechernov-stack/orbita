// Ф-07: у принятого замысла рядом с текстами лежат sources — якоря
// происхождения полей. Это объект, а не строка: обход полей замысла как
// строк ронял форму («.trim is not a function»), и весь мастер-путь вставал.
import { describe, expect, it } from 'vitest'
import { textOfIntent } from './StartPath'

describe('тексты замысла отделены от якорей происхождения', () => {
  it('sources в форму не попадают', () => {
    const принятый = {
      for_whom: 'Минтранс России и Ространснадзор',
      what: 'единый транспорт коротких сообщений',
      where: 'Арктика, Сибирь, Дальний Восток',
      horizon: 'к 2033 году',
      sources: { what: ['b8', 'b28'], where: ['b9', 'b33'] },
    }
    const тексты = textOfIntent(принятый)
    expect(Object.keys(тексты).sort()).toEqual(['for_whom', 'horizon', 'what', 'where'])
    expect((тексты as Record<string, unknown>).sources).toBeUndefined()
    // каждое значение — строка: обход .trim() безопасен
    Object.values(тексты).forEach((v) => expect(typeof v).toBe('string'))
  })

  it('пустое и чужое не ломают форму', () => {
    expect(textOfIntent(undefined)).toEqual({})
    expect(textOfIntent(null)).toEqual({})
    expect(textOfIntent({ for_whom: '   ', sources: { a: 1 } })).toEqual({})
    expect(textOfIntent({ text: 'Группировка IoT для логистики.' }))
      .toEqual({ text: 'Группировка IoT для логистики.' })
  })
})
