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
import { ФункцииКУзлам } from './links'

/** Длинный состав: дальше перечень читают как справку, а не как экран работы. */
const ДЛИННЫЙ = 12

export function Concept({ project }: { project: string | null }) {
  const [узлы, setУзлы] = useState<ComponentRow[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [новый, setНовый] = useState({ code: '', name: '', nature: 'node', level: 3, kind: 'subsystem' })
  const [развёртывание, setРазвёртывание] = useState({ behaviour: '', node: '', rationale: '' })
  const [концепции, setКонцепции] = useState<ConceptRow[]>([])
  const [вариант, setВариант] = useState({ variant: '', rationale: '', rejected: '', reason: '' })

  const [развёрнут, setРазвёрнут] = useState(false)
  const [каркас, setКаркас] = useState<{ shelf: string; nodes?: number; already?: number; levels?: number; rule?: string; note: string } | null>(null)
  const [беру, setБеру] = useState(false)
  const [итог, setИтог] = useState<string | null>(null)

  const перечитать = useCallback(() => {
    if (!project) return
    api.components(project).then((r) => setУзлы(r.items)).catch((e) => setОтказ(String(e.message ?? e)))
    api.concept(project).then((r) => setКонцепции(r.items)).catch(() => undefined)
    api.frame(project).then(setКаркас).catch(() => undefined)
  }, [project])

  /**
   * Взять каркас состава с полки класса миссии. Правило взятия — в самой
   * полке («уровни 0–3 всегда»), и экран его показывает: инженер видит, что
   * придёт, до нажатия. Глубже — узлом, когда понадобится.
   */
  const взять = () => {
    if (!project) return
    setБеру(true); setОтказ(null); setИтог(null)
    api.takeFrame(project)
      .then((р) => { setИтог(р.note); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setБеру(false))
  }

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
          {/*
            Длинный состав — это СПРАВКА, а не работа: сорок девять узлов
            занимали весь первый экран сцены, и карточки ниже человек просто
            не находил (владелец, 21.09: «есть только базовый вариант»).
            Свёрнут, пока не понадобится; короткий состав виден целиком.
          */}
          {узлы.length > ДЛИННЫЙ && (
            <button type="button" className="v2-link" aria-pressed={развёрнут}
              title={развёрнут ? 'свернуть перечень узлов' : 'показать все узлы состава'}
              onClick={() => setРазвёрнут(!развёрнут)}>
              {развёрнут ? 'свернуть' : `показать все ${узлы.length}`}
            </button>
          )}
        </div>
        {итог && <div className="v2-note-line">{итог}</div>}
        {каркас && (каркас.nodes ?? 0) > (каркас.already ?? 0) && (
          <div className="v2-form__actions">
            <button type="button" className="v2-primary" disabled={беру}
              title={каркас.rule
                ? `правило полки ${каркас.shelf}: ${каркас.rule}`
                : `каркас с полки ${каркас.shelf}`}
              onClick={взять}>
              {беру
                ? 'Беру каркас…'
                : `Взять каркас состава (${(каркас.nodes ?? 0) - (каркас.already ?? 0)} узлов с полки ${каркас.shelf})`}
            </button>
            <span className="v2-dim">{каркас.note}</span>
          </div>
        )}
        {узлы.length === 0 ? (
          <div className="v2-empty">
            Состав пуст — сцена 7 держится этим.
            <span className="v2-empty__why">
              {каркас?.shelf
                ? 'Состав берётся каркасом класса миссии — кнопкой выше; узлы глубже уровня каркаса заводятся по одному, когда понадобятся.'
                : 'Нужно не менее трёх узлов: система, её элементы и то, что ими управляет.'}
            </span>
          </div>
        ) : (
          <ul className="v2-tree">
            {(развёрнут ? узлы : узлы.slice(0, ДЛИННЫЙ)).map((у) => (
              <li key={у.code}>
                <span className="v2-tree__node">{у.code}</span> {у.name}
                <span className="v2-dim"> · уровень {у.level} · {у.nature === 'behaviour' ? 'поведение' : 'носитель'}</span>
              </li>
            ))}
            {!развёрнут && узлы.length > ДЛИННЫЙ && (
              <li className="v2-dim">…и ещё {узлы.length - ДЛИННЫЙ}: «показать все» в шапке карточки</li>
            )}
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


      {/* Варианты — ПЕРЕД выбором базового: выбирают из них, и карточка
          стоит там, где человек и смотрит. Сравнение — рядом с выбором: решение принимают, ГЛЯДЯ на показатели,
          а не вспоминая их. Балла у варианта нет намеренно. */}
      {/* Четвёртая раздача — функции → узлы — тем же порядком, что и три прежние (шип 1). */}
      <ФункцииКУзлам project={project} />
      <Variants project={project} />

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
              <span className="v2-empty__why">решил {к.decided_by}</span>
              <СпискиКонцепции project={project} концепция={к} onChanged={перечитать} />
            </div>
          ))
        )}
        {концепции.length === 0 && (
        <div className="v2-form">
          <label>Вариант
            <input value={вариант.variant} placeholder="V1 · 24 КА в трёх плоскостях"
              onChange={(e) => setВариант({ ...вариант, variant: e.target.value })} />
          </label>
          <label>Обоснование выбора
            <textarea rows={2} value={вариант.rationale}
              placeholder="чем этот вариант лучше остальных по целям и ограничениям"
              autoComplete="off"
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
        )}
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
    </>
  )
}


/**
 * Отказы от объёма и отклонённые варианты — списками у принятой концепции.
 *
 * Выбор варианта — миг, а отказы копятся по ходу сцены: «это вынесено за
 * границы первой очереди, потому что…». До 21.09 форма слала `descopes: []`
 * жёстко, и §9 отчёта о концепции миссии («Что вынесено за границы первой
 * очереди») наполнить было нечем ничем — KDP-A держалась этим.
 */
function СпискиКонцепции({ project, концепция, onChanged }: {
  project: string
  концепция: ConceptRow
  onChanged: () => void
}) {
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  const [вынос, setВынос] = useState({ text: '', impact: '' })
  const [отклонён, setОтклонён] = useState({ variant: '', reason: '' })

  const дописать = (поле: 'descopes' | 'rejected', строка: Record<string, string>) => {
    setЗанято(true); setОтказ(null)
    const было = поле === 'descopes' ? (концепция.descopes ?? []) : (концепция.rejected ?? [])
    api.patchEntity(project, концепция.code, { [поле]: [...было, строка] }, 'инженер')
      .then(() => { setВынос({ text: '', impact: '' }); setОтклонён({ variant: '', reason: '' }); onChanged() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-note">
      {отказ && <div className="v2-locked">{отказ}</div>}

      <span className="v2-field__cap">
        Отложенное содержание — {(концепция.descopes ?? []).length}
      </span>
      {(концепция.descopes ?? []).map((о, i) => (
        <span key={`${о.text}-${i}`} className="v2-dim">· {о.text} — {о.impact}</span>
      ))}
      <div className="v2-form v2-form--row">
        <label>что вынесено
          <input value={вынос.text} placeholder="межспутниковая связь в первой очереди"
            aria-label="отложенное содержание: что вынесено"
            onChange={(e) => setВынос({ ...вынос, text: e.target.value })} />
        </label>
        <label>чем платим
          <input value={вынос.impact} placeholder="задержка доставки до 40 мин вне зоны станций"
            aria-label="отложенное содержание: чем платим"
            onChange={(e) => setВынос({ ...вынос, impact: e.target.value })} />
        </label>
        <button type="button" className="v2-chip"
          disabled={занято || !вынос.text.trim() || !вынос.impact.trim()}
          title={!вынос.text.trim() || !вынос.impact.trim()
            ? 'отказ без цены — не решение: назовите, что вынесено и чем за это платим'
            : 'дописать отказ от объёма в решение'}
          onClick={() => дописать('descopes', { text: вынос.text.trim(), impact: вынос.impact.trim() })}>
          Дописать отказ
        </button>
      </div>

      <span className="v2-field__cap">
        Отклонённые варианты — {(концепция.rejected ?? []).length}
      </span>
      {(концепция.rejected ?? []).map((о, i) => (
        <span key={`${о.variant}-${i}`} className="v2-dim">· {о.variant} — {о.reason}</span>
      ))}
      <div className="v2-form v2-form--row">
        <label>вариант
          <input value={отклонён.variant} placeholder="V2 · 72 КА в шести плоскостях"
            aria-label="отклонённый вариант"
            onChange={(e) => setОтклонён({ ...отклонён, variant: e.target.value })} />
        </label>
        <label>причина отклонения
          <input value={отклонён.reason} placeholder="стоимость вне рамок финансирования"
            aria-label="причина отклонения варианта"
            onChange={(e) => setОтклонён({ ...отклонён, reason: e.target.value })} />
        </label>
        <button type="button" className="v2-chip"
          disabled={занято || !отклонён.variant.trim() || !отклонён.reason.trim()}
          title={!отклонён.reason.trim()
            ? 'отклонение без причины не записывается: через год будет не видно, чем платили'
            : 'дописать отклонённый вариант в решение'}
          onClick={() => дописать('rejected', { variant: отклонён.variant.trim(), reason: отклонён.reason.trim() })}>
          Дописать отклонённый
        </button>
      </div>
    </div>
  )
}

