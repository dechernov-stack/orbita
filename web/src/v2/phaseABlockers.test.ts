// Блокеры Phase A (27.09, ответ владельца): «нечем» из таблицы «до» сквозного
// прогона закрыты ОБЩИМИ средствами карточки объекта — строка долга карточки
// узла с действиями, «+ завести» той же карточкой вниз, грань-связь «выведено
// из», действия модели и вердикты позиций экспертизы. Новых панелей нет.
import { describe, expect, it } from 'vitest'
import type { ComponentCard } from './api'
import клиент from './api.ts?raw'
import { граниПоТочкам } from './ladder'
import лестница from './ladder.tsx?raw'
import модели from './models.tsx?raw'
import прогон from './modelrun.tsx?raw'
import точки from './points.tsx?raw'
import выведено from './requirements/derived.tsx?raw'
import карточкаТребования from './requirements/card.tsx?raw'
import карточка from './ui/objectcard.tsx?raw'
import новая from './ui/newrecord.tsx?raw'

const ступень: ComponentCard = {
  code: 'EL-SC', name: 'КА', nature: 'node', level: 2, gate: 'SDR',
  facets: [
    { key: 'models', title: 'Модели', required_to: 'MCR', expected: null, lines: [] },
    { key: 'supplier', title: 'Поставщик и ответственный', required_to: 'SRR', expected: null, lines: [] },
    { key: 'configuration', title: 'Конфигурация изделия', required_to: 'SDR', expected: null, lines: [] },
  ],
  gaps: [
    { facet: 'configuration', gate: 'SDR', what: '' },
    { facet: 'models', gate: 'MCR', what: '' },
    { facet: 'supplier', gate: 'SRR', what: '' },
  ],
}

describe('строка долга карточки узла — лестница к точке с действиями', () => {
  it('грани по точкам в порядке лестницы, с ключом грани — ключ ведёт к действию', () => {
    expect(граниПоТочкам(ступень)).toEqual([
      ['MCR', [{ key: 'models', title: 'Модели' }]],
      ['SRR', [{ key: 'supplier', title: 'Поставщик и ответственный' }]],
      ['SDR', [{ key: 'configuration', title: 'Конфигурация изделия' }]],
    ])
  })

  it('грань-запись — «+ завести» той же карточкой вниз, грань-связь — пикер', () => {
    expect(лестница).toContain('<НоваяЗапись project={project} kind="configuration_item"')
    expect(лестница).toContain('<НоваяЗапись project={project} kind="function"')
    expect(лестница).toContain('kind="exchange"')
    expect(лестница).toContain('kind="exchange_item"')
    expect(лестница).toContain("api.addLink(project, { type: 'owns', from: сторона, to: узел }")
    expect(лестница).toContain('inputs: [...входы, { param_ref: вход }]')
    expect(лестница).toContain('refs: [...new Set([...(выбран.refs ?? []), узел])]')
  })

  it('где запись рождается на своей сцене — сказано словами, а не пустотой', () => {
    expect(лестница).toContain("technologies: 'технология узла — формой технологий (сцена A7): узел — её носитель'")
    expect(лестница).toContain("budgets: 'бюджет с корнем в узле — формой «Бюджеты» (сцена A5)'")
  })
})

describe('новая запись — та же карточка объекта, общее заведение', () => {
  it('черновик пишет в карточку, заводит одним общим вызовом, недостающее — словами', () => {
    expect(новая).toContain('api.createEntity(project, kind, doc, автор')
    expect(новая).toContain('черновик всеСразу')
    expect(новая).toContain('Нажать нельзя: не названо —')
    expect(клиент).toContain('`/entities?project=${encodeURIComponent(project)}`')
  })

  it('у черновика нет истории, правка — «в черновике»; нормативы пикер берёт с полки', () => {
    expect(карточка).toContain("черновик ? 'в черновике'")
    expect(карточка).toContain('{!черновик && <ИконКнопка икон="история"')
    expect(карточка).toContain("const ВИДЫ_ПОЛОК = new Set(['normative_document'])")
  })
})

describe('грань «Выведено из» карточки требования', () => {
  it('родитель пикером, обоснование обязательным, пишет и снимает общий маршрут связей', () => {
    expect(карточкаТребования).toContain('<ВыведеноИз project={project} т={т} />')
    expect(выведено).toContain("type: 'derives_from', from: т.code, to: новая.to, rationale: новая.rationale.trim()")
    expect(выведено).toContain('обоснование не названо: связь без причины неотличима от случайной')
    expect(выведено).toContain('api.removeLink(project, id')
  })
})

describe('действия модели на сцене A6', () => {
  it('прогон — выходы величинами с единицей справочника, верификация — с основанием', () => {
    expect(модели).toContain('слово="записать прогон"')
    expect(модели).toContain('слово="верифицировать"')
    expect(прогон).toContain('measure: { value: Number(в.value.replace')
    expect(прогон).toContain('основание не названо: верификация без основания — не решение')
    expect(клиент).toContain('runModel: (project: string, model: string, outputs: RunOutput[], author: string)')
  })
})

describe('вердикты позиций экспертизы точки (A12)', () => {
  it('вердикт — в строке позиции, «принято» — всем без вердикта; чек-лист только читает', () => {
    expect(точки).toContain('onВердикт={(в) => вердикт([{ artifact: п.artifact, verdict: в }])}')
    expect(точки).toContain('принято — всем без вердикта')
    expect(точки).toContain('{чекЛист.expertise?.positions.map((п) => <Позиция key={п.artifact} п={п} />)}')
    expect(клиент).toContain('`/points/${encodeURIComponent(gate)}/positions?project=${encodeURIComponent(project)}`')
  })

  it('коды зрелости Романова без легенды — с подсказкой, а не молча', () => {
    expect(точки).toContain('title="код зрелости Романова: легенда кодов зрелости не задана"')
  })
})
