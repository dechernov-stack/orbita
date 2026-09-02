import { describe, expect, it } from 'vitest'
import { chosenRows, rowKey } from './batchSelection'

describe('Г-02: счётчик пачки от фактического выбора', () => {
  const shown = [{ item: { id: 'SV-0101' } }, { item: { id: 'SV-0102' } }, { item: { id: 'SV-0103' } }]

  it('без снятых — все строки в пачке, счётчик равен списку', () => {
    expect(chosenRows(shown, new Set())).toHaveLength(3)
  })

  it('отказ пачки снял хвост — счётчик уменьшается ровно на снятые', () => {
    const снятые = new Set(['SV-0103'])
    expect(chosenRows(shown, снятые).map(rowKey)).toEqual(['SV-0101', 'SV-0102'])
  })

  it('снятое по перебитому id не трогает остальных: ключ — тот, что в списке', () => {
    // отказ пришёл на новый id, которого в списке нет — отметки в списке целы
    expect(chosenRows(shown, new Set(['SV-0117']))).toHaveLength(3)
  })
})
