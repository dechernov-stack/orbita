// Экран «Постановка» перестал быть стеной: сила ставится здесь, а строка
// матрицы называет, где закрывается разрыв (проход владельца 18.09).
import { describe, expect, it } from 'vitest'
import экран from './coverage.tsx?raw'

describe('экран постановки правится на месте', () => {
  it('сила и влияние стороны правятся прямо на матрице влияния', () => {
    expect(экран).toContain('api.patchEntity(project, с.code')
    // Сила — оценка человека 1–5; влияние считает система, инженер правит.
    expect(экран).toContain('поправить(с, { power: e.target.value })')
    expect(экран).toContain('поправить(с, { influence: e.target.value })')
    // Прежняя отсылка «иди в другую сцену» ушла из разметки: осталась только
    // в комментарии, где объяснено, почему её не стало.
    const разметка = экран.slice(экран.indexOf('return ('))
    expect(разметка).not.toContain('задать в сцене 3 карандашом')
  })

  it('непокрытая нужда называет следующий клик, а не только нехватку', () => {
    const кусок = экран.slice(экран.indexOf('function кудаИдти'), экран.indexOf('export function Coverage'))
    expect(кусок).toContain('сцене 3')
    expect(кусок).toContain('сцене 4')
    expect(кусок).toContain('сцене 6')
    expect(кусок).toContain('Раздать нужды по целям и сервисам')
    expect(экран).toContain('data-why="следующий-клик"')
  })
})
