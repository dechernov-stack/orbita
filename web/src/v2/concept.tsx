// Экран концепции (сцена 7): состав каркасом, развёртывание поведения,
// базовый вариант с обоснованием.
//
// Правило сцены: состав берётся не с чистого листа, а каркасом класса
// миссии; поведенческий компонент без носителя — брак модели, и экран
// говорит об этом до того, как ворота откажут.
import { useCallback, useEffect, useState } from 'react'
import { api, type ComponentRow, type ConceptRow } from './api'
// Сравнение вариантов живёт одним местом с разделом моделей: у сцены 7 и
// у раздела «Модели» это ОДНА таблица показателей, а не две похожие.
import { Variants } from './models'

export function Concept({ project }: { project: string | null }) {
  const [узлы, setУзлы] = useState<ComponentRow[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [новый, setНовый] = useState({ code: '', name: '', nature: 'node', level: 3, kind: 'subsystem' })
  const [развёртывание, setРазвёртывание] = useState({ behaviour: '', node: '', rationale: '' })
  const [концепции, setКонцепции] = useState<ConceptRow[]>([])
  const [вариант, setВариант] = useState({ variant: '', rationale: '', rejected: '', reason: '' })

  const перечитать = useCallback(() => {
    if (!project) return
    api.components(project).then((r) => setУзлы(r.items)).catch((e) => setОтказ(String(e.message ?? e)))
    api.concept(project).then((r) => setКонцепции(r.items)).catch(() => undefined)
  }, [project])

  useEffect(перечитать, [перечитать])

  if (!project) {
    return <div className="v2-card"><div className="v2-empty">Сначала откройте проект.</div></div>
  }

  const поведения = узлы.filter((у) => у.nature === 'behaviour')
  const носители = узлы.filter((у) => у.nature === 'node')

  return (
    <>
      {отказ && <div className="v2-card"><div className="v2-locked">{отказ}</div></div>}

      <div className="v2-card">
        <div className="v2-card__head">
          <span className="v2-card__title">Состав системы</span>
          <span className="v2-card__count">{узлы.length}</span>
        </div>
        {узлы.length === 0 ? (
          <div className="v2-empty">
            Состав пуст — сцена 7 держится этим.
            <span className="v2-empty__why">Нужно не менее трёх узлов: система, её элементы и то, что ими управляет.</span>
          </div>
        ) : (
          <ul className="v2-tree">
            {узлы.map((у) => (
              <li key={у.code}>
                <span className="v2-tree__node">{у.code}</span> {у.name}
                <span className="v2-dim"> · уровень {у.level} · {у.nature === 'behaviour' ? 'поведение' : 'носитель'}</span>
              </li>
            ))}
          </ul>
        )}
        <div className="v2-form">
          <label>Код<input value={новый.code} placeholder="OBC-CPU"
            onChange={(e) => setНовый({ ...новый, code: e.target.value })} /></label>
          <label>Имя<input value={новый.name} placeholder="БЦВМ"
            onChange={(e) => setНовый({ ...новый, name: e.target.value })} /></label>
          <label>Род
            <select value={новый.nature} onChange={(e) => setНовый({ ...новый, nature: e.target.value })}>
              <option value="node">носитель (node): масса, габарит, тепло</option>
              <option value="behaviour">поведение (behaviour): функции, режимы, ресурсы</option>
            </select>
          </label>
          <div className="v2-form__actions">
            <button type="button" className="v2-primary" disabled={!новый.code.trim() || !новый.name.trim()}
              title="завести узел состава"
              onClick={() => api.addComponent(project, новый).then(перечитать).catch((e) => setОтказ(String(e.message ?? e)))}>
              Добавить узел
            </button>
          </div>
        </div>
      </div>

      <div className="v2-card">
        <div className="v2-card__head">
          <span className="v2-card__title">Базовый вариант</span>
          <span className="v2-card__count">{концепции.length}</span>
        </div>
        {концепции.length === 0 ? (
          <div className="v2-empty">
            Базовый вариант не назван — сцена 7 держится этим.
            <span className="v2-empty__why">
              Выбор без обоснования — не решение: отклонённые варианты остаются с причинами,
              а отказы от объёма записываются списком, чтобы через год было видно, чем платили.
            </span>
          </div>
        ) : (
          концепции.map((к) => (
            <div key={к.code} className="v2-note">
              <span className="v2-note__rule">{к.code}</span>
              <span>вариант {к.variant}: {к.rationale}</span>
              <span className="v2-empty__why">
                решил {к.decided_by}
                {к.rejected?.length > 0 && ` · отклонены: ${к.rejected.map((о) => `${о.variant} (${о.reason})`).join('; ')}`}
              </span>
            </div>
          ))
        )}
        <div className="v2-form">
          <label>Вариант
            <input value={вариант.variant} placeholder="V1 · 24 КА в трёх плоскостях"
              onChange={(e) => setВариант({ ...вариант, variant: e.target.value })} />
          </label>
          <label>Обоснование выбора
            <textarea rows={2} value={вариант.rationale}
              placeholder="чем этот вариант лучше остальных по целям и ограничениям"
              onChange={(e) => setВариант({ ...вариант, rationale: e.target.value })} />
          </label>
          <label>Отклонённый вариант
            <input value={вариант.rejected} placeholder="V2"
              onChange={(e) => setВариант({ ...вариант, rejected: e.target.value })} />
          </label>
          <label>Причина отклонения
            <input value={вариант.reason} placeholder="масса вне 12U…100 кг (Р2)"
              onChange={(e) => setВариант({ ...вариант, reason: e.target.value })} />
          </label>
          <div className="v2-form__actions">
            <button type="button" className="v2-primary"
              disabled={!вариант.variant.trim() || !вариант.rationale.trim()}
              title={!вариант.rationale.trim()
                ? 'обоснование обязательно: выбор без причины — не решение'
                : 'записать базовый вариант'}
              onClick={() => api.setConcept(project, {
                variant: вариант.variant,
                rationale: вариант.rationale,
                rejected: вариант.rejected.trim()
                  ? [{ variant: вариант.rejected, reason: вариант.reason }]
                  : [],
                descopes: [],
              })
                .then(() => { setВариант({ variant: '', rationale: '', rejected: '', reason: '' }); перечитать() })
                .catch((e) => setОтказ(String(e.message ?? e)))}>
              Назвать базовым
            </button>
          </div>
        </div>
      </div>

      <div className="v2-card">
        <div className="v2-card__head">
          <span className="v2-card__title">Развёртывание поведения</span>
          <span className="v2-card__count">{поведения.length}</span>
        </div>
        <div className="v2-empty__why">
          Поведенческий компонент обязан быть развёрнут хотя бы на одном носителе:
          без этого его не соберёт ни модель, ни Capella.
        </div>
        <div className="v2-form">
          <label>Поведение
            <select value={развёртывание.behaviour}
              onChange={(e) => setРазвёртывание({ ...развёртывание, behaviour: e.target.value })}>
              <option value="">—</option>
              {поведения.map((у) => <option key={у.code} value={у.code}>{у.code} · {у.name}</option>)}
            </select>
          </label>
          <label>Носитель
            <select value={развёртывание.node}
              onChange={(e) => setРазвёртывание({ ...развёртывание, node: e.target.value })}>
              <option value="">—</option>
              {носители.map((у) => <option key={у.code} value={у.code}>{у.code} · {у.name}</option>)}
            </select>
          </label>
          <label>Обоснование
            <input value={развёртывание.rationale} placeholder="почему именно на этом носителе"
              onChange={(e) => setРазвёртывание({ ...развёртывание, rationale: e.target.value })} />
          </label>
          <div className="v2-form__actions">
            <button type="button" className="v2-primary"
              disabled={!развёртывание.behaviour || !развёртывание.node || !развёртывание.rationale.trim()}
              title={!развёртывание.rationale.trim()
                ? 'связь без причины неотличима от случайной — напишите обоснование'
                : 'развернуть поведение на носителе'}
              onClick={() => api.deploy(project, развёртывание)
                .then(() => { setРазвёртывание({ behaviour: '', node: '', rationale: '' }); перечитать() })
                .catch((e) => setОтказ(String(e.message ?? e)))}>
              Развернуть
            </button>
          </div>
        </div>
      </div>

      {/* Сравнение — рядом с выбором: решение принимают, ГЛЯДЯ на показатели,
          а не вспоминая их. Балла у варианта нет намеренно. */}
      <Variants project={project} />
    </>
  )
}
