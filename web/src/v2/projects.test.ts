// Экраны 2 и 3 шипа 2 (ЗАДАНИЕ-ШИП-2-ДИЗАЙН): «Проекты» — портфель двумя
// равными колонками строками-карточками; «Новый проект» — одна форма двумя
// колонками с точками фазы. Проверяется по исходному тексту, как остальные
// сторожа экранов (образец workScene.test.ts): каждое требование задания —
// строкой в коде, которую нельзя убрать молча.
import { describe, expect, it } from 'vitest'
import проекты from './projects.tsx?raw'
import новый from './newproject.tsx?raw'
import вызовы from './projects.api.ts?raw'
// CSS в vitest импортом (и с ?raw) приходит пустым — читаем файл как текст.
// Типов node в проекте нет (@types/node не ставится), сам модуль в vitest есть.
// @ts-ignore
import { readFileSync } from 'node:fs'

const стили: string = readFileSync(new URL('./projects.css', import.meta.url), 'utf-8')

/** Тело правила CSS по селектору: селектор пишется в файле ровно так, с « {». */
function правило(селектор: string): string {
  const от = стили.indexOf(`\n${селектор} {`)
  expect(от, `в projects.css нет правила «${селектор}»`).toBeGreaterThanOrEqual(0)
  return стили.slice(от, стили.indexOf('}', от))
}

describe('экран 2 «Проекты»: две равные колонки строками-карточками', () => {
  it('колонки «Рабочие» и «Примеры» равны по ширине, а группу называет сервер', () => {
    expect(правило('.v2-pf__cols')).toContain('grid-template-columns: repeat(2, minmax(0, 1fr))')
    expect(проекты).toContain("{ группа: 'work', имя: 'Рабочие'")
    expect(проекты).toContain("{ группа: 'example', имя: 'Примеры'")
    // Колонка — из поля `group` портфеля: экран не гадает по имени и коду.
    expect(проекты).toContain("п.group === 'example' ? 'example' : 'work'")
    expect(вызовы).toContain('group: string')
  })

  it('пустая колонка — ОДНА строка с тем, чего нет', () => {
    expect(проекты).toContain("пусто: 'Рабочих проектов пока нет'")
    expect(проекты).toContain("пусто: 'Примеров пока нет'")
    expect(проекты).toContain('<div className="v2-empty">{колонка.пусто}</div>')
  })

  it('имя проекта — ПОЛНОСТЬЮ: усечения на экране нет', () => {
    expect(проекты).toContain('<span className="v2-pf__name">{проект.name}</span>')
    expect(правило('.v2-pf .v2-pf__name')).toContain('white-space: normal')
    expect(правило('.v2-pf .v2-pf__name')).toContain('overflow-wrap: anywhere')
    expect(стили).not.toContain('text-overflow')
    expect(стили).not.toContain('-webkit-line-clamp')
  })

  it('«Новый проект» — главное действие справа сверху', () => {
    expect(проекты).toContain('className="v2-primary v2-pf__new"')
    expect(проекты).toContain('Новый проект')
    expect(проекты).toContain('onClick={onNew}')
    expect(правило('.v2-pf__top')).toContain('justify-content: space-between')
  })

  it('карточка: фаза чипом, точка ромбом с датой и блокирующими, активность, инициалы руководителя', () => {
    expect(проекты).toContain('<Маркер род="точка" состояние={точка.blocking > 0 ? \'блок\' : \'текущее\'}')
    expect(проекты).toContain('{датаКратко(точка.planned_date)}')
    // «блокирует N» — красным (v2-bad) и словом; перенос строки его не рвёт.
    expect(проекты).toMatch(/className="v2-bad[^"]*"[^>]*>[^<]*блокирует \{точка\.blocking\}/)
    expect(проекты).toContain('title="фаза проекта"')
    expect(проекты).toContain('{датаДнём(проект.last_activity)}')
    // инициалы — чипом, полное имя — подсказкой: усечённого текста без title нет
    expect(проекты).toContain('{инициалы(проект.manager)}')
    expect(проекты).toContain('title={`руководитель проекта · ${проект.manager}`}')
  })

  it('клик по карточке открывает проект', () => {
    expect(проекты).toContain('onClick={() => onOpen(проект.code)}')
    expect(проекты).toContain('export function ProjectsScreen({ onOpen, onNew }')
  })

  it('ни сеток-бумаги, ни панелей ради панелей, ни дашбордов', () => {
    expect(проекты).not.toContain('v2-panel')
    expect(проекты).not.toContain('<h3>')
    expect(стили).not.toContain('repeating-linear-gradient')
    expect(стили).not.toContain('background-image')
    expect(стили).not.toContain('box-shadow: 0 ')
  })
})

describe('экран 3 «Новый проект»: одна форма двумя колонками', () => {
  it('форма — две колонки, и обе видны сразу', () => {
    expect(правило('.v2-np__cols')).toContain('grid-template-columns: minmax(0, 1fr) minmax(0, 1fr)')
    expect(новый).toContain('export function NewProjectScreen({ onCreated, onCancel }')
  })

  it('слева: название, класс миссии с полки, руководитель списком учёток, дата старта', () => {
    expect(новый).toContain('<span className="v2-field__cap">Название</span>')
    expect(новый).toContain('<span className="v2-field__cap">Класс миссии</span>')
    expect(новый).toContain('<span className="v2-field__cap">Руководитель</span>')
    expect(новый).toContain('<span className="v2-field__cap">Дата старта</span>')
    // класс — с полки, не списком в коде экрана
    expect(вызовы).toContain("'/shelves?kind=mission_class'")
    expect(новый).toContain('классыМиссии()')
    // руководитель: учётки подсказкой, свободный ввод остаётся
    expect(новый).toContain('list="v2-np-учётки"')
    expect(новый).toContain('<datalist id="v2-np-учётки">')
    // дата старта — сегодня
    expect(новый).toContain('useState(сегодня)')
  })

  it('справа: точки фазы ключами и заголовками ПОЛКИ, умолчания +24/+72/+88 дней', () => {
    expect(вызовы).toContain("'/shelves?kind=phase_template'")
    expect(новый).toContain("const ШАБЛОН = 'PHT-9001'")
    expect(новый).toContain('точкиШаблона(ШАБЛОН)')
    expect(новый).toContain('const СДВИГИ = [24, 72, 88]')
    expect(новый).toContain('Точки фазы Pre-A')
    expect(новый).toContain('по умолчанию +24 / +72 / +88 дней')
    // заголовок точки — из полки; своих имён у экрана нет
    expect(новый).toContain('<span className="v2-field__cap">{точка.title}</span>')
    expect(новый).not.toContain('Внутренний обзор')
    expect(новый).not.toContain('KDP-A')
    // все даты правятся
    expect(новый).toContain('onChange={(e) => setДаты((д) => ({ ...д, [точка.key]: e.target.value }))}')
  })

  it('«Создать проект» заперта с причиной без названия; рядом «Отмена»', () => {
    expect(новый).toContain('Создать проект')
    expect(новый).toContain('Отмена')
    expect(новый).toContain("className=\"v2-primary\"")
    expect(новый).toContain('disabled={Boolean(помеха) || занято}')
    expect(новый).toContain('title={помеха ?? ')
    expect(новый).toContain("помеха = !имя.trim()")
  })

  it('даты уходят телом POST /v2/projects и возвращают код проекта', () => {
    expect(вызовы).toContain('gate_dates: { gate: string; date: string }[]')
    expect(вызовы).toContain("вызов<{ project: string }>('/projects', { method: 'POST'")
    expect(новый).toContain('gate_dates: точки.map((точка, i) => ({ gate: точка.key, date: датаТочки(точка, i) }))')
    expect(новый).toContain('.then((ответ) => onCreated(ответ.project))')
  })

  it('дата считается от старта днями, а не строкой с подстановкой года', () => {
    expect(новый).toContain('function плюсДней(дата: string, дней: number): string')
    expect(новый).toContain('д.setDate(д.getDate() + дней)')
    // ISO-строка UTC сдвинула бы день у московского полуночного времени
    expect(новый).not.toContain('toISOString')
  })
})
