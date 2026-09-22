// Основа шипа 2 (дизайн): шапка 40 px без сцены, роли и режима; одно меню
// учётки; рейка с пиктограммой и полным словом; маркеры трёх фигур; палитра
// направления и одна гарнитура. Ничего из построенного не выброшено —
// прежние имена токенов указывают на новые значения.
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import оболочка from './shell.tsx?raw'
// CSS в vitest импортом приходит пустым — читаем файл как текст.
const токены = readFileSync(new URL('./tokens.css', import.meta.url), 'utf-8')
import маркеры from './markers.tsx?raw'
import пиктограммы from './icons.tsx?raw'
import html from '../../index.html?raw'

describe('шапка и рейка (шип 2, экран 1)', () => {
  it('в шапке — логотип, проект, фаза и ближайшая точка; сцены, роли и режима нет', () => {
    expect(оболочка).toContain('<span className="v2-brand"')
    expect(оболочка).toContain('<span className="v2-gatechip"')
    expect(оболочка).not.toContain('сцена <b>{сцена.key}')
    expect(оболочка).not.toContain('<select className="v2-density"')
    expect(токены).toContain('height: 40px; padding: 0 16px;')
  })

  it('всё личное — в одном меню-аватаре: имя, роль, «от имени», плотность, эксперт-режим, выход', () => {
    expect(оболочка).toContain('function МенюУчётки')
    expect(оболочка).toContain('className="v2-avatar"')
    expect(оболочка).toContain('выступить от имени роли')
    expect(оболочка).toContain('плотность экрана работы')
    expect(оболочка).toContain('эксперт-режим')
    expect(оболочка).toContain("fetch('/api/auth/logout', { method: 'POST' })")
  })

  it('рейка: пиктограмма + полное слово, эксперт-разделы серым до переключения', () => {
    expect(оболочка).toContain('<Икон имя={s.icon} />{s.title}')
    expect(оболочка).toContain("'v2-rail__item v2-rail__item--exp'")
    expect(токены).toContain('white-space: normal; /* полные подписи')
    expect(оболочка).toContain("{ key: 'projects', title: 'Проекты'")
    expect(оболочка).toContain("{ key: 'bridge', title: 'Мостик'")
    expect(оболочка).toContain("{ key: 'mywork', title: 'Моя работа'")
  })
})

describe('токены направления', () => {
  it('палитра чернил и графита, Golos Text, табличные цифры, радиус 6 только у ввода, теней нет', () => {
    expect(токены).toContain('--paper: #F4F6F8;')
    expect(токены).toContain('--cobalt: #2A4BD7;')
    expect(токены).toContain('--font: "Golos Text"')
    expect(токены).toContain('font-variant-numeric: tabular-nums;')
    expect(токены).toContain('--radius: 0;')
    expect(токены).toContain('--radius-input: 6px;')
    expect(токены).not.toMatch(/box-shadow: 0 \d+px \d+px rgba/)
    expect(html).toContain('family=Golos+Text')
  })

  it('три маркера — компоненты с подписью состояния словом; пиктограммы всегда со словом', () => {
    expect(маркеры).toContain("export type Род = 'сцена' | 'точка' | 'мероприятие'")
    expect(маркеры).toContain('<title>{title}</title>')
    expect(пиктограммы).toContain('aria-hidden="true"')
    expect(пиктограммы).toContain('strokeWidth="1.5"')
  })
})
