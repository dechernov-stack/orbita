// Оболочка v2 по эталону (`эталоны/reference-shell-v2.html`).
//
// Шапка отвечает на три вопроса разом: где я (проект · фаза · сцена), что
// горит (ближайшая точка с блокирующими) и кто я (учётка, «мои»). Рейка —
// разделы продукта; экспертные показываются переключателем, а не всегда.
import { Fragment as Фрагмент, useEffect, useState } from 'react'
import { api, type Phase, type ProjectRow } from './api'
import { Work } from './work'
import { MyTasks } from './tasks'
import { KnowledgeField } from './knowledgefield'
import { Formulation } from './formulation'
import { Concept } from './concept'
import { Requirements } from './requirements'
import { ArchitectureScreen } from './architecture'
import { Documents } from './documents'
import { Models } from './models'
import { Points } from './points'
import { PassportScreen } from './passport'
import { RiskRegistry } from './risks'
import { BridgeScreen } from './bridge'
import { ProjectsScreen } from './projects'
import { NewProjectScreen } from './newproject'
import { MyWorkScreen } from './mywork'
import { ExternalModelScreen } from './externalmodel'
import { Library } from './library'
import { GlossaryScreen } from './glossary'
import { ИМЯ_РЕЖИМА, режимПоРоли, type Режим } from './density'
import { ПлотностьКонтекст } from './ui/density'
import { Икон, type Пиктограмма } from './icons'
import { Маркер } from './markers'
import { инициалы } from './people'

/** Раздел рейки. `wave` — волна, в которой раздел оживает. */
type Section = {
  key: string
  title: string
  wave: number
  expert?: boolean
  hint: string
  /** Раздел виден с этой сцены фазы; до неё — только в эксперт-режиме. */
  fromScene?: string
  /** Пиктограмма рейки — всегда рядом со словом, никогда вместо него. */
  icon: Пиктограмма
}

/**
 * Ярусы рейки (шип 5 §1.6) — тонкие разделители без подписей: входы
 * (проекты · мостик · моя работа) | работа фазы | паспорт и словарь. Порядок
 * разделов не меняется; разделитель встаёт ПЕРЕД названным разделом.
 */
const ЯРУС_С = new Set(['work', 'passport'])

const SECTIONS: Section[] = [
  { key: 'projects', title: 'Проекты', wave: 8, icon: 'проекты', hint: 'портфель: рабочие и примеры, новый проект' },
  { key: 'bridge', title: 'Мостик', wave: 8, icon: 'мостик', hint: 'вход ведущего: маршрут, что решить, что блокирует точку, команда, сигналы' },
  { key: 'mywork', title: 'Моя работа', wave: 8, icon: 'моя-работа', hint: 'вход специалиста: поручения по сроку и одно открытое мероприятие' },
  { key: 'work', title: 'Работа', wave: 1, icon: 'работа', hint: 'лента сцен и точек фазы — сцена в теле' },
  { key: 'formulation', title: 'Постановка', wave: 1, icon: 'постановка', hint: 'стейкхолдеры, нужды, цели, ограничения, сервисы и покрытие' },
  { key: 'knowledge', title: 'Поле знаний', wave: 2, icon: 'знания', hint: 'материалы, факты с якорями, загрузка с заданием' },
  { key: 'concept', title: 'Концепция', wave: 3, icon: 'концепция', hint: 'состав системы, варианты построения, базовый вариант' },
  { key: 'requirements', title: 'Требования', wave: 3, icon: 'требования', hint: 'реестр требований, два дерева, влияние правки' },
  { key: 'architecture', title: 'Архитектура', wave: 3, icon: 'архитектура', hint: 'операционный, системный, логический и физический слои' },
  { key: 'models', title: 'Модели', wave: 4, icon: 'модели', hint: 'записи моделей, прогоны, резервы' },
  { key: 'documents', title: 'Документы', wave: 4, icon: 'документы', hint: 'разделы документов с полнотой к ступени, тезисы, печать' },
  { key: 'risks', title: 'Риски', wave: 4, icon: 'риски', fromScene: '11', hint: 'реестр рисков всей фазы: отбор, закрытие решением, срок-точка' },
  { key: 'points', title: 'Точки', wave: 6, icon: 'точки', hint: 'готовность по экспертизе, замечания, фиксация' },
  { key: 'passport', title: 'Паспорт', wave: 6, icon: 'паспорт', hint: 'название, класс миссии, руководитель, DA, стандарт, даты точек — правка на месте' },
  { key: 'glossary', title: 'Словарь', wave: 9, icon: 'словарь', hint: 'термины класса миссии и проекта: синонимы, классы, кандидаты из документов — принять, отклонить, слить' },
  { key: 'library', title: 'Библиотека', wave: 2, icon: 'библиотека', expert: true, hint: 'полки, окно взятия, справочники' },
  { key: 'exchange', title: 'Обмен', wave: 5, icon: 'обмен', expert: true, hint: 'StrictDoc и ReqIF, выгрузка знаний' },
  { key: 'external', title: 'Внешняя модель', wave: 7, icon: 'внешняя', expert: true, hint: 'элементы Capella по слоям либо fixture с баннером; только чтение' },
  { key: 'journal', title: 'Журналы', wave: 5, icon: 'журналы', expert: true, hint: 'журнал службы, история правок' },
]

/** Дата в шапке — днём и месяцем: год в ленте фазы и так один. */
function датаКратко(дата: string): string {
  const [, м, д] = дата.split('-')
  return д && м ? `${д}.${м}` : дата
}

/** Учётка стенда: вход селектором без пароля (ТЗ §4.2). */
type StandUser = {
  login: string; display_name: string; roles?: Record<string, string>
  /** ADR-066: автор журнала, роль «от имени», право владельца, роли словами */
  author?: string; acting_role?: string | null; can_act_as?: boolean; acting_roles?: Record<string, string>
}

/**
 * Сборка сменилась под открытой вкладкой — сказать, а не зависнуть.
 *
 * Проход владельца 20.09: «завис сервер — не отвечает». Сервер был жив (все
 * контейнеры здоровы, ответы за доли секунды): под открытой вкладкой сменили
 * образ `web`, и страница осталась со СТАРОЙ сборкой — её чанки на новом
 * контейнере уже не лежат, и приложение замирает молча. Теперь замечаем это
 * сами: раз в минуту читаем точку входа и сравниваем имя главного скрипта
 * (оно меняется с каждой сборкой). Изменилось — полоса «вышло обновление» с
 * кнопкой; ничего не перезагружаем без человека, он может быть в середине
 * ввода.
 */
function useОбновление(): boolean {
  const [вышло, setВышло] = useState(false)
  useEffect(() => {
    const мой = [...document.querySelectorAll('script[src]')]
      .map((с) => (с as HTMLScriptElement).src)
      .find((с) => с.includes('/assets/'))
    if (!мой) return undefined
    const проверить = () => {
      fetch(window.location.pathname, { cache: 'no-store' })
        .then((о) => (о.ok ? о.text() : null))
        .then((html) => {
          if (!html) return
          const свежий = html.match(/src="([^"]*\/assets\/[^"]+\.js)"/)?.[1]
          if (свежий && !мой.endsWith(свежий.replace(/^\.?\//, ''))) setВышло(true)
        })
        .catch(() => undefined)
    }
    const часы = window.setInterval(проверить, 60000)
    // Чанк, которого уже нет на сервере, роняет загрузку страницы молча:
    // это тот же признак смены сборки.
    const наОшибку = (е: Event) => {
      const цель = е.target as HTMLElement | null
      if (цель && 'src' in цель && String((цель as HTMLScriptElement).src).includes('/assets/')) setВышло(true)
    }
    window.addEventListener('error', наОшибку, true)
    return () => { window.clearInterval(часы); window.removeEventListener('error', наОшибку, true) }
  }, [])
  return вышло
}

export function Shell() {
  const [section, setSection] = useState('work')
  const [expert, setExpert] = useState(false)
  const [users, setUsers] = useState<StandUser[]>([])
  const [project, setProject] = useState<string | null>(null)
  const [portfolio, setPortfolio] = useState<ProjectRow[]>([])
  /**
   * Счётчик перечитывания портфеля. Список читался ОДИН раз при входе, и
   * заведённый после этого проект в него не попадал: шапка говорила «проект
   * не выбран» при выбранном проекте, а выбора не появлялось вовсе — выйти из
   * этого состояния было нечем (поймано владельцем на старте ПМИ-6, 14.09).
   */
  const [portfolioTick, setPortfolioTick] = useState(0)
  const [phase, setPhase] = useState<Phase | null>(null)
  /** Счётчик перечитывания фазы: решение точки меняет шапку и ленту. */
  const [phaseTick, setPhaseTick] = useState(0)
  /** Переход «к месту» из заданий: открыть работу на нужной сцене. */
  const [wantScene, setWantScene] = useState<string | null>(null)
  /** Зачем нас сюда послали: словами раздела документа, а не «переход выполнен». */
  const [wantReason, setWantReason] = useState<string | null>(null)
  /** Ссылка с точки: открыть реестр рисков на карточке этого риска. */
  const [wantRisk, setWantRisk] = useState<string | null>(null)
  useEffect(() => { if (section !== 'risks') setWantRisk(null) }, [section])
  /** Дорога из §11 ведёт к форме: поле знаний открывается ручным вводом. */
  const [ручнойВвод, setРучнойВвод] = useState(false)
  useEffect(() => { if (section !== 'knowledge') setРучнойВвод(false) }, [section])
  const [tasks, setTasks] = useState<number>(0)
  /** Сборка сменилась под открытой вкладкой: сказать словами, а не зависнуть. */
  const обновление = useОбновление()
  const [me, setMe] = useState<string | null>(null)
  /** Моя учётка целиком: роль в проекте задаёт плотность экрана (§2). */
  const [я, setЯ] = useState<StandUser | null>(null)
  /** Вход через Telegram включён на сервере (ADR-065) — в шапке кнопка входа. */
  const [входTelegram, setВходTelegram] = useState(false)
  const [входTick, setВходTick] = useState(0)
  /** Вход обязателен, а сессии нет: оболочка не трогает реестр — только карточка входа. */
  const [нуженВход, setНуженВход] = useState<boolean | null>(null)
  /** Плотность, выбранная руками; null — умолчание роли. */
  const [режим, setРежим] = useState<Режим | null>(null)
  /** «Просторно» (шип 5 §1.3, §1.5): слово у пиктограмм и в таблице, три колонки граней карточки. */
  const [просторно, setПросторно] = useState<boolean>(() => {
    try { return localStorage.getItem('orbita.v2.spacious') === '1' } catch { return false }
  })
  const переключитьПросторно = (вкл: boolean) => {
    setПросторно(вкл)
    try { localStorage.setItem('orbita.v2.spacious', вкл ? '1' : '0') } catch { /* без хранилища — до перезагрузки */ }
  }
  const [failure, setFailure] = useState<string | null>(null)

  useEffect(() => {
    if (нуженВход !== false) return
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
  }, [нуженВход, portfolioTick])

  useEffect(() => {
    if (!project) { setPhase(null); return }
    localStorage.setItem('orbita.v2.project', project)
    api.phase(project).then(setPhase).catch(() => setPhase(null))
    api.myTasks(project).then((r) => setTasks(r.items.filter((з) => !з.waiting).length)).catch(() => undefined)
  }, [project, section, phaseTick])

  useEffect(() => {
    fetch('/api/auth/whoami')
      .then((r) => r.json())
      .then((d) => {
        setUsers(d.stand_users ?? [])
        setMe(d.user?.author ?? d.user?.display_name ?? null)
        setЯ(d.user ?? null)
        setВходTelegram(d.mode === 'telegram')
        setНуженВход(Boolean(d.enabled) && !d.user && d.mode === 'telegram')
      })
      .catch((e) => setFailure(String(e)))
  }, [входTick])

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

  if (нуженВход) {
    return (
      <div className="v2-shell">
        <main className="v2-main">
          <div className="v2-panel" data-why="почему-нельзя">
            <h3>Вход в «Орбиту»</h3>
            <div className="v2-empty__why">Стенд закрыт входом: пропуск даёт членство в группе Telegram проекта.</div>
            <TelegramВход onDone={() => setВходTick((t) => t + 1)} />
          </div>
        </main>
      </div>
    )
  }

  /**
   * Раздел «с сцены N» показывается, когда сцена открыта или прожита; фаза без
   * такой сцены (Phase A после Pre-A) показывает его всегда. Эксперт-режим
   * показывает всё.
   */
  const сценаДостигнута = (ключ?: string) => {
    if (!ключ || !phase) return true
    const сцена = phase.scenes.find((с) => с.key === ключ)
    return !сцена || сцена.state !== 'locked'
  }
  const visible = SECTIONS.filter((s) => (expert || !s.expert) && (expert || сценаДостигнута(s.fromScene)))
  const current = SECTIONS.find((s) => s.key === section) ?? SECTIONS[0]
  const точка = phase?.gates.find((т) => !т.passed)

  return (
    <ПлотностьКонтекст.Provider value={{ режим: текущийРежим, просторно, эксперт: expert, кто: me }}>
    <div className="v2-shell">
      {обновление && (
        <div className="v2-locked" role="status">
          Вышло обновление интерфейса — страница держит прежнюю сборку и может перестать отвечать.
          {' '}
          <button type="button" className="v2-chip" onClick={() => window.location.reload()}
            title="перезагрузить страницу: несохранённый ввод в формах пропадёт">
            обновить страницу
          </button>
        </div>
      )}
      <header className="v2-top">
        <div className="v2-ctx">
          <span className="v2-brand" title="Орбита — ИС поддержки разработки космической системы IoT">Орбита</span>
          {portfolio.length > 0 ? (
            <select className="v2-project" value={project ?? ''} title="проект портфеля: выбор помнится"
              aria-label="проект"
              onChange={(e) => setProject(e.target.value || null)}>
              <option value="">— выберите проект —</option>
              {portfolio.map((п) => <option key={п.code} value={п.code}>{п.name}</option>)}
            </select>
          ) : project ? (
            // Портфель ещё не перечитан, а проект уже выбран: называть его
            // «не выбранным» значило бы врать о том, что видно на экране.
            <b title="портфель перечитывается">{project}</b>
          ) : (
            <b title="портфеля нет: заведите проект в разделе «Проекты»">Портфель пуст</b>
          )}
          {phase && <span className="v2-chip" title={`фаза проекта · стандарт ${phase.standard}`}>{phase.phase}</span>}
          {точка && (
            <span className="v2-gatechip" title={точка.blocking.join('; ') || 'условия точки выполнены'}>
              <Маркер род="точка" состояние={точка.blocking.length > 0 ? 'блок' : 'текущее'} подпись={точка.title} />
              {' '}{точка.title}
              {точка.planned_date && ` · ${датаКратко(точка.planned_date)}`}
              {' · '}
              {точка.blocking.length > 0
                ? <span className="v2-bad">блокирует {точка.blocking.length}</span>
                : <span className="v2-ok">условия выполнены</span>}
            </span>
          )}
        </div>
        <div className="v2-me">
          <button type="button" className="v2-link" title="адресованные разрывы: что закрыть именно мне"
            onClick={() => setSection('tasks')}>
            мои <b>{tasks}</b>
          </button>
          {входTelegram && !me ? (
            <TelegramВход onDone={() => setВходTick((t) => t + 1)} />
          ) : (
            <МенюУчётки
              имя={me ?? (users.length > 0 ? `учётки стенда: ${users.length}` : 'учётка не выбрана')}
              роль={роль ? (ИМЯ_РОЛИ[роль] ?? роль) : 'роль не назначена'}
              я={я} expert={expert} onExpert={setExpert}
              просторно={просторно} onПросторно={переключитьПросторно}
              режим={текущийРежим} умолчание={ИМЯ_РЕЖИМА[режимПоРоли(роль)]} onРежим={(р) => setРежим(р)}
              onActAs={(код) => api.actAs(код || null).then(() => setВходTick((t) => t + 1)).catch((err) => setFailure(String(err)))}
              onLogout={входTelegram ? () => {
                fetch('/api/auth/logout', { method: 'POST' }).then(() => window.location.reload()).catch((err) => setFailure(String(err)))
              } : undefined} />
          )}
        </div>
      </header>

      <div className="v2-layout">
        <nav className="v2-rail" aria-label="разделы">
          {visible.filter((s) => !s.expert).map((s) => (
            <Фрагмент key={s.key}>
              {ЯРУС_С.has(s.key) && <div className="v2-rail__tier" role="separator" />}
              <button className="v2-rail__item" type="button"
                aria-current={s.key === section ? 'page' : undefined}
                title={s.hint}
                onClick={() => setSection(s.key)}>
                <Икон имя={s.icon} />{s.title}
              </button>
            </Фрагмент>
          ))}
          <div className="v2-rail__sep" role="separator" />
          {SECTIONS.filter((s) => s.expert).map((s) => (
            <button key={s.key} className={expert ? 'v2-rail__item' : 'v2-rail__item v2-rail__item--exp'} type="button"
              aria-current={s.key === section ? 'page' : undefined}
              disabled={!expert}
              title={expert ? s.hint : `${s.hint} — откроется в эксперт-режиме (меню учётки)`}
              onClick={() => setSection(s.key)}>
              <Икон имя={s.icon} />{s.title}
            </button>
          ))}
        </nav>

        <main className="v2-main">
          {failure && (
            <div className="v2-panel" data-why="почему-нельзя">
              <h3>Стенд не ответил</h3>
              <div className="v2-empty">{failure}</div>
            </div>
          )}
          {section === 'work' ? (
            <Work project={project} onProject={(п) => { setProject(п); setPortfolioTick((т) => т + 1) }}
              wantScene={wantScene}
              wantReason={wantReason}
              onScenePicked={() => setWantScene(null)}
              роль={роль} режим={режим} onРежим={setРежим} />
          ) : section === 'knowledge' ? (
            <KnowledgeField project={project} expert={expert} ручной={ручнойВвод}
              onGoGlossary={() => setSection('glossary')} точки={phase?.gates}
              onGoScene={(сцена, зачем) => { setWantScene(сцена); setWantReason(зачем ?? null); setSection('work') }} />
          ) : section === 'formulation' ? (
            <Formulation project={project} фаза={phase}
              onGoScene={(сцена, зачем) => { setWantScene(сцена); setWantReason(зачем ?? null); setSection('work') }} />
          ) : section === 'concept' ? (
            <Concept project={project} />
          ) : section === 'requirements' ? (
            <Requirements project={project} />
          ) : section === 'architecture' ? (
            <ArchitectureScreen project={project} />
          ) : section === 'models' ? (
            <Models project={project} />
          ) : section === 'documents' ? (
            <Documents project={project} expert={expert}
              onGoScene={(сцена, зачем) => {
                setWantScene(сцена); setWantReason(зачем ?? null); setSection('work')
              }}
              onGoField={() => { setРучнойВвод(true); setSection('knowledge') }} />
          ) : section === 'glossary' ? (
            <GlossaryScreen project={project} />
          ) : section === 'library' ? (
            <Library project={project} />
          ) : section === 'external' ? (
            <ExternalModelScreen project={project} />
          ) : section === 'points' ? (
            <Points project={project} phase={phase} onChanged={() => setPhaseTick((t) => t + 1)}
              учётка={я}
              onGoRisk={(код) => { setWantRisk(код); setSection('risks') }}
              onGoScene={(сцена) => { setWantScene(сцена); setSection('work') }} />
          ) : section === 'projects' ? (
            <ProjectsScreen
              onOpen={(код) => { setProject(код); setSection('work') }}
              onNew={() => setSection('newproject')} />
          ) : section === 'newproject' ? (
            <NewProjectScreen
              onCreated={(код) => { setProject(код); setPortfolioTick((т) => т + 1); setSection('work') }}
              onCancel={() => setSection('projects')} />
          ) : section === 'bridge' ? (
            <BridgeScreen project={project} onGo={(куда) => {
              if (куда.scene) setWantScene(куда.scene)
              if (куда.section === 'risks' && куда.code) setWantRisk(куда.code)
              setSection(куда.section)
            }} />
          ) : section === 'mywork' ? (
            <MyWorkScreen project={project} учётка={я}
              onGo={(куда) => { if (куда.scene) setWantScene(куда.scene); setSection(куда.section) }} />
          ) : section === 'risks' ? (
            project
              ? <RiskRegistry project={project} wantRisk={wantRisk} сцены={phase?.scenes.map((с) => ({ key: с.key, title: с.title })) ?? []} />
              : <div className="v2-panel" data-why="почему-нельзя"><h3>Риски</h3><div className="v2-empty">Проект не выбран.</div></div>
          ) : section === 'passport' ? (
            <PassportScreen project={project} учётка={я}
              onChanged={() => { setPhaseTick((t) => t + 1); setPortfolioTick((т) => т + 1) }} />
          ) : section === 'tasks' ? (
            <MyTasks project={project} onGoScene={(сцена) => { setWantScene(сцена); setSection('work') }} />
          ) : (
            <div className="v2-panel" data-why="почему-нельзя">
              <h3>{current.title}</h3>
              <div className="v2-empty">
                Раздел строится шипом 2 (дизайн).
                <span className="v2-empty__why">{current.hint}.</span>
              </div>
            </div>
          )}
        </main>
      </div>
    </div>
    </ПлотностьКонтекст.Provider>
  )
}

/** Роли словами — те же, что на экране точки. */
const ИМЯ_РОЛИ: Record<string, string> = {
  lead: 'руководитель проекта', lead_se: 'ведущий системный инженер', specialist: 'инженер', da_review: 'DA', sma: 'SMA', reader: 'наблюдатель',
}

/**
 * Одно меню учётки в шапке (Stitch-2, шип 2): имя и роль, «выступить от
 * имени», плотность экрана, эксперт-режим, выход. В самой шапке личного
 * больше ничего нет — там проект, фаза и точка.
 */
function МенюУчётки({ имя, роль, я, expert, onExpert, просторно, onПросторно, режим, умолчание, onРежим, onActAs, onLogout }: {
  имя: string
  роль: string
  я: StandUser | null
  expert: boolean
  onExpert: (v: boolean) => void
  просторно: boolean
  onПросторно: (v: boolean) => void
  режим: Режим
  умолчание: string
  onРежим: (р: Режим) => void
  onActAs: (код: string) => void
  onLogout?: () => void
}) {
  const [открыто, setОткрыто] = useState(false)
  return (
    <span className="v2-account">
      <button type="button" className="v2-avatar" aria-haspopup="menu" aria-expanded={открыто}
        title={`${имя} · ${роль} — меню учётки`} onClick={() => setОткрыто(!открыто)}>
        {инициалы(имя)}
      </button>
      {открыто && (
        <div className="v2-menu" role="menu" aria-label="учётка">
          <div>
            <b>{имя}</b>
            <div className="v2-dim">{роль}</div>
          </div>
          {я?.can_act_as && (
            <label className="v2-field">
              <span className="v2-field__cap">выступить от имени роли</span>
              <select value={я.acting_role ?? ''} aria-label="выступить от имени роли"
                title="ADR-066: только владельцу системы; каждое действие пишется автором «имя как роль»"
                onChange={(e) => onActAs(e.target.value)}>
                <option value="">своя роль</option>
                {Object.entries(я.acting_roles ?? {}).map(([код, слово]) => (
                  <option key={код} value={код}>как {слово}</option>
                ))}
              </select>
            </label>
          )}
          <label className="v2-field">
            <span className="v2-field__cap">плотность экрана работы</span>
            <select value={режим} aria-label="плотность экрана" title={`умолчание роли — ${умолчание}`}
              onChange={(e) => onРежим(e.target.value as Режим)}>
              {(['мероприятие', 'сцена', 'фаза'] as Режим[]).map((р) => (
                <option key={р} value={р}>{ИМЯ_РЕЖИМА[р]}</option>
              ))}
            </select>
          </label>
          <label className="v2-inline" title={expert
            ? 'выключить эксперт-режим: останутся только разделы работы'
            : 'включить эксперт-режим: библиотека, обмен, внешняя модель и журналы'}>
            <input type="checkbox" checked={expert} onChange={(e) => onExpert(e.target.checked)} />
            эксперт-режим
          </label>
          <label className="v2-inline" title="просторно: слово рядом с каждой пиктограммой, три колонки в карточке объекта">
            <input type="checkbox" checked={просторно} onChange={(e) => onПросторно(e.target.checked)} />
            просторно
          </label>
          {onLogout && (
            <button type="button" className="v2-link" title="выйти: сессия закроется на сервере" onClick={onLogout}>Выйти</button>
          )}
        </div>
      )}
    </span>
  )
}

/**
 * Кнопка входа через Telegram в шапке v2 (ADR-065): сервер даёт ссылку на
 * общего бота, клиент ждёт approved опросом раз в две секунды; сессию ставит
 * сервер cookie, после чего шапка перечитывает whoami.
 */
function TelegramВход({ onDone }: { onDone: () => void }) {
  const [ссылка, setСсылка] = useState<string | null>(null)
  const [состояние, setСостояние] = useState<'idle' | 'waiting' | 'denied' | 'expired' | 'error'>('idle')
  const [почему, setПочему] = useState<string | null>(null)
  const начать = () => {
    setПочему(null)
    api.authStart()
      .then((s) => {
        setСсылка(s.deep_link)
        setСостояние('waiting')
        window.open(s.deep_link, '_blank', 'noopener')
        const t0 = Date.now()
        const шаг = () => {
          api.authStatus(s.token)
            .then((r) => {
              if (r.status === 'approved') { onDone(); return }
              if (r.status === 'denied' || r.status === 'expired') { setСостояние(r.status); return }
              if (Date.now() - t0 > 5 * 60 * 1000) { setСостояние('expired'); return }
              window.setTimeout(шаг, 2000)
            })
            .catch((e) => { setСостояние('error'); setПочему(String(e)) })
        }
        window.setTimeout(шаг, 2000)
      })
      .catch((e) => { setСостояние('error'); setПочему(String(e)) })
  }
  return (
    <span className="v2-inline" title="общий бот входа: нажмите в нём Start — членство в группе проекта и есть пропуск">
      <button type="button" className="v2-chip" onClick={начать} disabled={состояние === 'waiting'}
        title={состояние === 'waiting'
          ? 'ждём, пока вы нажмёте Start в боте; не дождётесь за пять минут — кнопка оживёт сама'
          : 'откроется общий бот входа; нажмите в нём Start — членство в группе проекта и есть пропуск'}>
        {состояние === 'waiting' ? 'ждём Telegram…' : 'Войти через Telegram'}
      </button>
      {ссылка && состояние === 'waiting' && <a className="v2-link" href={ссылка} target="_blank" rel="noreferrer">ссылка</a>}
      {состояние === 'denied' && <span className="v2-bad">вас нет в группе проекта</span>}
      {состояние === 'expired' && <span className="v2-warn">время вышло — ещё раз</span>}
      {состояние === 'error' && <span className="v2-bad">{почему}</span>}
    </span>
  )
}

