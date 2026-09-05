// Оболочка v2 (ТЗ §4.2): рейка · шапка · эксперт-режим · портфель.
//
// Волна 0 строит именно каркас: рейка знает разделы, шапка знает, где
// инженер и что держит ближайшую точку, эксперт-режим выключен и включается
// явно. Содержимое разделов приходит волнами 1–7 — до тех пор раздел честно
// говорит, какой волной он открывается, вместо пустого экрана.
import { useEffect, useState } from 'react'
import { Work } from './work'
import { MyTasks } from './tasks'
import { KnowledgeField } from './knowledge'
import { Coverage } from './coverage'

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
  { key: 'tasks', title: 'Мои задания', wave: 1, hint: 'адресованные разрывы: что закрыть именно мне' },
  { key: 'formulation', title: 'Постановка', wave: 1, hint: 'замысел, стейкхолдеры, нужды, цели, ограничения, сервисы' },
  { key: 'knowledge', title: 'Поле знаний', wave: 2, hint: 'материалы, факты с якорями, загрузка с заданием' },
  { key: 'coverage', title: 'Покрытие', wave: 2, hint: 'матрица нужд: чем закрыта каждая и чего не хватает' },
  { key: 'concept', title: 'Концепция', wave: 3, hint: 'состав системы, варианты построения, сравнение' },
  { key: 'requirements', title: 'Требования', wave: 3, hint: 'реестр требований, два дерева, влияние правки' },
  { key: 'architecture', title: 'Архитектура', wave: 3, hint: 'операционный, системный, логический и физический слои' },
  { key: 'models', title: 'Модели', wave: 4, hint: 'записи моделей, прогоны, резервы' },
  { key: 'documents', title: 'Документы', wave: 5, hint: 'структура, рендеринг, рецензия, печать' },
  { key: 'points', title: 'Точки', wave: 6, hint: 'готовность по экспертизе, замечания, фиксация' },
  { key: 'library', title: 'Библиотека', wave: 2, expert: true, hint: 'полки, окно взятия, справочники' },
  { key: 'exchange', title: 'Обмен', wave: 5, expert: true, hint: 'StrictDoc и ReqIF, выгрузка знаний' },
  { key: 'journal', title: 'Журналы', wave: 5, expert: true, hint: 'журнал службы, история правок' },
]

/** Учётка стенда: вход селектором без пароля (ТЗ §4.2). */
type StandUser = { login: string; display_name: string }

export function Shell() {
  const [section, setSection] = useState('work')
  const [expert, setExpert] = useState(false)
  const [users, setUsers] = useState<StandUser[]>([])
  const [project, setProject] = useState<string | null>(null)
  /** Переход «к месту» из заданий: открыть работу на нужной сцене. */
  const [wantScene, setWantScene] = useState<string | null>(null)
  const [me, setMe] = useState<string | null>(null)
  const [failure, setFailure] = useState<string | null>(null)

  useEffect(() => {
    fetch('/api/auth/whoami')
      .then((r) => r.json())
      .then((d) => {
        setUsers(d.stand_users ?? [])
        setMe(d.user?.display_name ?? null)
      })
      .catch((e) => setFailure(String(e)))
  }, [])

  const visible = SECTIONS.filter((s) => expert || !s.expert)
  const current = SECTIONS.find((s) => s.key === section) ?? SECTIONS[0]

  return (
    <div className="v2-shell">
      <nav className="v2-rail" aria-label="разделы">
        <div className="v2-rail__group">Проект</div>
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
            <div className="v2-rail__group">Эксперт</div>
            {visible.filter((s) => s.expert).map((s) => (
              <button key={s.key} className="v2-rail__item" type="button"
                aria-current={s.key === section ? 'page' : undefined}
                title={s.hint}
                onClick={() => setSection(s.key)}>
                {s.title}
              </button>
            ))}
          </>
        )}
      </nav>

      <div>
        <header className="v2-head">
          <span className="v2-head__project">{project ?? 'Проект не выбран'}</span>
          <span className="v2-head__scene">
            {project ? 'фаза Pre-A · стандарт NASA-7120' : 'откройте проект — это сцена 1'}
          </span>
          <span className="v2-head__spacer" />
          <button type="button" className="v2-chip"
            aria-pressed={expert}
            title={expert
              ? 'выключить эксперт-режим: останутся только разделы текущей сцены'
              : 'включить эксперт-режим: библиотека, обмен и журналы'}
            onClick={() => setExpert(!expert)}>
            эксперт-режим: {expert ? 'включён' : 'выключен'}
          </button>
          <span title="учётка стенда: вход селектором, пароля у витринных учёток нет">
            {me ?? (users.length > 0 ? `учётки стенда: ${users.length}` : 'учётка не выбрана')}
          </span>
        </header>

        <main className="v2-work">
          {failure && (
            <div className="v2-card">
              <div className="v2-card__head"><span className="v2-card__title">Стенд не ответил</span></div>
              <div className="v2-empty">{failure}</div>
            </div>
          )}
          {section === 'work' ? (
            <Work project={project} onProject={setProject} wantScene={wantScene} onScenePicked={() => setWantScene(null)} />
          ) : section === 'knowledge' ? (
            <KnowledgeField project={project} />
          ) : section === 'coverage' ? (
            <Coverage project={project} />
          ) : section === 'formulation' ? (
            <Coverage project={project} />
          ) : section === 'tasks' ? (
            <MyTasks project={project} onGoScene={(сцена) => { setWantScene(сцена); setSection('work') }} />
          ) : (
            <div className="v2-card">
              <div className="v2-card__head">
                <span className="v2-card__title">{current.title}</span>
                <span className="v2-card__count">волна {current.wave}</span>
              </div>
              <div className="v2-empty">
                {current.hint}.
                <span className="v2-empty__why">
                  Раздел откроется волной {current.wave}; сейчас пройдены волны 0–1.
                </span>
              </div>
            </div>
          )}
        </main>
      </div>
    </div>
  )
}
