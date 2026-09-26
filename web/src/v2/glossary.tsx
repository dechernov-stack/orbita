/**
 * Словарь (шип 4 §2, РЕШЕНИЕ-СЛОВАРЬ-И-БАЗА-ЗНАНИЙ): термины класса миссии
 * (библиотека) и дельта проекта; кандидаты из документов — с цитатой, решает
 * человек: принять · отклонить с причиной · слить синонимом в принятый термин.
 */
import { useEffect, useState } from 'react'
import { Вкладки, useВкладка } from './ui/tabs'
import { api, type GlossaryTerm, type SearchHit } from './api'

export function GlossaryScreen({ project }: { project: string | null }) {
  const [термины, setТермины] = useState<GlossaryTerm[]>([])
  const [классы, setКлассы] = useState<Record<string, string>>({})
  const [заметка, setЗаметка] = useState('')
  const [игла, setИгла] = useState('')
  const [класс, setКласс] = useState('')
  const [статус, setСтатус] = useState('')
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  const [новый, setНовый] = useState({ term_ru: '', class: 'se_concept', definition: '', synonyms: '' })
  const [причины, setПричины] = useState<Record<string, string>>({})
  const [куда, setКуда] = useState<Record<string, string>>({})
  const [тик, setТик] = useState(0)
  /** Рубрикатор по буквам (КТ2: «одной простынёй неудобно»): буква — отбор по первой букве термина. */
  const [буква, setБуква] = useState('')
  const [источник, setИсточник] = useState('')
  const [привязка, setПривязка] = useState<string | null>(null)
  /** Принятый словарь виден сразу; непринятые (кандидаты) — своей вкладкой (владелец, 24.09). */
  const [вкладка, setВкладка] = useВкладка<'словарь' | 'кандидаты'>('glossary', 'словарь', ['словарь', 'кандидаты'])

  useEffect(() => {
    let живо = true
    // Без проекта — словарь класса миссии (библиотека): дельты проекта нет.
    api.glossary(project ?? '', игла, класс, статус)
      .then((о) => { if (!живо) return; setТермины(о.items); setКлассы(о.classes); setЗаметка(о.note); setОтказ(null) })
      .catch((e) => { if (живо) setОтказ(String(e.message ?? e)) })
    return () => { живо = false }
  }, [project, игла, класс, статус, тик])

  const перечитать = () => setТик((т) => т + 1)
  const кандидаты = термины.filter((т) => т.status === 'candidate')
  const словоКласса = (т: GlossaryTerm) => т.class_word ?? классы[т.class] ?? т.class
  const буквы = буквыТерминов(термины)
  const источники = [...new Set(термины.map((т) => источникКоротко(т.source)).filter(Boolean))].sort()
  const принятые = термины.filter((т) => т.status !== 'candidate')
    .filter((т) => !буква || перваяБуква(т.term_ru) === буква)
    .filter((т) => !источник || источникКоротко(т.source) === источник)
    .sort((а, б) => а.term_ru.localeCompare(б.term_ru, 'ru'))

  /** Стороны и узлы проекта, заведённые до словаря, — привязать: термин или кандидат. */
  const привязать = () => {
    if (!project) return
    setЗанято(true); setОтказ(null)
    api.glossaryLink(project)
      .then((о) => { setПривязка(о.note + (о.notes.length ? ' — ' + о.notes.slice(0, 3).join('; ') + (о.notes.length > 3 ? ' …' : '') : '')); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  const решить = (т: GlossaryTerm, действие: 'accept' | 'reject' | 'merge') => {
    setЗанято(true); setОтказ(null)
    api.glossaryDecide(project ?? '', т.code, действие, {
      author: 'инженер',
      reason: причины[т.code] || undefined,
      into: действие === 'merge' ? куда[т.code] : undefined,
    })
      .then(() => { setЗанято(false); перечитать() })
      .catch((e) => { setЗанято(false); setОтказ(String(e.message ?? e)) })
  }

  const добавить = () => {
    setЗанято(true); setОтказ(null)
    api.glossaryAdd(project ?? '', {
      term_ru: новый.term_ru.trim(), class: новый.class, definition: новый.definition.trim(), author: 'инженер',
      synonyms: новый.synonyms.split(/[;\n]/).map((с) => с.trim()).filter(Boolean),
    })
      .then(() => { setЗанято(false); setНовый({ term_ru: '', class: 'se_concept', definition: '', synonyms: '' }); перечитать() })
      .catch((e) => { setЗанято(false); setОтказ(String(e.message ?? e)) })
  }

  return (
    <div className="v2-screen" data-why="работа">
      <h2>Словарь</h2>
      <div className="v2-note-line">{заметка}</div>
      {отказ && <div className="v2-locked">{отказ}</div>}
      {project && <ПоискПоБазе project={project} />}

      <div className="v2-form v2-form--row">
        <input value={игла} placeholder="термин, синоним, код объекта, определение"
          onChange={(e) => setИгла(e.target.value)} aria-label="поиск по словарю" />
        <select value={класс} onChange={(e) => setКласс(e.target.value)} aria-label="класс термина">
          <option value="">— любой класс —</option>
          {Object.entries(классы).map(([к, слово]) => <option key={к} value={к}>{слово}</option>)}
        </select>
        <select value={статус} onChange={(e) => setСтатус(e.target.value)} aria-label="статус термина">
          <option value="">— любой статус —</option>
          <option value="accepted">принятые</option>
          <option value="candidate">кандидаты</option>
          <option value="obsolete">устаревшие</option>
        </select>
        <select value={источник} onChange={(e) => setИсточник(e.target.value)} aria-label="источник термина">
          <option value="">— любой источник —</option>
          {источники.map((и) => <option key={и} value={и}>{и}</option>)}
        </select>
        {project && (
          <button type="button" onClick={привязать} disabled={занято}
            title="стороны и узлы проекта, заведённые до словаря: каждой — термин по имени или коду, незнакомым — кандидат">
            Привязать стороны и узлы проекта
          </button>
        )}
      </div>
      {привязка && <div className="v2-note-line">{привязка}</div>}
      {/* Принятый словарь виден сразу, непринятые — своей вкладкой (владелец, 24.09); вкладки — шип 5 §1.1. */}
      <Вкладки label="вкладки словаря" current={вкладка} onChange={setВкладка}
        items={[
          { key: 'словарь', word: 'Принятые', count: термины.filter((т) => т.status === 'accepted').length,
            hint: 'принятые термины класса миссии и проекта' },
          { key: 'кандидаты', word: 'Кандидаты', count: кандидаты.length, health: кандидаты.length > 0 ? 'debt' : 'ok',
            hint: кандидаты.length > 0 ? `кандидатов ${кандидаты.length}: ждут решения — принять, отклонить, слить` : 'кандидатов нет' },
        ]} />
      {/* Рубрикатор — отбор по первой букве чипами, не меню. */}
      <div className="v2-chips" data-why="работа" aria-label="рубрикатор по буквам">
        <button type="button" className={буква ? 'v2-chip' : 'v2-chip v2-chip--on'} aria-pressed={!буква} onClick={() => setБуква('')}
          title="все термины по алфавиту">все</button>
        {буквы.map((б) => (
          <button key={б} type="button" className={буква === б ? 'v2-chip v2-chip--on' : 'v2-chip'} aria-pressed={буква === б}
            onClick={() => setБуква(буква === б ? '' : б)} title={`термины на «${б}»`}>{б}</button>
        ))}
      </div>

      {вкладка === 'кандидаты' && кандидаты.length === 0 && (
        <div className="v2-empty">Кандидатов нет.
          <span className="v2-empty__why">Они приходят из разбора документов и привязки сторон и узлов проекта.</span></div>
      )}
      {вкладка === 'кандидаты' && кандидаты.length > 0 && (
        <section className="v2-card" data-why="почему-нельзя">
          <h3>Кандидаты из документов ({кандидаты.length})</h3>
          <div className="v2-muted">новый термин не проходит мимо: принять как есть, отклонить с причиной или слить синонимом в принятый</div>
          <table className="v2-table">
            <thead><tr><th>Код</th><th>Написание</th><th>Класс</th><th>Цитата</th><th>Решение</th></tr></thead>
            <tbody>
              {кандидаты.map((т) => (
                <tr key={т.code}>
                  <td className="v2-mono">{т.code}</td>
                  <td><b>{т.term_ru}</b></td>
                  <td>{словоКласса(т)}</td>
                  <td className="v2-muted">{т.quote ? `«${т.quote}»` : '—'}</td>
                  <td>
                    <button type="button" className="v2-link" disabled={занято}
                      title="принять термин как есть — определение уточните правкой на месте"
                      onClick={() => решить(т, 'accept')}>принять</button>
                    {' · '}
                    <input value={причины[т.code] ?? ''} placeholder="почему отклонить" aria-label="причина отклонения"
                      onChange={(e) => setПричины({ ...причины, [т.code]: e.target.value })} />
                    <button type="button" className="v2-link" disabled={занято || !(причины[т.code] ?? '').trim()}
                      title="отклонить кандидата: запись остаётся с причиной, из словаря уходит"
                      onClick={() => решить(т, 'reject')}>отклонить</button>
                    {' · '}
                    <select value={куда[т.code] ?? ''} aria-label="принятый термин, в который слить"
                      onChange={(e) => setКуда({ ...куда, [т.code]: e.target.value })}>
                      <option value="">— слить в… —</option>
                      {принятые.filter((п) => п.status === 'accepted').map((п) => (
                        <option key={п.code} value={п.code}>{п.term_ru}</option>
                      ))}
                    </select>
                    <button type="button" className="v2-link" disabled={занято || !куда[т.code]}
                      title="написание станет синонимом принятого термина; кандидат снимается"
                      onClick={() => решить(т, 'merge')}>слить</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      {вкладка === 'словарь' && <>
      <div className="v2-note-line">{`показано ${принятые.length} из ${термины.filter((т) => т.status !== 'candidate').length}${буква ? ` · буква «${буква}»` : ''}${источник ? ` · источник «${источник}»` : ''}`}</div>
      <table className="v2-table">
        <thead><tr><th>Код</th><th>Термин</th><th>Класс</th><th>Синонимы</th><th>Определение</th><th>Источник</th><th>Где</th></tr></thead>
        <tbody>
          {принятые.map((т) => (
            <tr key={т.code} className={т.status === 'obsolete' ? 'v2-dim' : undefined}>
              <td className="v2-mono">{т.code}{т.area === 'project' ? <span className="v2-muted"> · проект</span> : null}</td>
              <td><b>{т.term_ru}</b>{т.term_en ? <span className="v2-muted"> · {т.term_en}</span> : null}
                {т.object_code ? <span className="v2-mono"> · {т.object_code}</span> : null}</td>
              <td>{словоКласса(т)}</td>
              <td className="v2-muted">{(т.synonyms ?? []).join(' · ')}</td>
              <td>{т.definition}
                {т.difference ? <div className="v2-muted">отличие: {т.difference}</div> : null}
                {т.not_to_confuse ? <div className="v2-muted">не путать: {т.not_to_confuse}</div> : null}</td>
              <td className="v2-muted">{т.source ?? ''}</td>
              <td className="v2-muted">{(т.used_in ?? []).join(' · ')}{т.status === 'obsolete' ? ' · устарел' : ''}</td>
            </tr>
          ))}
          {принятые.length === 0 && (
            <tr><td colSpan={7} className="v2-empty">
              Терминов не найдено.
              <span className="v2-empty__why">Сид словаря ложится загрузчиком полок; кандидаты приходят из разбора документов.</span>
            </td></tr>
          )}
        </tbody>
      </table>
      </>}

      {project && <div className="v2-form v2-form--row" data-why="работа">
        <input value={новый.term_ru} placeholder="каноническое имя термина" aria-label="термин"
          onChange={(e) => setНовый({ ...новый, term_ru: e.target.value })} />
        <select value={новый.class} aria-label="класс нового термина" onChange={(e) => setНовый({ ...новый, class: e.target.value })}>
          {Object.entries(классы).map(([к, слово]) => <option key={к} value={к}>{слово}</option>)}
        </select>
        <input value={новый.definition} placeholder="определение — одна-три строки" aria-label="определение"
          onChange={(e) => setНовый({ ...новый, definition: e.target.value })} />
        <input value={новый.synonyms} placeholder="синонимы через ;" aria-label="синонимы"
          onChange={(e) => setНовый({ ...новый, synonyms: e.target.value })} />
        <button type="button" className="v2-primary" onClick={добавить}
          disabled={занято || !новый.term_ru.trim() || !новый.definition.trim()}
          title="термин проекта — принятый сразу: решение владельца словаря">Добавить термин</button>
      </div>}
    </div>
  )
}

/**
 * База знаний (шип 4 §2): гибридный поиск по блокам документов, терминам,
 * пунктам нормативов и фактам с фильтрами по роли документа и рангу; индекс
 * перестраивается кнопкой — после разбора документа он перестраивается сам.
 */
function ПоискПоБазе({ project }: { project: string }) {
  const [слова, setСлова] = useState('')
  const [роль, setРоль] = useState('')
  const [ранг, setРанг] = useState('')
  const [вид, setВид] = useState('')
  const [находки, setНаходки] = useState<SearchHit[]>([])
  const [заметка, setЗаметка] = useState('')
  const [вектор, setВектор] = useState(false)
  const [занято, setЗанято] = useState(false)
  const [отказ, setОтказ] = useState<string | null>(null)

  const искать = () => {
    if (!слова.trim()) return
    setЗанято(true); setОтказ(null)
    api.search(project, слова.trim(), { role: роль, rank: ранг }, вид ? [вид] : [])
      .then((о) => { setНаходки(о.items); setЗаметка(о.note); setВектор(о.vector) })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }
  const перестроить = () => {
    setЗанято(true); setОтказ(null)
    api.indexRebuild(project)
      .then((о) => setЗаметка(о.note))
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <section className="v2-card" data-why="работа">
      <h3>Поиск по базе знаний</h3>
      <div className="v2-form v2-form--row">
        <input value={слова} placeholder="интервал передачи" aria-label="что искать в базе знаний"
          onChange={(e) => setСлова(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') искать() }} />
        <select value={роль} onChange={(e) => setРоль(e.target.value)} aria-label="роль документа">
          <option value="">— роль документа —</option>
          <option value="charter">устав</option><option value="tor">источник требований</option>
          <option value="regulatory">норматив</option><option value="context">обстановка</option>
          <option value="supplier">поставщик</option><option value="heritage">наследие</option>
        </select>
        <select value={ранг} onChange={(e) => setРанг(e.target.value)} aria-label="ранг доверия">
          <option value="">— ранг —</option>
          <option value="mandatory">обязательный</option><option value="expert">экспертный</option>
          <option value="reference">справочный</option><option value="doubtful">сомнительный</option>
        </select>
        <select value={вид} onChange={(e) => setВид(e.target.value)} aria-label="что искать">
          <option value="">— всё —</option>
          <option value="canon_block">блоки документов</option><option value="term">термины</option>
          <option value="clause">пункты нормативов</option><option value="fact">факты</option>
        </select>
        <button type="button" className="v2-primary" onClick={искать} disabled={занято || !слова.trim()}
          title="слова — по русской морфологии; смысл — вектором, если провайдер эмбеддингов задан">Искать</button>
        <button type="button" onClick={перестроить} disabled={занято}
          title="перестроить индекс проекта: изменённые блоки перезаписать, исчезнувшие снять">Перестроить индекс</button>
      </div>
      {заметка && <div className="v2-note-line">{заметка}{вектор ? ' · вектор есть' : ''}</div>}
      {отказ && <div className="v2-locked">{отказ}</div>}
      {находки.length > 0 && (
        <table className="v2-table">
          <thead><tr><th>Что</th><th>Откуда</th><th>Отрывок</th><th>Оценка</th></tr></thead>
          <tbody>
            {находки.map((н) => (
              <tr key={н.kind + н.ref + н.title}>
                <td>{н.kind_word}</td>
                <td><span className="v2-mono">{н.ref}</span> <span className="v2-muted">{н.title}</span></td>
                <td>{н.snippet}</td>
                <td className="v2-muted">{н.score.toFixed(2)}{typeof н.semantic === 'number' ? ` · смысл ${н.semantic.toFixed(2)}` : ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}

/** Первая буква термина заглавной — для рубрикатора; цифры и латиница — своими группами. */
export function перваяБуква(термин: string): string {
  const с = (термин ?? '').trim().charAt(0).toUpperCase()
  if (!с) return '—'
  if (/[0-9]/.test(с)) return '0–9'
  return с
}

/** Буквы, на которые есть термины, по алфавиту: кириллица, потом латиница, потом цифры. */
export function буквыТерминов(термины: { term_ru: string; status: string }[]): string[] {
  const есть = new Set(термины.filter((т) => т.status !== 'candidate').map((т) => перваяБуква(т.term_ru)))
  const вес = (б: string) => (/[А-ЯЁ]/.test(б) ? 0 : /[A-Z]/.test(б) ? 1 : 2)
  return [...есть].sort((а, б) => вес(а) - вес(б) || а.localeCompare(б, 'ru'))
}

/** Источник коротко: «NASA SEH App. B» → «NASA SEH», «ПОЛКА-PBS» → «ПОЛКА-PBS». */
export function источникКоротко(источник?: string): string {
  const и = (источник ?? '').trim()
  if (!и) return ''
  if (/^NASA/i.test(и)) return 'NASA SEH'
  return и.split(/[(:—–]/)[0].trim().slice(0, 40)
}
