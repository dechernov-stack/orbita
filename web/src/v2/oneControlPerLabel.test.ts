// Сторож: поле из нескольких контролов не заворачивается в один <label>.
//
// Проход владельца 19.09: «единицу измерения выбрать нельзя!» Виджет величины
// держал ТРИ контрола (оператор · значение · единица) под одним <label>, а label
// связан с ПЕРВЫМ контролом — настоящий клик по любому другому браузер
// переадресует ему, и список единиц закрывался в тот же миг. В разметке это не
// видно, и подстановка значения в тестах такую ошибку не ловит: у меня она
// «работала» ровно потому, что значения ставились кодом, а не мышью.
//
// Сторож структурный и узкий: сетка величины (`v2-measure`) не может стоять
// внутри <label>, а сам виджет обязан нести подпись строкой и aria-label у
// каждого контрола.
import { describe, expect, it } from 'vitest'
import { ТЕКСТ_ТРЕБОВАНИЙ as требования } from './test-support/requirementsSource'
import поле from './knowledgefield.tsx?raw'
import документыПоля from './knowledge/documents.tsx?raw'
import фактыПоля from './knowledge/facts.tsx?raw'
import ручнойВвод from './knowledge/manual.tsx?raw'
import { ТЕКСТ_ПРЕДЛОЖЕНИЙ as предложения } from './test-support/proposalsSource'
import источник from './knowledge/source.tsx?raw'
import карточка from './ui/objectcard.tsx?raw'
import стороны from './registry/stakeholders.tsx?raw'
import нужды from './registry/needs.tsx?raw'
import допущения from './registry/assumptions.tsx?raw'
import режимы from './modes.tsx?raw'
import риски from './risks.tsx?raw'
import точки from './points.tsx?raw'
import состав from './composition.tsx?raw'
import концепция from './concept.tsx?raw'
import паспорт from './passport.tsx?raw'
import словарь from './glossary.tsx?raw'

/** Код без комментариев: слово «label» в объяснении — не разметка. */
function безКомментариев(код: string): string {
  return код.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
}

/** Есть ли сетка величины внутри какого-нибудь <label>. */
function величинаВЛейбле(код: string): string[] {
  const чистый = безКомментариев(код)
  const плохие: string[] = []
  let от = чистый.indexOf('<label')
  while (от >= 0) {
    const до = чистый.indexOf('</label>', от)
    if (до < 0) break
    const фрагмент = чистый.slice(от, до)
    if (/v2-measure|v2-row3|v2-row2/.test(фрагмент)) плохие.push(фрагмент.replace(/\s+/g, ' ').slice(0, 120))
    от = чистый.indexOf('<label', до)
  }
  return плохие
}

describe('поле из нескольких контролов — не один label', () => {
  // Шип 5 §9: сторож — и на новых поверхностях: вкладки поля знаний, реестры постановки, карточка объекта.
  it.each([
    ['requirements', требования], ['knowledgefield', поле], ['modes', режимы],
    ['knowledge/documents', документыПоля], ['knowledge/facts', фактыПоля], ['knowledge/manual', ручнойВвод],
    ['knowledge/proposals', предложения], ['knowledge/source', источник], ['ui/objectcard', карточка],
    ['registry/stakeholders', стороны], ['registry/needs', нужды], ['registry/assumptions', допущения],
    // Шип 5 §7: риски, точки, состав и концепция, паспорт, словарь.
    ['risks', риски], ['points', точки], ['composition', состав], ['concept', концепция], ['passport', паспорт], ['glossary', словарь],
  ] as [string, string][])(
    '%s: ряд из нескольких контролов не стоит внутри label',
    (_имя, код) => { expect(величинаВЛейбле(код)).toEqual([]) },
  )

  it('величина собрана без label: подпись строкой, у контролов свои имена', () => {
    const где = требования.indexOf('function Величина')
    const конец = [требования.indexOf('\nfunction ', где + 10), требования.indexOf('\nexport function ', где + 10)].filter((i) => i > 0)
    const кусок = требования.slice(где, конец.length > 0 ? [...конец].sort((а, б) => а - б)[0] : undefined)
    expect(кусок).toContain('<span className="v2-field">')
    expect(кусок).toContain('v2-field__cap')
    expect(безКомментариев(кусок)).not.toContain('<label')
    expect(кусок).toContain('aria-label={`${подпись}: оператор`}')
    expect(кусок).toContain('aria-label={`${подпись}: значение`}')
    expect(кусок).toContain('aria-label={`${подпись}: единица справочника`}')
  })

  it('сторож ловит нарочную ошибку', () => {
    expect(величинаВЛейбле('<label>Показатель<span className="v2-measure"><select/></span></label>'))
      .toHaveLength(1)
    // Комментарий со словом label нарушением не считается.
    expect(величинаВЛейбле('// <label> в объяснении: v2-measure\n')).toEqual([])
  })
})
