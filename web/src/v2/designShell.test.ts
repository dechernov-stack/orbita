// Основа шипа 2 (дизайн): шапка 40 px без сцены, роли и режима; одно меню
// учётки; рейка с пиктограммой и полным словом; маркеры трёх фигур; палитра
// направления и одна гарнитура. Ничего из построенного не выброшено —
// прежние имена токенов указывают на новые значения.
import { describe, expect, it } from 'vitest'
import оболочка from './shell.tsx?raw'
import маркеры from './markers.tsx?raw'
import пиктограммы from './icons.tsx?raw'
import html from '../../index.html?raw'

describe('шапка и рейка (шип 2, экран 1)', () => {
  it('в шапке — логотип, проект, фаза и ближайшая точка; сцены, роли и режима нет', () => {
    expect(оболочка).toContain('<span className="v2-brand"')
    expect(оболочка).toContain('<span className="v2-gatechip"')
    expect(оболочка).not.toContain('сцена <b>{сцена.key}')
    expect(оболочка).not.toContain('<select className="v2-density"')
    // Высота шапки, палитра, гарнитура и радиусы — в стороже tools/validate_design_guards.py:
    // vitest отдаёт CSS пустой строкой, а типов Node в проекте нет намеренно.
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
    expect(оболочка).toContain("{ key: 'projects', title: 'Проекты'")
    expect(оболочка).toContain("{ key: 'bridge', title: 'Мостик'")
    expect(оболочка).toContain("{ key: 'mywork', title: 'Моя работа'")
  })
})

describe('токены направления', () => {
  it('гарнитура направления подключена страницей', () => {
    // Сама палитра, радиусы и высота шапки — в стороже дизайна: он читает CSS.
    expect(html).toContain('family=Golos+Text')
  })

  it('три маркера — компоненты с подписью состояния словом; пиктограммы всегда со словом', () => {
    expect(маркеры).toContain("export type Род = 'сцена' | 'точка' | 'мероприятие'")
    expect(маркеры).toContain('<title>{title}</title>')
    expect(пиктограммы).toContain('aria-hidden="true"')
    expect(пиктограммы).toContain('strokeWidth="1.5"')
  })
})
