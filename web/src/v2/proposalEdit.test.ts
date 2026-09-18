// Правка предложения до приёма — экран даёт поля, а истину о них берёт с сервера.
//
// Владелец 18.09: «во вкладке Постановка нет редактируемых полей». Проверяется
// устройство: поля строки берутся из содержимого и обязательных полей ВИДА,
// перечни значений — из истины схем (`kind.enums`), правка уезжает вместе с
// приёмом. Значений в коде экрана нет — иначе перечень разошёлся бы с истиной.
import { describe, expect, it } from 'vitest'
import экран from './knowledgefield.tsx?raw'
import апи from './api.ts?raw'

describe('правка предложения в постановке', () => {
  it('поля строки — содержимое плюс обязательные поля вида', () => {
    const начало = экран.indexOf('const поляПравки')
    expect(начало).toBeGreaterThan(0)
    const кусок = экран.slice(начало, начало + 420)
    expect(кусок).toContain('Object.keys(п.payload)')
    expect(кусок).toContain('вид?.required')
    // Величины и поля-ссылки правятся своей формой на сцене, не здесь.
    expect(кусок).toContain('measures')
    expect(кусок).toContain('fact_refs')
  })

  it('перечень значений берётся из истины схем, а не из кода экрана', () => {
    expect(экран).toContain('вид?.enums?.[поле]')
    // Ни одного перечня значений в экране быть не должно.
    expect(экран).not.toContain("'functional'")
    expect(экран).not.toContain("'project', 'system'")
  })

  it('правка уезжает вместе с приёмом и только для выбранных строк', () => {
    expect(экран).toContain('правкиДля(отмечены)')
    expect(экран).toContain('правкиДля(коды)')
    const где = экран.indexOf('const правкиДля')
    const кусок = экран.slice(где, где + 420)
    expect(кусок).toContain('коды.includes(код)')
    // Приём принимает правки отдельным полем — сервер их и проверяет.
    expect(апи).toContain('edits?: Record<string, Record<string, string>>')
    expect(апи).toContain('JSON.stringify({ chosen, author, reason, role, edits })')
  })
})
