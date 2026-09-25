// Применимость и выгрузка картины (шип 4 §5) на экране.
import { describe, expect, it } from 'vitest'
import экран from './applicability.tsx?raw'
import постановка from './coverage.tsx?raw'
import знания from './knowledgefield.tsx?raw'
import { api } from './api'

describe('применимость на постановке', () => {
  it('вкладка на подменю постановки; матрица — вердикт словом, граница устава у «не наш профиль», решение своим окном', () => {
    expect(постановка).toContain("выбрать('применимость')")
    expect(постановка).toContain('<Применимость project={project} />')
    expect(экран).toContain('aria-label="матрица применимости"')
    expect(экран).toContain('с.charter_boundary && (')
    expect(экран).toContain("input: { label: 'почему это не наш случай', required: true }")
    expect(экран).toContain('api.decideOpportunity(project, с.code, status, reason)')
    expect(экран).toContain('aria-label="чужие цели"')
    expect(экран).not.toContain('window.confirm')
  })
  it('выгрузка картины — срез по задаче, ссылка архивом с отпечатком', () => {
    expect(знания).toContain('<ВыгрузкаКартины project={project ?? \'\'} />')
    expect(знания).toContain('api.pictureTasks()')
    expect(api.pictureZipUrl('PJ-1', 'reading')).toBe('/api/v2/export/picture.zip?project=PJ-1&task=reading')
  })
})
