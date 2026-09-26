// Вкладка «Документы» поля знаний (шип 5 §3.1).
//
// Таблица: код · документ · роль словом · ранг · фактов · последний прогон ·
// вклад (что документ дал: 18 сторон · 29 нужд …) · действия пиктограммами —
// досье · прочитать в постановку · разобрать по заданию · загрузить версию ·
// снять вклад; у устава ещё карта пробелов. Досье раскрывается карточкой
// вниз от строки. «Загрузить» в шапке вкладки открывает форму источника с
// партией (несколько файлов, роль у каждого).
//
// Чтение идёт по ДОКУМЕНТУ и даёт постановку сразу — стороны, нужды, цели,
// рамки, вехи, у каждого пункта цитата и якорь (до 16.09 чтение начиналось
// только с формы загрузки, и владелец жал «Сформировать постановку из поля»:
// срез в 50 элементов давал 26 предложений, 18 из них — «узнанное
// принятое»). Прочитанное ведёт во вкладку «Предложения» с отбором по
// прогону. Числа «фактов», «последний прогон» и «вклад» — из данных: факты
// поля и досье документа, клиент их не выдумывает.
import { Fragment, useCallback, useEffect, useState } from 'react'
import { ConfirmBox, useConfirm } from '../../ui/Confirm'
import { countPhrase, видЗнаком } from '../../ui/countPhrase'
import { api, РЕЖИМЫ_ПРИЁМА, РОЛИ_ДОКУМЕНТА, type Dossier, type DocumentRole, type FactRow, type MaterialRow, type TaskPlan } from '../api'
import { ВзятьИзПроекта, Досье, КартаПробелов } from '../dossier'
import { ИконКнопка } from '../ui/iconbutton'
import { СЛОВО_ПРОГОНА } from '../words'
import { РАНГ } from './common'
import { ЗаметкиПриёма, ПланРазбора } from './plan'
import { Source } from './source'

/** Форма числа по-русски: 1 сторона · 3 стороны · 18 сторон. Вёрстка, не расчёт. */
function склонение(n: number, формы: [string, string, string]): string {
  const д10 = n % 10
  const д100 = n % 100
  const форма = д10 === 1 && д100 !== 11 ? формы[0] : д10 >= 2 && д10 <= 4 && (д100 < 12 || д100 > 14) ? формы[1] : формы[2]
  return `${n} ${форма}`
}

/** «Вклад» документа словами: сущности, которые на нём стоят, по видам. */
export function вкладСловами(сущности: { kind: string }[]): string {
  const по = new Map<string, number>()
  сущности.forEach((с) => по.set(с.kind, (по.get(с.kind) ?? 0) + 1))
  return [...по.entries()].map(([вид, n]) => (
    вид === 'stakeholder' ? склонение(n, ['сторона', 'стороны', 'сторон'])
      : видЗнаком(вид) ? countPhrase(вид, n) : склонение(n, ['запись', 'записи', 'записей'])
  )).join(' · ')
}

/** Последний прогон разбора: слово статуса и дата. */
export function последнийПрогон(досье: Dossier | null | undefined): string | null {
  const прогоны = [...(досье?.runs ?? [])].sort((а, б) => б.at.localeCompare(а.at))
  const п = прогоны[0]
  if (!п) return null
  const [г, м, д] = п.at.slice(0, 10).split('-')
  return `${СЛОВО_ПРОГОНА[п.status] ?? п.status} · ${д}.${м}.${г}`
}

const РОЛЬ_СЛОВОМ = Object.fromEntries(РОЛИ_ДОКУМЕНТА.map((р) => [р.code, р.word]))

export function ВкладкаДокументы({ project, факты, onRead, onChanged, onError, onGoGlossary }: {
  project: string
  /** Факты поля: число «фактов» у документа — по ним. */
  факты: FactRow[]
  /** Документ прочитан: экран уходит во вкладку «Предложения» с отбором по прогону. */
  onRead: (run: string, note: string) => void
  /** Поле изменилось (разбор, приём, откат): экрану пора перечитать факты. */
  onChanged: () => void
  onError: (e: string) => void
  /** Заметки приёма со словарём ведут к месту — в словарь. */
  onGoGlossary?: () => void
}) {
  const [список, setСписок] = useState<MaterialRow[]>([])
  const [досье, setДосье] = useState<Record<string, Dossier | null>>({})
  const [открыт, setОткрыт] = useState<{ code: string; что: 'досье' | 'карта' } | null>(null)
  const [читаю, setЧитаю] = useState<{ code: string; sec: number } | null>(null)
  const [форма, setФорма] = useState<{ версия: string } | null>(null)
  const [план, setПлан] = useState<TaskPlan | null>(null)
  const [заметки, setЗаметки] = useState<string[]>([])
  const [итог, setИтог] = useState<string | null>(null)
  const [ask, спросить, закрытьВопрос] = useConfirm()

  const перечитать = useCallback(() => {
    api.materials(project).then((r) => {
      setСписок(r.items)
      // Досье — у каждого документа: последний прогон и вклад. Документов в
      // проекте единицы, а досье считает сервер — клиент только показывает.
      Promise.all(r.items.map((м) => api.dossier(project, м.code).then((д) => [м.code, д] as const).catch(() => [м.code, null] as const)))
        .then((пары) => setДосье(Object.fromEntries(пары)))
    }).catch(() => setСписок([]))
  }, [project])
  useEffect(перечитать, [перечитать])

  const изменилось = () => { перечитать(); onChanged() }
  const фактов = (код: string) => факты.filter((ф) => !ф.manual && ф.material === код).length

  const прочитатьКак = (м: MaterialRow, роль: DocumentRole | null) => {
    setЧитаю({ code: м.code, sec: 1 })
    const часы = window.setInterval(() => setЧитаю((т) => (т ? { ...т, sec: т.sec + 1 } : null)), 1000)
    const кончить = () => { window.clearInterval(часы); setЧитаю(null) }
    ;(роль && роль !== м.role ? api.setMaterialRole(project, м.code, роль) : Promise.resolve(null))
      .then(() => api.readDocument(project, м.code, 'инженер'))
      .then((р) => { кончить(); изменилось(); onRead(р.run, р.note) })
      .catch((e) => { кончить(); onError(String(e.message ?? e)) })
  }
  const прочитать = (м: MaterialRow) => {
    if (м.role) { прочитатьКак(м, null); return }
    // Роль решает, ЧТО из документа образуется: цели рождает только устав.
    // Без роли документ прочтётся как обстановка — спросить сразу, не молчать.
    спросить({
      question: `Роль документа «${м.name}» не названа: ею решается, что из него образуется — цели рождает только устав.`,
      ok: 'Прочитать',
      choice: { label: 'роль документа', options: РОЛИ_ДОКУМЕНТА.map((р) => [р.code, р.word]), initial: 'context' },
      onOk: (роль) => прочитатьКак(м, роль as DocumentRole),
    })
  }
  const сменитьРоль = (м: MaterialRow) => спросить({
    question: `Роль документа «${м.name}»: устав один на проект — второй сервер отобьёт с именем первого.`,
    ok: 'Записать',
    choice: { label: 'роль документа', options: РОЛИ_ДОКУМЕНТА.map((р) => [р.code, р.word]), initial: м.role ?? 'context' },
    onOk: (роль) => api.setMaterialRole(project, м.code, роль as DocumentRole)
      .then(() => { setИтог(`${м.code}: роль — ${РОЛЬ_СЛОВОМ[роль] ?? роль}`); перечитать() })
      .catch((e) => onError(String(e.message ?? e))),
  })
  const разобрать = (м: MaterialRow) => спросить({
    question: `Разобрать «${м.name}» по заданию: факты лягут в поле, план действий — на акцепт ниже.`,
    ok: 'Разобрать',
    input: { label: 'задание разбора', placeholder: 'разбери по сущностям', required: true },
    onOk: (задание) => api.intake(project, м.code, задание)
      .then((р) => api.taskPlan(project, р.task))
      .then((п) => { setПлан(п); setЗаметки([]); изменилось() })
      .catch((e) => onError(String(e.message ?? e))),
  })
  const снятьВклад = (м: MaterialRow) => спросить({
    question: `Снять вклад «${м.name}»: факты документа уйдут из поля, сущности без других оснований снимутся. Действие видно в досье.`,
    ok: 'Снять вклад',
    input: { label: 'почему снимаем', required: true },
    onOk: (причина) => api.rollbackMaterial(project, м.code, причина)
      .then((р) => { setИтог(р.note); изменилось() })
      .catch((e) => onError(String(e.message ?? e))),
  })

  return (
    <div data-why="работа" aria-label="документы поля знаний">
      <div className="v2-chips">
        <span className="v2-dim">Чтение идёт по документу и даёт постановку сразу — у каждого пункта цитата и якорь.</span>
        <span className="v2-chips__sp" />
        <ИконКнопка икон="загрузить" слово={форма && !форма.версия ? 'закрыть загрузку' : 'загрузить'} сословом
          className="v2-btn v2-btn--primary" aria-pressed={Boolean(форма && !форма.версия)}
          onClick={() => setФорма(форма && !форма.версия ? null : { версия: '' })} />
      </div>
      {форма && (
        <Source key={форма.версия || 'новый'} project={project} новаяВерсия={форма.версия}
          onParsed={(р) => {
            изменилось()
            if (р.task) api.taskPlan(project, р.task).then((п) => { setПлан(п); setЗаметки([]) }).catch(() => setПлан(null))
          }}
          onRead={(run, note) => { setФорма(null); изменилось(); onRead(run, note) }}
          onError={onError} />
      )}
      {итог && <div className="v2-note-line">{итог}</div>}
      {список.length > 0 && (
        <table className="v2-table">
          <thead>
            <tr>
              <th>Код</th><th>Документ</th><th>Роль</th><th>Ранг</th><th>Фактов</th>
              <th title="последний прогон разбора: статус и дата">Последний прогон</th>
              <th title="что документ дал: сущности, которые на нём стоят">Вклад</th>
              <th className="v2-acts" aria-label="действия строки" />
            </tr>
          </thead>
          <tbody>
            {список.map((м) => {
              const д = досье[м.code]
              const открытоДосье = открыт?.code === м.code && открыт.что === 'досье'
              const открытаКарта = открыт?.code === м.code && открыт.что === 'карта'
              return (
                <Fragment key={м.code}>
                  <tr className={открытоДосье || открытаКарта ? 'v2-row--open' : undefined}>
                    <td className="v2-mono">{м.code}</td>
                    <td>
                      {м.name}<span className="v2-dim"> · {м.chars} знаков</span>
                      {м.accept_mode && <span className="v2-chip" title="режим приёма документа"> {РЕЖИМЫ_ПРИЁМА.find((р) => р.code === м.accept_mode)?.word ?? м.accept_mode}</span>}
                      {м.summary && <div className="v2-dim" title="резюме разбора">{м.summary}</div>}
                    </td>
                    <td>
                      <button type="button" className="v2-link" onClick={() => сменитьРоль(м)}
                        title="ролью решается, ЧТО из документа может образоваться: цели рождает только устав">
                        {м.role ? РОЛЬ_СЛОВОМ[м.role] ?? м.role : 'роль не названа'}
                      </button>
                    </td>
                    <td>{м.rank ? (РАНГ[м.rank] ?? м.rank) : <span className="v2-warn">не назван</span>}</td>
                    <td>{фактов(м.code)}</td>
                    <td className="v2-nowrap">{последнийПрогон(д) ?? <span className="v2-dim">не разбирался</span>}</td>
                    <td>{д && д.entities.length > 0 ? вкладСловами(д.entities) : <span className="v2-dim">{д ? 'сущностей нет' : '—'}</span>}</td>
                    <td className="v2-acts">
                      {читаю?.code === м.code && <span className="v2-dim">читаю… {читаю.sec} с </span>}
                      <ИконКнопка икон="карточка" слово={открытоДосье ? 'свернуть досье' : 'досье'} aria-expanded={открытоДосье}
                        onClick={() => setОткрыт(открытоДосье ? null : { code: м.code, что: 'досье' })} />
                      <ИконКнопка икон="прочитать" слово="прочитать в постановку" disabled={читаю !== null} onClick={() => прочитать(м)} />
                      <ИконКнопка икон="сформировать" слово="разобрать по заданию" onClick={() => разобрать(м)} />
                      <ИконКнопка икон="загрузить" слово="загрузить версию" onClick={() => setФорма({ версия: м.code })} />
                      <ИконКнопка икон="снять" слово="снять вклад документа" onClick={() => снятьВклад(м)} />
                      {м.role === 'charter' && (
                        <ИконКнопка икон="сетка" слово={открытаКарта ? 'скрыть карту пробелов' : 'карта пробелов устава'} aria-expanded={открытаКарта}
                          onClick={() => setОткрыт(открытаКарта ? null : { code: м.code, что: 'карта' })} />
                      )}
                    </td>
                  </tr>
                  {(открытоДосье || открытаКарта) && (
                    <tr className="v2-card-row">
                      <td colSpan={8}>
                        <div className="v2-object">
                          {открытоДосье && <Досье project={project} code={м.code} onChanged={изменилось} onError={onError} />}
                          {открытаКарта && <КартаПробелов project={project} code={м.code} onError={onError} />}
                        </div>
                      </td>
                    </tr>
                  )}
                </Fragment>
              )
            })}
          </tbody>
        </table>
      )}
      {план && (
        <ПланРазбора project={project} план={план}
          onAccepted={(з) => { setПлан(null); setЗаметки(з); изменилось() }}
          onClose={() => setПлан(null)} onError={onError} />
      )}
      {заметки.length > 0 && <ЗаметкиПриёма заметки={заметки} onGoGlossary={onGoGlossary} onHide={() => setЗаметки([])} />}
      {/* Библиотека другого проекта — и без своих документов: норматив берут готовым, не разбирая заново. */}
      <ВзятьИзПроекта project={project} onDone={изменилось} onError={onError} />
      <ConfirmBox request={ask} onClose={закрытьВопрос} />
    </div>
  )
}
