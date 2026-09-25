// Замечания владельца КТ2 (24.09): словарь по буквам, постановка подменю, факты
// сложены по предмету с переходом к противоречию, «Написать связно» и честный
// статус пустого раздела, кандидат в словарь — с дорогой к месту.
import { describe, expect, it } from 'vitest'
import словарь from './glossary.tsx?raw'
import постановка from './coverage.tsx?raw'
import знания from './knowledgefield.tsx?raw'
import документы from './documents.tsx?raw'
import оболочка from './shell.tsx?raw'
import { буквыТерминов, перваяБуква, источникКоротко } from './glossary'
import { группыФактов, предметФакта } from './knowledgefield'

describe('словарь: рубрикатор по буквам и привязка проекта', () => {
  it('буквы — кириллица, латиница, цифры; кандидаты в рубрикатор не идут', () => {
    const термины = [
      { term_ru: 'Нужда', status: 'accepted' }, { term_ru: 'need', status: 'accepted' },
      { term_ru: '12U', status: 'accepted' }, { term_ru: 'Якорь', status: 'candidate' }, { term_ru: 'нить', status: 'accepted' },
    ]
    expect(буквыТерминов(термины)).toEqual(['Н', 'N', '0–9'])
    expect(перваяБуква('  ёмкость')).toBe('Ё')
    expect(источникКоротко('NASA SEH App. B')).toBe('NASA SEH')
    expect(источникКоротко('кросс-терминология NASA ↔ РК-11КТ (сид GL-9003)')).toBe('кросс-терминология NASA ↔ РК-11КТ')
  })
  it('экран: рубрикатор, отбор по источнику, кнопка привязки сторон и узлов', () => {
    expect(словарь).toContain('aria-label="рубрикатор по буквам"')
    expect(словарь).toContain('aria-label="источник термина"')
    expect(словарь).toContain('Привязать стороны и узлы проекта')
    // Принятый словарь виден сразу, непринятые — своей вкладкой (владелец, 24.09).
    expect(словарь).toContain('aria-label="вкладки словаря"')
    expect(словарь).toContain("setВкладка('кандидаты')")
    expect(словарь).toContain("useState<'словарь' | 'кандидаты'>('словарь')")
    expect(словарь).toContain('api.glossaryLink(project)')
  })
})

describe('постановка: подменю вместо простыни', () => {
  it('три вкладки со счётчиками, выбор помнится', () => {
    expect(постановка).toContain('aria-label="подменю постановки"')
    expect(постановка).toContain("выбрать('стороны')")
    expect(постановка).toContain("выбрать('покрытие')")
    expect(постановка).toContain("выбрать('без-нужд')")
    expect(постановка).toContain("localStorage.setItem('orbita.v2.formulation.tab'")
  })
})

describe('поле знаний: факты по предмету, противоречия — ссылками', () => {
  it('группы по субъекту с числом нерешённых; без субъекта — материал или ручной ввод', () => {
    const г = группыФактов([
      { subject: 'Росморпорт', material: 'M-1', disposition: 'free' },
      { subject: 'Росморпорт', material: 'M-1', disposition: 'adopted' },
      { subject: '', material: 'M-2' },
      { subject: '', material: 'M-3', manual: true },
    ])
    expect(г.map((x) => [x.предмет, x.факты.length, x.нерешено])).toEqual([['Росморпорт', 2, 1], ['M-2', 1, 1], ['ручной ввод', 1, 1]])
    expect(предметФакта({ subject: '  ' , material: 'M-9' })).toBe('M-9')
  })
  it('экран: свёртка групп, переход к факту противоречия, дорога к словарю из заметок приёма', () => {
    expect(знания).toContain('раскрыть все (')
    expect(знания).toContain('противоречит {ф.conflicts.map')
    expect(знания).toContain('id={ф.code ? `v2-fact-${ф.code}` : undefined}')
    expect(знания).toContain('к месту: Словарь')
    expect(оболочка).toContain("onGoGlossary={() => setSection('glossary')}")
  })
})

describe('документы: написать связно и честный статус пустого раздела', () => {
  it('кнопка «написать связно» у раздела, отклонённый текст показан с причинами', () => {
    // Шип 4 §4: связный текст пишется фоновым заданием, экран опрашивает его.
    expect(документы).toContain('api.writeSectionJob(project, code, раздел.no')
    expect(документы).toContain("написанное ? 'переписать связно' : 'написать связно'")
    expect(документы).toContain('текст отклонён сторожем')
    expect(документы).toContain('api.renderings(project, code)')
  })
  it('пустой по праву раздел говорит «строк нет», кнопка закрытия тезисом — в заголовке', () => {
    expect(документы).toContain('строк нет — закройте тезисом «их нет» или заведите тему')
    expect(документы).toContain('пустоПоПраву && onЗакрытьСловами')
  })
})
