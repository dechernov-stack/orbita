// Оболочка v2 по эталону (`эталоны/reference-shell-v2.html`).
//
// Шапка отвечает на три вопроса разом: где я (проект · фаза · сцена), что
// горит (ближайшая точка с блокирующими) и кто я (учётка, «мои»). Рейка —
// разделы продукта; экспертные показываются переключателем, а не всегда.
import { useEffect, useState } from 'react'
import { api, type Phase, type ProjectRow } from './api'
import { Work } from './work'
import { MyTasks } from './tasks'
import { KnowledgeField as KnowledgeIntake } from './knowledge'
import { KnowledgeField } from './knowledgefield'
import { Coverage } from './coverage'
import { Concept } from './concept'
import { Requirements } from './requirements'
import { ArchitectureScreen } from './architecture'
import { Documents } from './documents'
import { Models } from './models'
import { ИМЯ_РЕЖИМА, режимПоРоли, type Режим } from './density'

/** Раздел рейки. `wave` — волна, в которой раздел оживает. */
type Section = {
  key: string
  title: string
  wave: number
  expert?: boolean
  hint: string
}

const SECTIONS: Section[] = [
  { key: 'work', title: 'Работа', wave: 1, hint: 'лента сцен и точек фазы — вход в продукт' },
  { key: 'formulation', title: 'Постановка', wave: 1, hint: 'стейкхолдеры, нужды, цели, ограничения, сервисы и покрытие' },
  { key: 'knowledge', title: 'Поле знаний', wave: 2, hint: 'материалы, факты с якорями, загрузка с заданием' },
  { key: 'concept', title: 'Концепция', wave: 3, hint: 'состав системы, варианты построения, базовый вариант' },
  { key: 'requirements', title: 'Требования', wave: 3, hint: 'реестр требований, два дерева, влияние правки' },
  { key: 'architecture', title: 'Архитектура', wave: 3, hint: 'операционный, системный, логический и физический слои' },
  { key: 'models', title: 'Модели', wave: 4, hint: 'записи моделей, прогоны, резервы' },
  { key: 'documents', title: 'Документы', wave: 4, hint: 'разделы документов с полнотой к ступени, тезисы, печать' },
  { key: 'points', title: 'Точки', wave: 6, hint: 'готовность по экспертизе, замечания, фиксация' },
  { key: 'library', title: 'Библиотека', wave: 2, expert: true, hint: 'полки, окно взятия, справочники' },
  { key: 'exchange', title: 'Обмен', wave: 5, expert: true, hint: 'StrictDoc и ReqIF, выгрузка знаний' },
  { key: 'journal', title: 'Журналы', wave: 5, expert: true, hint: 'журнал службы, история правок' },
]

/** Дата в шапке — днём и месяцем: год в ленте фазы и так один. */
function датаКратко(дата: string): string {
  const [, м, д] = дата.split('-')
  return д && м ? `${д}.${м}` : дата
}

/** Учётка стенда: вход селектором без пароля (ТЗ §4.2). */
type StandUser = { login: string; display_name: string; roles?: Record<string, string> }

export function Shell() {
  const [section, setSection] = useState('work')
  const [expert, setExpert] = useState(false)
  const [users, setUsers] = useState<StandUser[]>([])
  const [project, setProject] = useState<string | null>(null)
  const [portfolio, setPortfolio] = useState<ProjectRow[]>([])
  const [phase, setPhase] = useState<Phase | null>(null)
  /** Переход «к месту» из заданий: открыть работу на нужной сцене. */
  const [wantScene, setWantScene] = useState<string | null>(null)
  const [tasks, setTasks] = useState<number>(0)
  /** Сцена, открытая на экране: шапка обязана совпадать с ним. */
  const [openScene, setOpenScene] = useState<string | null>(null)
  const [me, setMe] = useState<string | null>(null)
  /** Моя учётка целиком: роль в проекте задаёт плотность экрана (§2). */
  const [я, setЯ] = useState<StandUser | null>(null)
  /** Плотность, выбранная руками; null — умолчание роли. */
  const [режим, setРежим] = useState<Режим | null>(null)
  const [failure, setFailure] = useState<string | null>(null)

  useEffect(() => {
    api.projects()
      .then((r) => {
        setPortfolio(r.items)
        // Помним выбор: продукт открывают десятки раз в день, и выбирать
        // проект заново каждый раз — работа, которой не должно быть.
        const прежний = localStorage.getItem('orbita.v2.project')
        setProject((текущий) =>
          текущий ?? (r.items.some((п) => п.code === прежний) ? прежний : r.items[0]?.code ?? null))
      })
      .catch(() => undefined)
  }, [])

  useEffect(() => {
    if (!project) { setPhase(null); return }
    localStorage.setItem('orbita.v2.project', project)
    api.phase(project).then(setPhase).catch(() => setPhase(null))
    api.myTasks(project).then((r) => setTasks(r.items.filter((з) => !з.waiting).length)).catch(() => undefined)
  }, [project, section])

  useEffect(() => {
    fetch('/api/auth/whoami')
      .then((r) => r.json())
      .then((d) => {
        setUsers(d.stand_users ?? [])
        setMe(d.user?.display_name ?? null)
        setЯ(d.user ?? null)
      })
      .catch((e) => setFailure(String(e)))
  }, [])

  // Роль — из учётки и проекта: плотность её следствие, а не настройка.
  //
  // Проекты v2 живут своим счётом, и роли в них пока не заводятся (модуль
  // access — волна 6). Пока их нет, берётся роль УЧЁТКИ: на стенде она одна
  // и та же во всех проектах, и это факт, а не догадка. Роли разошлись, а
  // проекта в карте нет — плотность самая узкая, без выдумывания.
  const ролиУчётки = Object.values(я?.roles ?? {})
  const однаРоль = ролиУчётки.length > 0 && new Set(ролиУчётки).size === 1
    ? ролиУчётки[0] : null
  const роль = (project && я?.roles?.[project]) || однаРоль
  const текущийРежим: Режим = режим ?? режимПоРоли(роль)
  // Смена проекта возвращает плотность к умолчанию роли: в другом проекте
  // у того же человека может быть другая роль.
  useEffect(() => { setРежим(null) }, [project])

  const visible = SECTIONS.filter((s) => expert || !s.expert)
  const current = SECTIONS.find((s) => s.key === section) ?? SECTIONS[0]
  const сцена = phase?.scenes.find((с) => с.key === (openScene ?? phase.current_scene))
  const точка = phase?.gates.find((т) => !т.passed)

  return (
    <div className="v2-shell">
      <header className="v2-top">
        <div className="v2-ctx">
          {portfolio.length > 0 ? (
            <select className="v2-project" value={project ?? ''} title="проект портфеля: выбор помнится"
              onChange={(e) => setProject(e.target.value || null)}>
              <option value="">— выберите проект —</option>
              {portfolio.map((п) => <option key={п.code} value={п.code}>{п.name}</option>)}
            </select>
          ) : (
            <b>Проект не выбран</b>
          )}
          {phase && <span className="v2-dim">{phase.phase} · {phase.standard}</span>}
          {сцена && (
            <span className="v2-chip" title={сцена.question}>
              сцена <b>{сцена.key} · {сцена.title}</b>
            </span>
          )}
          {точка && (
            <span className="v2-chip" title={точка.blocking.join('; ') || 'условия точки выполнены'}>
              ближайшая точка <b>{точка.title}</b>
              {точка.planned_date && ` · ${датаКратко(точка.planned_date)}`}
              {' · '}
              {точка.blocking.length > 0
                ? <span className="v2-bad">блокирующих {точка.blocking.length}</span>
                : <span className="v2-ok">условия выполнены</span>}
            </span>
          )}
        </div>
        <div className="v2-me">
          <button type="button" className="v2-link" title="адресованные разрывы: что закрыть именно мне"
            onClick={() => setSection('tasks')}>
            мои <b>{tasks}</b>
          </button>
          {section === 'work' && (
            <label className="v2-inline" title={`плотность экрана: умолчание роли — ${ИМЯ_РЕЖИМА[режимПоРоли(роль)]}`}>
              вид
              <select className="v2-density" value={текущийРежим}
                onChange={(e) => setРежим(e.target.value as Режим)}>
                {(['мероприятие', 'сцена', 'фаза'] as Режим[]).map((р) => (
                  <option key={р} value={р}>{ИМЯ_РЕЖИМА[р]}</option>
                ))}
              </select>
            </label>
          )}
          <label className="v2-inline" title={expert
            ? 'выключить эксперт-режим: останутся только разделы работы'
            : 'включить эксперт-режим: библиотека, обмен и журналы'}>
            <input type="checkbox" checked={expert} onChange={(e) => setExpert(e.target.checked)} />
            эксперт-режим
          </label>
          <span title="учётка стенда: вход селектором, пароля у витринных учёток нет">
            {me ?? (users.length > 0 ? `учётки стенда: ${users.length}` : 'учётка не выбрана')}
          </span>
        </div>
      </header>

      <div className="v2-layout">
        <nav className="v2-rail" aria-label="разделы">
          {visible.filter((s) => !s.expert).map((s) => (
            <button key={s.key} className="v2-rail__item" type="button"
              aria-current={s.key === section ? 'page' : undefined}
              title={s.hint}
              onClick={() => setSection(s.key)}>
              {s.title}
            </button>
          ))}
          {expert && (
            <>
              <div className="v2-rail__group">эксперт</div>
              {visible.filter((s) => s.expert).map((s) => (
                <button key={s.key} className="v2-rail__item v2-rail__item--exp" type="button"
                  aria-current={s.key === section ? 'page' : undefined}
                  title={s.hint}
                  onClick={() => setSection(s.key)}>
                  {s.title}
                </button>
              ))}
            </>
          )}
        </nav>

        <main className="v2-main">
          {failure && (
            <div className="v2-panel" data-why="почему-нельзя">
              <h3>Стенд не ответил</h3>
              <div className="v2-empty">{failure}</div>
            </div>
          )}
          {section === 'work' ? (
            <Work project={project} onProject={setProject} wantScene={wantScene}
              onScenePicked={() => setWantScene(null)} onScene={setOpenScene}
              роль={роль} режим={режим} onРежим={setРежим} />
          ) : section === 'knowledge' ? (
            <>
              <KnowledgeField project={project} />
              <KnowledgeIntake project={project} />
            </>
          ) : section === 'formulation' ? (
            <Coverage project={project} />
          ) : section === 'concept' ? (
            <Concept project={project} />
          ) : section === 'requirements' ? (
            <Requirements project={project} />
          ) : section === 'architecture' ? (
            <ArchitectureScreen project={project} />
          ) : section === 'models' ? (
            <Models project={project} />
          ) : section === 'documents' ? (
            <Documents project={project} />
          ) : section === 'tasks' ? (
            <MyTasks project={project} onGoScene={(сцена) => { setWantScene(сцена); setSection('work') }} />
          ) : (
            <div className="v2-panel" data-why="работа">
              <h3>
                {current.title}
                <span className="v2-cnt">волна {current.wave}</span>
              </h3>
              <div className="v2-empty">
                {current.hint}.
                <span className="v2-empty__why">
                  Раздел откроется волной {current.wave}; сейчас пройдены волны 0–3.
                </span>
              </div>
            </div>
          )}
        </main>
      </div>
    </div>
  )
}
