// Шип 3 — Phase A: поверхности через контекст (ПЛАН-ДОРАБОТОК-ПОСЛЕ-KDP-A §3).
// Девять сцен с выходами были без экранов; теперь каждая — привязка к
// СУЩЕСТВУЮЩЕМУ реестру с отбором по своему контексту, а не новый экран.
// Проверяется по исходному тексту, как остальные сторожа экранов.
import { describe, expect, it } from 'vitest'
import поверхности from './phasea.tsx?raw'
import работа from './work.tsx?raw'
import вызовы from './api.ts?raw'
import программатика from './programmatics.tsx?raw'
import ответственные from './responsibles.tsx?raw'
import паспорт from './passport.tsx?raw'
import план from './plan.tsx?raw'

describe('Phase A: сцена открывает реестр с отбором, а не новый экран', () => {
  it('экземпляр сцены привязывается по сцене шаблона; поверхность подключена в «Работе»', () => {
    expect(поверхности).toContain('const ключ = scene.instance_of ?? scene.key')
    expect(работа).toContain("import { PhaseASurface } from './phasea'")
    expect(работа).toContain('<PhaseASurface project={project} phase={фаза} scene={текущая} onChanged={перечитать}')
  })

  it('A1 — план фазы, ответственные сцен из окна плана, реестры соседних сцен за кликом', () => {
    expect(поверхности).toContain("if (ключ === 'A1')")
    expect(поверхности).toContain('<PhasePlan phase={phase} project={project} onChanged={onChanged} />')
    expect(поверхности).toContain('<ОтветственныеСцен project={project} onChanged={onChanged} />')
    expect(поверхности).toContain("onClick={() => onScene('A8')}")
    expect(поверхности).toContain("onClick={() => onScene('A7')}")
  })

  it('ответственный сцены живёт в окне плана; умолчание — из ролей проекта по роли сцены (истина 23.09)', () => {
    expect(ответственные).toContain("if (роль !== 'lead' && роль !== 'lead_se') return null")
    expect(ответственные).toContain('о.scene === ключ ? { ...о, responsible: логин || undefined } : о')
    expect(ответственные).toContain('api.setPlan(project, { phase: фаза.phase, author: \'инженер\', gate_dates: план.gate_dates, scene_windows: окна })')
    expect(ответственные).toContain('сначала окно сцены в плане работ фазы')
    // назначается на A1, сцене 1 и в паспорте — один блок, одно место хранения
    expect(работа).toContain('<ОтветственныеСцен project={project} onChanged={перечитать} />')
    expect(паспорт).toContain('<ОтветственныеСцен project={project} onChanged={onChanged} />')
    expect(план).toContain('responsible: о.responsible')
    expect(план).toContain("aria-label={`сцена ${с.key}: ответственный`}")
  })

  it('A2 · A3 · A9 · A11 — документ находится по ШАБЛОНУ из списка, не по угаданному коду', () => {
    expect(поверхности).toContain('template="semp"')
    expect(поверхности).toContain('template="conops"')
    expect(поверхности).toContain('template="sma"')
    expect(поверхности).toContain('template="projectplan"')
    expect(поверхности).toContain('template="fa"')
    expect(поверхности).toContain('r.items.find((д) => д.template === template)?.code ?? null')
    expect(вызовы).toContain('  template: string\n  title: string')
    // A3 — режимы и сценарии той же поверхностью, что сцена 9
    expect(поверхности).toContain('<SceneModes project={project} onChanged={onChanged} />')
    // документа нет — сказано словами и предложено завести с полки
    expect(поверхности).toContain('Документа по этому шаблону в проекте нет.')
    expect(поверхности).toContain("api.ensureDocument(project, template, автор || 'инженер')")
  })

  it('A4 — карточка узла к точке сцены, требования по носителю, стыки узла', () => {
    expect(поверхности).toContain("if (ключ === 'A4')")
    expect(поверхности).toContain('api.componentCard(project, node, gate)')
    expect(поверхности).toContain('<КарточкаУзла project={project} node={scene.node} gate={scene.gate ?? null} />')
    expect(поверхности).toContain('<Requirements project={project} отбор={отборУзла} />')
    expect(поверхности).toContain('useMemo(() => ({ носитель: scene.node ?? undefined }), [scene.node])')
    expect(поверхности).toContain('стыки.filter((с) => с.a === node || с.b === node)')
    // точка сцены — данными шаблона, а не таблицей в коде
    expect(вызовы).toContain('  gate?: string | null\n  /** Что сцена даёт — для нити потока. */')
    expect(поверхности).not.toContain("gate=\"SDR\"")
    // без узлов — словами, где их завести
    expect(поверхности).toContain('Узлов вида «элемент» в составе нет')
  })

  it('A5 — архитектура, системные требования, стыки системы и бюджеты', () => {
    expect(поверхности).toContain("if (ключ === 'A5')")
    expect(поверхности).toContain('<ArchitectureScreen project={project} />')
    expect(поверхности).toContain('<Requirements project={project} отбор={отборСистемы} />')
    expect(поверхности).toContain("useMemo(() => ({ уровень: 'system' }), [])")
    expect(поверхности).toContain('<Стыки project={project} заголовок="Стыки системы" />')
    expect(поверхности).toContain('<Бюджеты project={project} />')
    // стык и бюджет заводятся тут же, перечни — словами истины
    expect(поверхности).toContain("api.addInterface(project, { ...тело, author: автор || 'инженер' })")
    expect(поверхности).toContain("api.addBudget(project, { ...новый, author: автор || 'инженер' })")
    expect(поверхности).toContain("api.kind('interface')")
    // сторона b — внешняя система именем с владельцем-стороной (истина 24.09), не узел под чужим кодом
    expect(поверхности).toContain("const ВНЕШНЯЯ = '__external'")
    expect(поверхности).toContain('<option value={ВНЕШНЯЯ}>внешняя система…</option>')
    expect(поверхности).toContain('b: { name: внешняя.name.trim(), ...(внешняя.owner ? { owner: внешняя.owner } : {}) }')
    expect(поверхности).toContain("внешняяСторона && !внешняя.name.trim() ? 'у внешней системы нет имени'")
    expect(вызовы).toContain('b_external: boolean')
    expect(поверхности).toContain("api.kind('budget')")
    expect(поверхности).toContain("(вид?.enums?.type ?? []).map((т) =>")
  })

  it('A6 — одна Концепция, «Модели» вкладкой в контексте фазы A; A7 — технологии; A8 — реестр рисков; A9 — ОСЗ; A10 — стоимость', () => {
    expect(поверхности).toContain("if (ключ === 'A6')")
    expect(поверхности).toContain("{вкладкаA6 === 'модели' ? <Models project={project} /> : <Concept project={project} />}")
    expect(поверхности).toContain('role="tablist" aria-label="контекст сцены A6"')
    expect(поверхности).toContain("if (ключ === 'A7') return <Technologies project={project} />")
    expect(поверхности).toContain("if (ключ === 'A8') return <RiskRegistry project={project} сцены={сцены} />")
    expect(поверхности).toContain('<Debris project={project} />')
    expect(поверхности).toContain("if (ключ === 'A10') return <Costs project={project} />")
    // ОСЗ — своя поверхность, сцена 11 собирает её из тех же частей
    expect(программатика).toContain('export function Debris({ project }: { project: string })')
    expect(программатика).toContain('<RiskRegistry project={project} />\n      <Debris project={project} />')
  })

  it('запертые кнопки называют причину, поверхности отвечают на тест действия', () => {
    for (const м of поверхности.matchAll(/<button[^>]*disabled=\{[^}]*\}[^>]*>/g)) {
      expect(м[0], м[0]).toContain('title=')
    }
    for (const строка of поверхности.split('\n')) {
      if (строка.includes('className="v2-panel"')) expect(строка, строка).toContain('data-why=')
    }
  })
})
