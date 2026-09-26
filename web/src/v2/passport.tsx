// Паспорт проекта (журнал ПМИ-7, З-25; шип 1, п. 1.5; шип 5 §7).
//
// Название, класс миссии, руководитель и стандарт задавались при создании и
// не правились ничем: в §2 FAD печатался «инженер», в §1 FA — пустой класс.
// Здесь они правятся на месте, с версией; печать читает те же поля, так что
// правка паспорта и есть правка документов. DA — роль в проекте, не поле
// записи: её назначает руководитель, и здесь она видна рядом.
//
// Шип 5 §7: паспорт — КАРТОЧКА ПРОЕКТА §1.3: те же грани по истине вида
// «проект», правка на месте по полю («сохранено, версия N» или «ничего не
// изменилось» словами). Поля паспорта пишет маршрут паспорта — он знает
// обязательность и перечни; фазу и шаблон меняет решение точки, их грани
// только для чтения. Общей кнопки «Сохранить паспорт» больше нет: поле
// сохраняется само, дата точки — тоже.
import { useEffect, useState } from 'react'
import { api, type EntityRow, type KindSpec, type Passport as Паспорт } from './api'
import { useАвтор } from './research'
import { моиРоли, type Учётка } from './points'
import { ОтветственныеСцен } from './responsibles'
import { Карточка } from './ui/objectcard'

/** Роли проекта словами — те же, что на экране точки. */
const РОЛЬ: Record<string, string> = {
  lead: 'руководитель проекта', lead_se: 'ведущий СИ', specialist: 'инженер', da_review: 'DA',
}

type Учётки = { login: string; display_name: string }[]

/** Поля паспорта — правит маршрут паспорта; остальные грани проекта только читаются. */
export const ПОЛЯ_ПАСПОРТА = ['name', 'mission_class', 'manager', 'standard'] as const

/**
 * Запись проекта для карточки: грани вида по истине, значения паспорта —
 * как их нормализует сервер (руководитель из прежнего `lead`, фаза из `phase`).
 */
export function записьПаспорта(запись: EntityRow, п: Паспорт): EntityRow {
  return {
    ...запись,
    doc: {
      ...запись.doc,
      name: п.name, mission_class: п.mission_class, manager: п.manager, standard: п.standard,
      phase_current: п.phase_current, phase_template: п.phase_template,
    },
  }
}

export function PassportScreen({ project, учётка, onChanged }: {
  project: string | null
  учётка?: Учётка | null
  /** Паспорт изменил название или даты — шапка и лента перечитываются. */
  onChanged?: () => void
}) {
  const [паспорт, setПаспорт] = useState<Паспорт | null>(null)
  /** Запись проекта и вид по истине — строение карточки. */
  const [запись, setЗапись] = useState<EntityRow | null>(null)
  const [вид, setВид] = useState<KindSpec | null>(null)
  const [роли, setРоли] = useState<Record<string, string>>({})
  const [учётки, setУчётки] = useState<Учётки>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  /** Итог правки даты точки словами — у своей даты. */
  const [итогДаты, setИтогДаты] = useState<{ key: string; текст: string; ошибка?: boolean } | null>(null)
  const [автор] = useАвтор()
  /** Кому и какую роль назначить: хук — до ранних возвратов, как и остальные. */
  const [назначение, setНазначение] = useState({ login: '', role: 'da_review' })

  const перечитать = () => {
    if (!project) return
    api.passport(project).then(setПаспорт).catch((e) => setОтказ(String(e.message ?? e)))
    api.entities(project, 'project')
      .then((р) => setЗапись(р.items.find((з) => з.code === project) ?? р.items[0] ?? null))
      .catch(() => setЗапись(null))
    api.kind('project').then(setВид).catch(() => setВид(null))
    api.projectRoles(project).then(setРоли).catch(() => setРоли({}))
    fetch('/api/auth/users').then((r) => (r.ok ? r.json() : { users: [] }))
      .then((d) => setУчётки(d.users ?? [])).catch(() => setУчётки([]))
  }
  useEffect(перечитать, [project])

  if (!project) return <div className="v2-panel" data-why="почему-нельзя"><h3>Паспорт</h3><div className="v2-empty">Проект не выбран.</div></div>
  if (!паспорт) return <div className="v2-panel" data-why="работа"><h3>Паспорт</h3><div className="v2-empty">{отказ ?? 'Читаю паспорт…'}</div></div>

  const мои = моиРоли(учётка, project)
  const руководитель = мои.includes('lead')
  const держатели = (роль: string) => Object.entries(роли).filter(([, р]) => р === роль).map(([л]) => л)
  const имя = (логин: string) => учётки.find((у) => у.login === логин)?.display_name ?? логин
  /** Кто правит — именем учётки; пусто не бывает: без имени правка не пишется. */
  const кто = автор.trim() || учётка?.display_name || 'инженер'

  /** Роль проекта учётке: DA фиксирует точки, РП и ведущий СИ — умолчание ответственных сцен. */
  const назначитьРоль = () => {
    if (!назначение.login) return
    setОтказ(null)
    api.setProjectRole(project, назначение.login, назначение.role)
      .then(() => { setНазначение({ login: '', role: 'da_review' }); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  /** Поле паспорта — маршрутом паспорта: пустое сервер не примет и скажет словами у поля. */
  const сохранитьПоле = (поле: string, значение: unknown) =>
    api.patchPassport(project, { fields: { [поле]: String(значение ?? '').trim() }, author: кто, reason: 'правка на месте в паспорте' })
      .then((о) => ({ version: о.version, changed: о.changed }))

  /** Дата точки — сразу по уходу с поля: пройденную точку сервер не передвинет и скажет почему. */
  const сохранитьДату = (точка: string, было: string, дата: string) => {
    if (!дата || дата === было) return
    setИтогДаты(null)
    api.patchPassport(project, { gate_dates: [{ gate: точка, date: дата }], author: кто, reason: 'дата точки в паспорте' })
      .then((о) => { setИтогДаты({ key: точка, текст: о.gate_dates_changed > 0 ? 'дата сохранена' : 'ничего не изменилось' }); перечитать(); onChanged?.() })
      .catch((e) => setИтогДаты({ key: точка, текст: String(e.message ?? e), ошибка: true }))
  }

  return (
    <div className="v2-panel" data-why="работа">
      {запись && вид ? (
        <Карточка project={project} row={записьПаспорта(запись, паспорт)} spec={вид} заголовок={`Паспорт · ${паспорт.name}`}
          состояние={<span className="v2-object__state">{паспорт.phase_current || 'фаза не названа'}{паспорт.phase_template ? ` · шаблон ${паспорт.phase_template}` : ''}</span>}
          мета={`версия ${паспорт.version} · ${паспорт.updated_by} · ${паспорт.updated_at}`}
          толькоЧтение={(вид.fields ?? []).filter((п) => !(ПОЛЯ_ПАСПОРТА as readonly string[]).includes(п))}
          всеСразу сохранитьПоле={сохранитьПоле}
          onSaved={() => { перечитать(); onChanged?.() }}
          свои={{
            // Класс миссии — с полки библиотеки: значение поля — код или имя класса.
            mission_class: ({ id, значение, занято, onSave }) => (
              <>
                <input id={id} key={`mc-${паспорт.version}`} list={`v2-классы-${project}`} defaultValue={String(значение ?? '')}
                  disabled={занято} onBlur={(e) => onSave(e.target.value.trim())} />
                <datalist id={`v2-классы-${project}`}>
                  {паспорт.mission_classes.map((к) => <option key={к.code} value={к.name}>{к.code}</option>)}
                </datalist>
              </>
            ),
            // Руководитель — учётка стенда: подсказка именами, сохраняется имя.
            manager: ({ id, значение, занято, onSave }) => (
              <>
                <input id={id} key={`mg-${паспорт.version}`} list={`v2-учётки-${project}`} defaultValue={String(значение ?? '')}
                  disabled={занято} onBlur={(e) => onSave(e.target.value.trim())} />
                <datalist id={`v2-учётки-${project}`}>
                  {учётки.map((у) => <option key={у.login} value={у.display_name}>{у.login}</option>)}
                </datalist>
              </>
            ),
          }}
          extra={(
            <div className="v2-facet v2-facet--wide">
              <div className="v2-facet__lab">Даты точек текущей фазы</div>
              <div className="v2-facet__chips">
                {паспорт.gates.map((т) => (
                  <label key={т.key} className="v2-field" title={т.passed ? 'точка пройдена: дата — факт' : 'плановая дата точки: сохраняется по уходу с поля'}>
                    <span className="v2-field__cap">{т.title}</span>
                    <input type="date" key={`${т.key}-${т.planned_date}`} aria-label={`дата точки ${т.key}`} disabled={т.passed}
                      defaultValue={т.planned_date} onBlur={(e) => сохранитьДату(т.key, т.planned_date, e.target.value)} />
                    {итогДаты?.key === т.key && <span className={итогДаты.ошибка ? 'v2-facet__err' : 'v2-facet__ok'}>{итогДаты.текст}</span>}
                  </label>
                ))}
              </div>
              <div className="v2-facet__hint">даты правятся в самих точках — план фазы держит те же</div>
            </div>
          )} />
      ) : (
        <div className="v2-empty">Читаю карточку проекта…</div>
      )}
      {отказ && <div className="v2-locked">{отказ}</div>}

      <h4 className="v2-h4">Роли проекта</h4>
      <div className="v2-dim">
        DA: {держатели('da_review').length > 0 ? держатели('da_review').map(имя).join(', ') : 'не назначен — точку фиксировать некому'}
        {' · '}руководитель: {держатели('lead').map(имя).join(', ') || паспорт.manager || '—'}
        {' · '}ведущий СИ: {держатели('lead_se').map(имя).join(', ') || '—'}
      </div>
      {руководитель ? (
        <div className="v2-form v2-form--row" title="роли назначает руководитель проекта: DA фиксирует точки, РП и ведущий СИ — умолчание ответственных сцен">
          <label className="v2-inline">
            роль
            <select aria-label="какую роль назначить" value={назначение.role} onChange={(e) => setНазначение({ ...назначение, role: e.target.value })}>
              {Object.entries(РОЛЬ).map(([к, и]) => <option key={к} value={к}>{и}</option>)}
            </select>
          </label>
          <label className="v2-inline">
            учётка
            <select aria-label="кому назначить роль" value={назначение.login} onChange={(e) => setНазначение({ ...назначение, login: e.target.value })}>
              <option value="">— учётка —</option>
              {учётки.map((у) => <option key={у.login} value={у.login}>{у.display_name} ({РОЛЬ[роли[у.login]] ?? 'без роли'})</option>)}
            </select>
          </label>
          <button type="button" className="v2-primary" disabled={!назначение.login}
            title={!назначение.login ? 'учётка не выбрана' : `назначить роль «${РОЛЬ[назначение.role]}»: у учётки одна роль в проекте`}
            onClick={назначитьРоль}>
            Назначить
          </button>
        </div>
      ) : (
        <div className="v2-dim">Роли назначает руководитель проекта.</div>
      )}

      <ОтветственныеСцен project={project} onChanged={onChanged} />
    </div>
  )
}

