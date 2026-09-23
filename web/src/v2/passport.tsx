// Паспорт проекта (журнал ПМИ-7, З-25; шип 1, п. 1.5).
//
// Название, класс миссии, руководитель и стандарт задавались при создании и
// не правились ничем: в §2 FAD печатался «инженер», в §1 FA — пустой класс.
// Здесь они правятся на месте, с версией; печать читает те же поля, так что
// правка паспорта и есть правка документов. DA — роль в проекте, не поле
// записи: её назначает руководитель, и здесь она видна рядом.
import { useEffect, useState } from 'react'
import { api, type Passport as Паспорт } from './api'
import { useАвтор } from './research'
import { моиРоли, type Учётка } from './points'
import { ОтветственныеСцен } from './responsibles'

/** Роли проекта словами — те же, что на экране точки. */
const РОЛЬ: Record<string, string> = {
  lead: 'руководитель проекта', lead_se: 'ведущий СИ', specialist: 'инженер', da_review: 'DA',
}

type Учётки = { login: string; display_name: string }[]

export function PassportScreen({ project, учётка, onChanged }: {
  project: string | null
  учётка?: Учётка | null
  /** Паспорт изменил название или даты — шапка и лента перечитываются. */
  onChanged?: () => void
}) {
  const [паспорт, setПаспорт] = useState<Паспорт | null>(null)
  const [роли, setРоли] = useState<Record<string, string>>({})
  const [учётки, setУчётки] = useState<Учётки>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [сделано, setСделано] = useState<string | null>(null)
  const [автор] = useАвтор()
  /** Черновик правки: поле → значение; пусто — ничего не тронуто. */
  const [черновик, setЧерновик] = useState<Record<string, string>>({})
  const [даты, setДаты] = useState<Record<string, string>>({})
  const [занято, setЗанято] = useState(false)
  /** Кому и какую роль назначить: хук — до ранних возвратов, как и остальные. */
  const [назначение, setНазначение] = useState({ login: '', role: 'da_review' })

  const перечитать = () => {
    if (!project) return
    api.passport(project).then((п) => { setПаспорт(п); setЧерновик({}); setДаты({}) })
      .catch((e) => setОтказ(String(e.message ?? e)))
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
  const поля = ['name', 'mission_class', 'manager', 'standard'] as const
  const изменено = Object.keys(черновик).length > 0 || Object.keys(даты).length > 0
  const помеха = !автор.trim() ? 'не названо, кто правит' : !изменено ? 'ничего не изменено' : null

  const сохранить = () => {
    if (помеха) return
    setЗанято(true); setОтказ(null); setСделано(null)
    api.patchPassport(project, {
      fields: Object.keys(черновик).length > 0 ? черновик : undefined,
      gate_dates: Object.keys(даты).length > 0 ? Object.entries(даты).map(([gate, date]) => ({ gate, date })) : undefined,
      author: автор,
    })
      .then((о) => {
        setСделано(`сохранено: версия ${о.version}` + (о.gate_dates_changed > 0 ? ` · дат точек ${о.gate_dates_changed}` : ''))
        перечитать(); onChanged?.()
      })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  /** Роль проекта учётке: DA фиксирует точки, РП и ведущий СИ — умолчание ответственных сцен. */
  const назначитьРоль = () => {
    if (!назначение.login) return
    setОтказ(null)
    api.setProjectRole(project, назначение.login, назначение.role)
      .then(() => { setНазначение({ login: '', role: 'da_review' }); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }

  return (
    <div className="v2-panel" data-why="работа">
      <h3>
        Паспорт · {паспорт.name}
        <span className="v2-cnt">v{паспорт.version} · {паспорт.updated_at} · {паспорт.updated_by}</span>
      </h3>
      <div className="v2-dim">
        {паспорт.phase_current || 'фаза не названа'}{паспорт.phase_template ? ` · шаблон ${паспорт.phase_template}` : ''}
        {' · '}фазу и шаблон меняет решение точки, не паспорт
      </div>
      <div className="v2-form">
        {поля.map((поле) => {
          const значение = черновик[поле] ?? паспорт[поле]
          const метка = паспорт.labels[поле] ?? поле
          // Черновик копит всё, что тронуто; неизменённое отсеет сервер (changed: 0).
          const поставить = (v: string) => setЧерновик((ч) => ({ ...ч, [поле]: v }))
          return (
            <label key={поле} className="v2-field">
              <span className="v2-field__cap">{метка}</span>
              {поле === 'standard' ? (
                <select aria-label={метка} value={значение} onChange={(e) => поставить(e.target.value)}>
                  {паспорт.standards.map((с) => <option key={с.code} value={с.code}>{с.label}</option>)}
                  {!паспорт.standards.some((с) => с.code === значение) && значение && <option value={значение}>{значение}</option>}
                </select>
              ) : поле === 'mission_class' ? (
                <>
                  <input aria-label={метка} list={`v2-классы-${project}`} value={значение}
                    onChange={(e) => поставить(e.target.value)} />
                  <datalist id={`v2-классы-${project}`}>
                    {паспорт.mission_classes.map((к) => <option key={к.code} value={к.name}>{к.code}</option>)}
                  </datalist>
                </>
              ) : поле === 'manager' ? (
                <>
                  <input aria-label={метка} list={`v2-учётки-${project}`} value={значение}
                    onChange={(e) => поставить(e.target.value)} />
                  <datalist id={`v2-учётки-${project}`}>
                    {учётки.map((у) => <option key={у.login} value={у.display_name}>{у.login}</option>)}
                  </datalist>
                </>
              ) : (
                <input aria-label={метка} value={значение} onChange={(e) => поставить(e.target.value)} />
              )}
            </label>
          )
        })}
      </div>

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

      <h4 className="v2-h4">Даты точек текущей фазы</h4>
      <div className="v2-form v2-form--row">
        {паспорт.gates.map((т) => (
          <label key={т.key} className="v2-field" title={т.passed ? 'точка пройдена: дата — факт' : 'плановая дата точки'}>
            <span className="v2-field__cap">{т.title}</span>
            <input type="date" aria-label={`дата точки ${т.key}`} disabled={т.passed}
              value={даты[т.key] ?? т.planned_date}
              onChange={(e) => setДаты((д) => ({ ...д, [т.key]: e.target.value }))} />
          </label>
        ))}
      </div>

      <div className="v2-form__actions">
        <button type="button" className="v2-chip v2-chip--on" disabled={Boolean(помеха) || занято} onClick={сохранить}
          title="сохранить паспорт: новая версия записи проекта; печать FAD §2 и FA §1 читает отсюда">
          {занято ? 'сохраняю…' : 'Сохранить паспорт'}
        </button>
        {помеха && изменено && <div className="v2-locked">Нажать нельзя: {помеха}</div>}
        {сделано && <span className="v2-ok">{сделано}</span>}
        {отказ && <div className="v2-locked">Не сохранено: {отказ}</div>}
      </div>
    </div>
  )
}

