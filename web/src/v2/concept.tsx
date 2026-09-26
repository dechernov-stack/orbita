// Экран концепции (сцена 7): состав каркасом, развёртывание поведения,
// базовый вариант с обоснованием.
//
// Правило сцены: состав берётся не с чистого листа, а каркасом класса
// миссии; поведенческий компонент без носителя — брак модели, и экран
// говорит об этом до того, как ворота откажут.
//
// Шип 5 §7: вкладки «Состав · Варианты · Базовая · Модели» — одна строка
// под заголовком раздела; узел состава открывается карточкой объекта §1.3
// с гранями анкеты. Та же концепция стоит на сцене 7 и на A6 фазы A.
import { useCallback, useEffect, useState } from 'react'
import { api, type ComponentRow, type ConceptRow } from './api'
// Сравнение вариантов живёт одним местом с разделом моделей: у сцены 7 и
// у раздела «Модели» это ОДНА таблица показателей, а не две похожие.
import { Models, Variants } from './models'
import { ФункцииКУзлам } from './links'
import { CompositionTree } from './composition'
import { useВкладка, Вкладки, type Вкладка } from './ui/tabs'

type ВкладкаКонцепции = 'состав' | 'варианты' | 'базовая' | 'модели'
export const ВКЛАДКИ_КОНЦЕПЦИИ: readonly ВкладкаКонцепции[] = ['состав', 'варианты', 'базовая', 'модели']

/** Строка вкладок концепции: счёт и маркер — из прочитанного, слово долга — в подсказке. */
export function вкладкиКонцепции(с: { узлов: number; вариантов: number | null; базовая: boolean; моделей: number | null }): Вкладка<ВкладкаКонцепции>[] {
  return [
    { key: 'состав', word: 'Состав', count: с.узлов, hint: 'узлы системы деревом с карточкой и анкетой, развёртывание поведения, функции на узлах' },
    {
      key: 'варианты', word: 'Варианты', count: с.вариантов,
      health: с.вариантов === null ? null : с.вариантов < 2 ? 'debt' : 'ok',
      hint: с.вариантов !== null && с.вариантов < 2
        ? 'сравнивать нечего: вариантов построения меньше двух'
        : 'варианты построения и их показатели — сравнение без балла',
    },
    {
      key: 'базовая', word: 'Базовая', health: с.базовая ? 'ok' : 'debt',
      hint: с.базовая ? 'базовый вариант с обоснованием, отказы от объёма, отклонённые варианты' : 'базовый вариант не назван — сцена 7 держится этим',
    },
    { key: 'модели', word: 'Модели', count: с.моделей, hint: 'записи моделей, прогоны и свёртка величин' },
  ]
}

export function Concept({ project }: { project: string | null }) {
  const [узлы, setУзлы] = useState<ComponentRow[]>([])
  const [отказ, setОтказ] = useState<string | null>(null)
  const [развёртывание, setРазвёртывание] = useState({ behaviour: '', node: '', rationale: '' })
  const [концепции, setКонцепции] = useState<ConceptRow[]>([])
  const [вариант, setВариант] = useState({ variant: '', rationale: '', rejected: '', reason: '' })
  const [вкладка, setВкладка] = useВкладка<ВкладкаКонцепции>('concept', 'состав', ВКЛАДКИ_КОНЦЕПЦИИ)
  const [вариантов, setВариантов] = useState<number | null>(null)
  const [моделей, setМоделей] = useState<number | null>(null)

  const перечитать = useCallback(() => {
    if (!project) return
    api.components(project).then((r) => setУзлы(r.items)).catch((e) => setОтказ(String(e.message ?? e)))
    api.concept(project).then((r) => setКонцепции(r.items)).catch(() => undefined)
    api.variants(project).then((r) => setВариантов(r.items.length)).catch(() => setВариантов(null))
    api.models(project).then((r) => setМоделей(r.items.length)).catch(() => setМоделей(null))
  }, [project])

  useEffect(перечитать, [перечитать])

  if (!project) {
    return <div className="v2-card"><div className="v2-empty">Сначала откройте проект.</div></div>
  }

  const поведения = узлы.filter((у) => у.nature === 'behaviour')
  const носители = узлы.filter((у) => у.nature === 'node')

  return (
    <>
      <Вкладки label="концепция" current={вкладка} onChange={setВкладка}
        items={вкладкиКонцепции({ узлов: узлы.length, вариантов, базовая: концепции.length > 0, моделей })} />
      {отказ && <div className="v2-card"><div className="v2-locked">{отказ}</div></div>}

      {/*
        Состав — дерево-таблица (шип 2, экран 8): 135 узлов читаются строками
        с раскрытием, правка идёт на месте, действия — пиктограммами. Прежняя
        карточка со списком и формой внизу жила тут же и не читалась (З-22).
        Четвёртая раздача — функции → узлы — тем же порядком, что и три
        прежние (шип 1); развёртывание поведения — там же: это тоже состав.
      */}
      {вкладка === 'состав' && (
        <>
          <CompositionTree project={project} />
          <ФункцииКУзлам project={project} />
          <Развёртывание project={project} поведения={поведения} носители={носители}
            развёртывание={развёртывание} setРазвёртывание={setРазвёртывание} onDone={перечитать} onОтказ={setОтказ} />
        </>
      )}

      {/* Варианты — отдельной вкладкой: сравнение без балла, решение за человеком. */}
      {вкладка === 'варианты' && <Variants project={project} />}

      {вкладка === 'модели' && <Models project={project} варианты={false} />}

      {вкладка === 'базовая' && (
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
      )}
    </>
  )
}

/** Развёртывание поведения на носителе: поведенческий компонент без носителя — брак модели. */
function Развёртывание({ project, поведения, носители, развёртывание, setРазвёртывание, onDone, onОтказ }: {
  project: string
  поведения: ComponentRow[]
  носители: ComponentRow[]
  развёртывание: { behaviour: string; node: string; rationale: string }
  setРазвёртывание: (р: { behaviour: string; node: string; rationale: string }) => void
  onDone: () => void
  onОтказ: (т: string) => void
}) {
  return (
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
              .then(() => { setРазвёртывание({ behaviour: '', node: '', rationale: '' }); onDone() })
              .catch((e) => onОтказ(String(e.message ?? e)))}>
            Развернуть
          </button>
        </div>
      </div>
    </div>
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

