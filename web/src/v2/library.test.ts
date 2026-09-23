// Библиотека (шип 2, экран 10): каталог полок строками и окно взятия.
// Прежний экран был складом — «куча полок, взять ничего нельзя» (З-24).
import { describe, expect, it } from 'vitest'
import экран from './library.tsx?raw'
import вызовы from './library.api.ts?raw'

describe('каталог полок', () => {
  it('строки, а не карточки: имя · записей · версия · взято · что это', () => {
    expect(экран).toContain('<th>Полка</th><th>Записей</th><th>Версия</th><th>Взято</th><th>Что это</th>')
    expect(вызовы).toContain('каталог: (project: string)')
    expect(экран).not.toContain('v2-card__title">Полка')
  })

  it('«Взять в проект…» есть у каждой строки; где нельзя — заперта и говорит почему', () => {
    expect(экран).toContain('Взять в проект…')
    expect(экран).toContain('const помеха = п.why ?? (п.mechanism ? механизмом(п.mechanism) : null)')
    expect(экран).toContain('title={помеха ?? `открыть окно взятия')
    expect(экран).toContain('function механизмом')
  })
})

describe('окно взятия', () => {
  it('чекбоксы с рекомендованным набором полки, уже взятые — без чекбокса', () => {
    expect(экран).toContain('aria-label={`взять ${з.code}`}')
    expect(экран).toContain('о.items.filter((з) => з.recommended && !з.taken).map((з) => з.code)')
    expect(экран).toContain('есть в проекте')
  })

  it('запертая строка серая и называет, кого взять сначала', () => {
    expect(экран).toContain("'v2-check v2-take__row v2-take__row--locked'")
    expect(экран).toContain('взять {з.needs.map((н) => н.title).join(\', \')}')
  })

  it('предпросмотр «станут доступны» приходит с сервера, счёт тоже', () => {
    expect(экран).toContain('станут доступны:')
    expect(вызовы).toContain('unlocks: { kind: string; count: number }[]')
    expect(экран).toContain('возьмём {окно.take}')
  })

  it('кнопка «Взять» без отметок заперта и говорит почему', () => {
    expect(экран).toContain('не отмечено ни одной записи — отметьте, что взять')
    expect(экран).toContain('Нажать нельзя: ничего не отмечено.')
  })
})
