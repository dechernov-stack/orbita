/**
 * Словарь (шип 4 §2, РЕШЕНИЕ-СЛОВАРЬ-И-БАЗА-ЗНАНИЙ): термины класса миссии
 * (библиотека) и дельта проекта; кандидаты из документов — с цитатой, решает
 * человек: принять · отклонить с причиной · слить синонимом в принятый термин.
 *
 * Шип 5 §7: вкладки Принятые · Кандидаты · NASA SEH (§1.1); принятые —
 * общим реестром с карточкой термина (§1.3: определение · синонимы · «не
 * путать с» · где используется · код объекта); кандидаты — полосами по
 * источнику, решения массово (§1.4). Поиск по базе знаний — вкладка «Индекс»
 * поля знаний, со словаря снят.
 */
import { Fragment, useContext, useEffect, useMemo, useState } from 'react'
import { ConfirmBox, useConfirm } from '../ui/Confirm'
import { Вкладки, useВкладка } from './ui/tabs'
import { api, type EntityRow, type GlossaryTerm, type KindSpec, type SearchHit } from './api'
import { Реестр, type Колонка } from './registry/registry'
import { Полоса, РаскрытьВсе, useПолосы } from './ui/band'
import { ИконКнопка } from './ui/iconbutton'
import { Отметка, ПанельМассово, useМассово, type МассовоеДействие } from './ui/mass'
import { Карточка } from './ui/objectcard'
import { ПлотностьКонтекст } from './ui/density'

/** Термины NASA SEH (приложение B справочника) — своей вкладкой: опорный словарь, не наш. */
export function изNasaSeh(т: { source?: string }): boolean {
  return (т.source ?? '').startsWith('NASA SEH')
}

/** Термин как запись для карточки объекта: грани — поля вида «термин словаря» по истине. */
export function строкаТермина(т: GlossaryTerm): EntityRow {
  return {
    id: т.id ?? т.code, code: т.code, status: т.status,
    doc: {
      term_ru: т.term_ru, term_en: т.term_en, synonyms: т.synonyms, class: т.class, definition: т.definition,
      difference: т.difference, not_to_confuse: т.not_to_confuse, source: т.source, used_in: т.used_in, object_code: т.object_code,
    },
  }
}

type ВкладкаСловаря = 'словарь' | 'кандидаты' | 'seh'

export function GlossaryScreen({ project }: { project: string | null }) {
  const [термины, setТермины] = useState<GlossaryTerm[]>([])
  const [классы, setКлассы] = useState<Record<string, string>>({})
  const [заметка, setЗаметка] = useState('')
  const [игла, setИгла] = useState('')
  const [класс, setКласс] = useState('')
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  const [новый, setНовый] = useState({ term_ru: '', class: 'se_concept', definition: '', synonyms: '' })
  const [тик, setТик] = useState(0)
  /** Рубрикатор по буквам (КТ2: «одной простынёй неудобно»): буква — отбор по первой букве термина. */
  const [буква, setБуква] = useState('')
  const [источник, setИсточник] = useState('')
  const [привязка, setПривязка] = useState<string | null>(null)
  const [итог, setИтог] = useState<string | null>(null)
  const [вид, setВид] = useState<KindSpec | null>(null)
  /** Принятый словарь виден сразу; непринятые (кандидаты) — своей вкладкой (владелец, 24.09). */
  const [вкладка, setВкладка] = useВкладка<ВкладкаСловаря>('glossary', 'словарь', ['словарь', 'кандидаты', 'seh'])
  const [ask, спросить, закрытьВопрос] = useConfirm()
  const { кто } = useContext(ПлотностьКонтекст)

  useEffect(() => {
    let живо = true
    // Без проекта — словарь класса миссии (библиотека): дельты проекта нет.
    api.glossary(project ?? '', игла, класс, '')
      .then((о) => { if (!живо) return; setТермины(о.items); setКлассы(о.classes); setЗаметка(о.note); setОтказ(null) })
      .catch((e) => { if (живо) setОтказ(String(e.message ?? e)) })
    return () => { живо = false }
  }, [project, игла, класс, тик])
  useEffect(() => { api.kind('glossary_term').then(setВид).catch(() => setВид(null)) }, [])

  const перечитать = () => setТик((т) => т + 1)
  const кандидаты = термины.filter((т) => т.status === 'candidate')
  const словоКласса = (т: GlossaryTerm) => т.class_word ?? классы[т.class] ?? т.class
  const непринятые = new Set(кандидаты.map((т) => т.code))
  const свои = термины.filter((т) => !непринятые.has(т.code) && !изNasaSeh(т))
  const seh = термины.filter((т) => !непринятые.has(т.code) && изNasaSeh(т))
  const набор = вкладка === 'seh' ? seh : свои
  const буквы = буквыТерминов(набор)
  const источники = [...new Set(набор.map((т) => источникКоротко(т.source)).filter(Boolean))].sort()
  const показаны = набор
    .filter((т) => !буква || перваяБуква(т.term_ru) === буква)
    .filter((т) => !источник || источникКоротко(т.source) === источник)
    .sort((а, б) => а.term_ru.localeCompare(б.term_ru, 'ru'))
  const принятые = термины.filter((т) => т.status === 'accepted')

  /** Стороны и узлы проекта, заведённые до словаря, — привязать: термин или кандидат. */
  const привязать = () => {
    if (!project) return
    setЗанято(true); setОтказ(null)
    api.glossaryLink(project)
      .then((о) => { setПривязка(о.note + (о.notes.length ? ' — ' + о.notes.slice(0, 3).join('; ') + (о.notes.length > 3 ? ' …' : '') : '')); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  /** Решения по кандидатам — по одному вызову на термин: отказ одного не отменяет принятого. */
  const решить = (коды: string[], действие: 'accept' | 'reject' | 'merge', причина?: string, into?: string) => {
    setЗанято(true); setОтказ(null); setИтог(null)
    Promise.allSettled(коды.map((к) => api.glossaryDecide(project ?? '', к, действие, { author: кто || 'инженер', reason: причина, into })))
      .then((итоги) => {
        const отказы = итоги.filter((и) => и.status === 'rejected') as PromiseRejectedResult[]
        const слово = действие === 'accept' ? 'принято' : действие === 'reject' ? 'отклонено' : 'слито'
        setИтог(отказы.length === 0 ? `${слово}: ${коды.length}` : `${слово}: ${коды.length - отказы.length} · отказов ${отказы.length} — ${String(отказы[0].reason?.message ?? отказы[0].reason)}`)
        перечитать()
      })
      .finally(() => setЗанято(false))
  }
  const отклонить = (коды: string[]) => спросить({
    question: `Отклонить ${коды.length === 1 ? `кандидата ${коды[0]}` : `кандидатов (${коды.length})`}: запись остаётся с причиной, из словаря уходит.`,
    ok: 'Отклонить', input: { label: 'почему отклонить', required: true },
    onOk: (причина) => решить(коды, 'reject', причина),
  })
  const слить = (коды: string[]) => спросить({
    question: `Слить ${коды.length === 1 ? коды[0] : `кандидатов (${коды.length})`} синонимом в принятый термин: кандидат снимается, написание становится синонимом.`,
    ok: 'Слить',
    choice: { label: 'принятый термин', options: принятые.map((п) => [п.code, п.term_ru]) },
    onOk: (куда) => решить(коды, 'merge', undefined, куда),
  })

  const добавить = () => {
    setЗанято(true); setОтказ(null)
    api.glossaryAdd(project ?? '', {
      term_ru: новый.term_ru.trim(), class: новый.class, definition: новый.definition.trim(), author: кто || 'инженер',
      synonyms: новый.synonyms.split(/[;\n]/).map((с) => с.trim()).filter(Boolean),
    })
      .then(() => { setЗанято(false); setНовый({ term_ru: '', class: 'se_concept', definition: '', synonyms: '' }); перечитать() })
      .catch((e) => { setЗанято(false); setОтказ(String(e.message ?? e)) })
  }

  const колонки: Колонка<GlossaryTerm>[] = [
    { key: 'код', title: 'Код', cell: (т) => <>{т.code}{т.area === 'project' ? <span className="v2-dim"> · проект</span> : null}</>, className: 'v2-mono' },
    { key: 'термин', title: 'Термин', cell: (т) => <><b>{т.term_ru}</b>{т.term_en ? <span className="v2-dim"> · {т.term_en}</span> : null}</> },
    { key: 'класс', title: 'Класс', cell: словоКласса },
    { key: 'синонимы', title: 'Синонимы', cell: (т) => <span className="v2-dim">{(т.synonyms ?? []).join(' · ')}</span> },
    { key: 'определение', title: 'Определение', cell: (т) => т.definition },
    { key: 'источник', title: 'Источник', cell: (т) => <span className="v2-dim">{т.source ?? ''}</span> },
  ]

  return (
    <div className="v2-screen" data-why="работа">
      <h2>Словарь</h2>
      {отказ && <div className="v2-locked">{отказ}</div>}
      {/* Принятый словарь виден сразу, непринятые — своей вкладкой (владелец, 24.09); вкладки — шип 5 §1.1. */}
      <Вкладки label="вкладки словаря" current={вкладка} onChange={(к) => { setВкладка(к); setБуква(''); setИсточник('') }}
        items={[
          { key: 'словарь', word: 'Принятые', count: свои.length, hint: 'принятые термины класса миссии и проекта' },
          { key: 'кандидаты', word: 'Кандидаты', count: кандидаты.length, health: кандидаты.length > 0 ? 'debt' : 'ok',
            hint: кандидаты.length > 0 ? `кандидатов ${кандидаты.length}: ждут решения — принять, отклонить, слить` : 'кандидатов нет' },
          { key: 'seh', word: 'NASA SEH', count: seh.length, hint: 'опорный словарь NASA SEH (приложение B): термины справочника, не наши' },
        ]} />
      <div className="v2-form v2-form--row">
        {/* Как ищется — подсказкой поля поиска, а не строкой над вкладками (шип 5 §7: строка повторяла плейсхолдер). */}
        <input value={игла} placeholder="термин, синоним, код объекта, определение" title={заметка || undefined}
          onChange={(e) => setИгла(e.target.value)} aria-label="поиск по словарю" />
        <select value={класс} onChange={(e) => setКласс(e.target.value)} aria-label="класс термина">
          <option value="">— любой класс —</option>
          {Object.entries(классы).map(([к, слово]) => <option key={к} value={к}>{слово}</option>)}
        </select>
        {вкладка !== 'кандидаты' && (
          <select value={источник} onChange={(e) => setИсточник(e.target.value)} aria-label="источник термина">
            <option value="">— любой источник —</option>
            {источники.map((и) => <option key={и} value={и}>{и}</option>)}
          </select>
        )}
        {project && (
          <button type="button" onClick={привязать} disabled={занято}
            title="стороны и узлы проекта, заведённые до словаря: каждой — термин по имени или коду, незнакомым — кандидат">
            Привязать стороны и узлы проекта
          </button>
        )}
      </div>
      {привязка && <div className="v2-note-line">{привязка}</div>}
      {итог && <div className="v2-note-line">{итог}</div>}

      {вкладка !== 'кандидаты' && (
        <>
          {/* Рубрикатор — отбор по первой букве чипами, не меню. */}
          <div className="v2-chips" data-why="работа" aria-label="рубрикатор по буквам">
            <button type="button" className={буква ? 'v2-chip' : 'v2-chip v2-chip--on'} aria-pressed={!буква} onClick={() => setБуква('')}
              title="все термины по алфавиту">все</button>
            {буквы.map((б) => (
              <button key={б} type="button" className={буква === б ? 'v2-chip v2-chip--on' : 'v2-chip'} aria-pressed={буква === б}
                onClick={() => setБуква(буква === б ? '' : б)} title={`термины на «${б}»`}>{б}</button>
            ))}
          </div>
          <Реестр label={вкладка === 'seh' ? 'словарь NASA SEH' : 'принятые термины'} строки={показаны} ключ={(т) => т.code} колонки={колонки}
            пусто={<>Терминов не найдено.<span className="v2-empty__why">Сид словаря ложится загрузчиком полок; кандидаты приходят из разбора документов.</span></>}
            карточка={(т, закрыть) => вид
              ? <Карточка project={project ?? ''} row={строкаТермина(т)} spec={вид} заголовок={т.term_ru} onSaved={перечитать} onClose={закрыть}
                  // Термин библиотеки класса миссии правится в поставке, а не здесь: у проекта — только своя дельта.
                  толькоЧтение={т.area === 'library' ? вид.fields : []} />
              : <div className="v2-empty">Читаю истину вида…</div>} />
        </>
      )}

      {вкладка === 'кандидаты' && (
        <Кандидаты кандидаты={кандидаты} словоКласса={словоКласса} занято={занято}
          onAccept={(коды) => решить(коды, 'accept')} onReject={отклонить} onMerge={слить} />
      )}

      {project && вкладка === 'словарь' && <div className="v2-form v2-form--row" data-why="работа">
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
      <ConfirmBox request={ask} onClose={закрытьВопрос} />
    </div>
  )
}

/**
 * Кандидаты полосами по источнику (шип 5 §7): новый термин не проходит мимо —
 * принять как есть, отклонить с причиной или слить синонимом в принятый;
 * массово — теми же тремя решениями, клавиши A · R.
 */
function Кандидаты({ кандидаты, словоКласса, занято, onAccept, onReject, onMerge }: {
  кандидаты: GlossaryTerm[]
  словоКласса: (т: GlossaryTerm) => string
  занято: boolean
  onAccept: (коды: string[]) => void
  onReject: (коды: string[]) => void
  onMerge: (коды: string[]) => void
}) {
  const группы = useMemo(() => {
    const по = new Map<string, GlossaryTerm[]>()
    кандидаты.forEach((т) => { const и = источникКоротко(т.source) || 'без источника'; по.set(и, [...(по.get(и) ?? []), т]) })
    return [...по.entries()].map(([источник, термины]) => ({ источник, термины }))
  }, [кандидаты])
  const полосы = useПолосы(группы.map((г) => г.источник))
  const ключи = группы.flatMap((г) => (полосы.открыта(г.источник) ? г.термины.map((т) => т.code) : []))
  const массово: МассовоеДействие[] = [
    { key: 'принять', икон: 'принять', слово: 'принять', клавиша: 'A', run: onAccept, disabled: занято },
    { key: 'отклонить', икон: 'отклонить', слово: 'отклонить с причиной', клавиша: 'R', run: onReject, disabled: занято },
    { key: 'слить', икон: 'связать', слово: 'слить в принятый', run: onMerge, disabled: занято },
  ]
  const м = useМассово(ключи, { действия: массово })
  if (кандидаты.length === 0) {
    return <div className="v2-empty">Кандидатов нет.<span className="v2-empty__why">Они приходят из разбора документов и привязки сторон и узлов проекта.</span></div>
  }
  return (
    <div data-why="почему-нельзя" aria-label="кандидаты словаря">
      {группы.length > 1 && <div className="v2-bands__all"><РаскрытьВсе все={полосы.все} число={полосы.число} onClick={полосы.всеРазом} /></div>}
      {группы.map((г) => (
        <Полоса key={г.источник} open={полосы.открыта(г.источник)} onToggle={() => полосы.переключить(г.источник)}
          subject={г.источник} counts={`кандидатов ${г.термины.length}`}
          actions={<ИконКнопка икон="принять" слово={`принять все из «${г.источник}»: ${г.термины.length}`} disabled={занято}
            onClick={() => onAccept(г.термины.map((т) => т.code))} />}>
          <div className="v2-reg__table" tabIndex={0} onKeyDown={м.клавиша} aria-label="кандидаты группы: ↑ ↓ строка, Space отметить">
            <table className="v2-table">
              <thead><tr><th className="v2-reg__mark" /><th>Код</th><th>Написание</th><th>Класс</th><th>Цитата</th><th className="v2-acts" aria-label="действия строки" /></tr></thead>
              <tbody>
                {г.термины.map((т) => (
                  <Fragment key={т.code}>
                    <tr className={ключи[м.курсор] === т.code ? 'v2-row--cursor' : undefined} onClick={() => м.setКурсор(ключи.indexOf(т.code))}>
                      <td className="v2-reg__mark"><Отметка ключ={т.code} выбрана={м.выбраны.has(т.code)} onToggle={м.отметить} /></td>
                      <td className="v2-mono">{т.code}</td>
                      <td><b>{т.term_ru}</b></td>
                      <td>{словоКласса(т)}</td>
                      <td className="v2-dim">{т.quote ? `«${т.quote}»` : '—'}</td>
                      <td className="v2-acts">
                        <ИконКнопка икон="принять" слово="принять как есть" disabled={занято} onClick={() => onAccept([т.code])} />
                        <ИконКнопка икон="отклонить" слово="отклонить с причиной" disabled={занято} onClick={() => onReject([т.code])} />
                        <ИконКнопка икон="связать" слово="слить синонимом в принятый" disabled={занято} onClick={() => onMerge([т.code])} />
                      </td>
                    </tr>
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        </Полоса>
      ))}
      <ПанельМассово выбранные={м.выбранные} действия={массово} onСнять={м.снять} />
    </div>
  )
}

/**
 * База знаний (шип 4 §2): гибридный поиск по блокам документов, терминам,
 * пунктам нормативов и фактам с фильтрами по роли документа и рангу; индекс
 * перестраивается кнопкой — после разбора документа он перестраивается сам.
 */
export function ПоискПоБазе({ project }: { project: string }) {
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
