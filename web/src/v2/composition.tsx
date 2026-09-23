// Состав системы деревом-таблицей (шип 2, экран 8).
//
// 135 узлов списком нечитаемы, а карточками — тем более (журнал ПМИ-7, З-22).
// Здесь дерево-таблица: раскрытие, колонки «код · наименование · ×N ·
// ключевая величина · ступень · анкета», правка на месте кликом по ячейке и
// действия строки пиктограммами с подсказкой. Панелей и боковых рамок нет:
// строки разделены графитной линией, и экран остаётся одним.
//
// Ни одного вердикта экран не выносит: ступень зрелости и разрывы считает
// карточка узла на сервере, какие величины спрашивать — говорит полка,
// кратность живёт своим видом. Сюда приходит уже сосчитанное.
import { useCallback, useEffect, useState } from 'react'
import './composition.css'
import { api, type ComponentCard } from './api'
import {
  величинаСловами, всеУзлы, видимые, ключиВеличин, читатьСостав,
  type СоставЭкрана, type УзелСостава,
} from './composition.api'
import { Икон } from './icons'
import { ConfirmBox, useConfirm } from '../ui/Confirm'

export function CompositionTree({ project }: { project: string }) {
  const [состав, setСостав] = useState<СоставЭкрана | null>(null)
  const [открытые, setОткрытые] = useState<Set<string>>(new Set())
  const [ключ, setКлюч] = useState('')
  const [правится, setПравится] = useState<{ узел: string; поле: string } | null>(null)
  const [черновик, setЧерновик] = useState('')
  const [карточка, setКарточка] = useState<ComponentCard | null>(null)
  const [добавляем, setДобавляем] = useState<string | null>(null)
  const [новый, setНовый] = useState({ code: '', name: '', kind: '' })
  const [отказ, setОтказ] = useState<string | null>(null)
  const [занято, setЗанято] = useState(false)
  const [ask, askConfirm, closeConfirm] = useConfirm()

  const перечитать = useCallback(() => {
    читатьСостав(project)
      .then((с) => {
        setСостав(с)
        // Развёрнуто до второго уровня: глубже — по клику инженера.
        setОткрытые((было) => (было.size > 0 ? было : new Set(всеУзлы(с.корни).filter((у) => у.level <= 1).map((у) => у.code))))
        setКлюч((к) => к || ключиВеличин(с)[0]?.key || '')
      })
      .catch((e) => setОтказ(String(e.message ?? e)))
  }, [project])
  useEffect(перечитать, [перечитать])

  if (отказ) return <div className="v2-card"><div className="v2-locked">{отказ}</div></div>
  if (!состав) return <div className="v2-card"><div className="v2-empty">Читаю состав…</div></div>

  const строки = видимые(состав.корни, открытые)
  const величины = ключиВеличин(состав)
  const выбранная = величины.find((в) => в.key === ключ)
  const единица = (код: string) => состав.единицы[код] ?? код
  const знак = (код: string) => состав.знаки[код] ?? код
  const величинаУзла = (узел: string) => состав.величины.find((в) => в.узел === узел && в.key === ключ)
  const слово = (поле: string, значение: string) => состав.значения[поле]?.[значение] ?? значение

  const раскрыть = (код: string, да: boolean) => {
    setОткрытые((было) => {
      const новое = new Set(было)
      if (да) новое.add(код); else новое.delete(код)
      return новое
    })
  }

  const развернуть = (до: number) => {
    setОткрытые(new Set(всеУзлы(состав.корни).filter((у) => у.level <= до).map((у) => у.code)))
  }

  const сохранить = (узел: УзелСостава, поле: string, значение: string) => {
    setПравится(null)
    const было = поле === 'name' ? узел.name : ''
    if (значение.trim() === было.trim()) return
    setЗанято(true); setОтказ(null)
    api.patchEntity(project, узел.code, { [поле]: значение.trim() }, 'инженер', 'правка состава на месте')
      .then(перечитать)
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  const сохранитьКратность = (узел: УзелСостава, значение: string) => {
    setПравится(null)
    const кратность = состав.кратность[узел.code]
    const поле = состав.кратностьПоле
    if (!поле) { setОтказ('истина схем не называет поля кратности у вхождения — править нечего'); return }
    if (!кратность?.запись) { setОтказ(`кратность узла ${узел.code} приходит с полки: правится она вхождением, а его в проекте нет`); return }
    setЗанято(true); setОтказ(null)
    api.patchEntity(project, кратность.запись, { [поле.поле]: значение.trim() }, 'инженер', 'кратность узла')
      .then(перечитать)
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  const снять = (узел: УзелСостава) => {
    askConfirm({
      question: `Снять узел ${узел.code} «${узел.name}» с применения? Он останется в составе с обоснованием отклонения.`,
      ok: 'Снять',
      input: { label: 'обоснование', placeholder: 'почему узел не применяется' },
      onOk: (почему) => {
        if (!почему?.trim()) { setОтказ('снятие без обоснования не записывается: причина — часть решения'); return }
        api.patchEntity(project, узел.code, { applicability: 'not_applicable', deviation: почему.trim() }, 'инженер', 'узел снят с применения')
          .then(перечитать)
          .catch((e) => setОтказ(String(e.message ?? e)))
      },
    })
  }

  const завести = (родитель: string) => {
    if (!новый.code.trim() || !новый.name.trim()) return
    setЗанято(true); setОтказ(null)
    api.addComponent(project, {
      code: новый.code.trim(), name: новый.name.trim(),
      kind: новый.kind || состав.перечни.kind?.[0] || 'subsystem',
      parent: родитель,
    })
      .then(() => { setДобавляем(null); setНовый({ code: '', name: '', kind: '' }); перечитать() })
      .catch((e) => setОтказ(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }

  return (
    <div className="v2-card" data-why="работа">
      <div className="v2-card__head">
        <span className="v2-card__title">
          Состав системы{состав.вариант ? ` · вариант ${состав.вариант}` : ''}
        </span>
        <span className="v2-card__count">{состав.всего} узлов · ступень к {состав.точка.title || состав.точка.key}</span>
        <span className="v2-head__spacer" />
        <label className="v2-inline" title="раскрыть дерево до уровня: глубже — по клику на строке">
          развернуть до
          <select value="" aria-label="развернуть дерево до уровня"
            onChange={(e) => e.target.value && развернуть(Number(e.target.value))}>
            <option value="">— уровень —</option>
            <option value="0">L1 — корни</option>
            <option value="1">L2</option>
            <option value="2">L3</option>
            <option value="9">все</option>
          </select>
        </label>
        <label className="v2-inline" title="какую величину показывать колонкой: ключи спрашивает полка каркаса">
          показать
          <select value={ключ} aria-label="ключевая величина колонкой" onChange={(e) => setКлюч(e.target.value)}>
            {величины.length === 0 && <option value="">величин полка не спрашивает</option>}
            {величины.map((в) => <option key={в.key} value={в.key}>{в.name}{в.unit ? `, ${единица(в.unit)}` : ''}</option>)}
          </select>
        </label>
      </div>

      {отказ && <div className="v2-locked">{отказ}</div>}
      {состав.всего === 0 ? (
        <div className="v2-empty">
          Состава нет.
          <span className="v2-empty__why">
            {состав.каркас?.note ?? 'Состав берётся каркасом класса миссии на этом же экране — не с чистого листа.'}
          </span>
        </div>
      ) : (
        <table className="v2-table v2-tree2">
          <thead>
            <tr>
              <th>{состав.метки.code ?? 'Код'}</th>
              <th>{состав.метки.name ?? 'Наименование'}</th>
              <th>{состав.кратностьПоле?.метка ?? '×N'}</th>
              <th>{выбранная ? `${выбранная.name}${выбранная.unit ? `, ${единица(выбранная.unit)}` : ''}` : 'Величина'}</th>
              <th>Ступень</th>
              <th>Анкета</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {строки.map(({ узел, глубина, естьДети }) => {
              const величина = величинаУзла(узел.code)
              const анкета = состав.анкета[узел.code] ?? []
              const заполнено = анкета.filter((п) => состав.величины.some((в) => в.узел === узел.code && в.key === п.key)).length
              const снят = узел.applicability === 'not_applicable'
              return (
                <tr key={узел.code} className={снят ? 'v2-tree2__row v2-dim' : 'v2-tree2__row'}>
                  <td style={{ paddingLeft: `${глубина * 16}px` }}>
                    {естьДети ? (
                      <button type="button" className="v2-link" aria-expanded={открытые.has(узел.code)}
                        title={открытые.has(узел.code) ? 'свернуть ветку' : `раскрыть ветку: ${узел.дети.length} узлов`}
                        onClick={() => раскрыть(узел.code, !открытые.has(узел.code))}>
                        <Икон имя={открытые.has(узел.code) ? 'свернуть' : 'развернуть'} />
                      </button>
                    ) : <span className="v2-tree2__leaf" />}
                    <span className="v2-mono">{узел.code}</span>
                  </td>
                  <td onDoubleClick={() => { setПравится({ узел: узел.code, поле: 'name' }); setЧерновик(узел.name) }}>
                    {правится?.узел === узел.code && правится.поле === 'name' ? (
                      <input value={черновик} autoFocus aria-label={`имя узла ${узел.code}`}
                        onChange={(e) => setЧерновик(e.target.value)}
                        onBlur={() => сохранить(узел, 'name', черновик)}
                        onKeyDown={(e) => { if (e.key === 'Enter') сохранить(узел, 'name', черновик) }} />
                    ) : (
                      <span title="двойной клик — правка имени на месте">
                        {узел.name}
                        <span className="v2-dim"> · {слово('kind', узел.kind)}</span>
                        {снят && <span className="v2-dim"> · снят: {узел.deviation || 'без обоснования'}</span>}
                      </span>
                    )}
                  </td>
                  <td onDoubleClick={() => { setПравится({ узел: узел.code, поле: 'quantity' }); setЧерновик(String(состав.кратность[узел.code]?.сколько ?? 1)) }}>
                    {правится?.узел === узел.code && правится.поле === 'quantity' ? (
                      <input value={черновик} autoFocus aria-label={`кратность узла ${узел.code}`}
                        onChange={(e) => setЧерновик(e.target.value)}
                        onBlur={() => сохранитьКратность(узел, черновик)}
                        onKeyDown={(e) => { if (e.key === 'Enter') сохранитьКратность(узел, черновик) }} />
                    ) : (
                      <span title={состав.кратность[узел.code]?.запись
                        ? 'двойной клик — правка кратности: она живёт вхождением ×N'
                        : 'кратность пришла с полки каркаса: своего вхождения у узла нет'}>
                        ×{состав.кратность[узел.code]?.сколько ?? 1}
                      </span>
                    )}
                  </td>
                  <td>
                    {величина
                      ? величинаСловами(величина.measure, единица, знак) || <span className="v2-dim">—</span>
                      : <span className="v2-dim">{анкета.some((п) => п.key === ключ) ? 'не заполнено' : '—'}</span>}
                  </td>
                  <td>
                    <button type="button" className="v2-link"
                      title={`ступень зрелости узла к точке ${состав.точка.title || состав.точка.key}: считает карточка узла`}
                      onClick={() => api.componentCard(project, узел.code, состав.точка.key)
                        .then(setКарточка).catch((e) => setОтказ(String(e.message ?? e)))}>
                      смотреть
                    </button>
                  </td>
                  <td title="сколько величин анкеты заполнено: спрашивает полка каркаса">
                    {анкета.length === 0
                      ? <span className="v2-dim">полка не спрашивает</span>
                      : <span className={заполнено < анкета.length ? 'v2-warn' : 'v2-ok'}>{заполнено} из {анкета.length}</span>}
                  </td>
                  <td className="v2-tree2__acts">
                    <button type="button" className="v2-link" title={`добавить дочерний узел под ${узел.code}`}
                      onClick={() => setДобавляем(добавляем === узел.code ? null : узел.code)}>
                      <Икон имя="добавить" />
                    </button>
                    <button type="button" className="v2-link" disabled={снят}
                      title={снят ? 'узел уже снят с применения' : `снять ${узел.code} с применения — с обоснованием`}
                      onClick={() => снять(узел)}>
                      <Икон имя="снять" />
                    </button>
                    <button type="button" className="v2-link" title={`карточка узла ${узел.code}: грани, разрывы, величины`}
                      onClick={() => api.componentCard(project, узел.code, состав.точка.key)
                        .then(setКарточка).catch((e) => setОтказ(String(e.message ?? e)))}>
                      <Икон имя="карточка" />
                    </button>
                  </td>
                </tr>
              )
            })}
            {добавляем && (
              <tr className="v2-card-row">
                <td colSpan={7}>
                  <div className="v2-form v2-form--row">
                    <span className="v2-empty__why">дочерний узел под {добавляем}</span>
                    <label className="v2-field">
                      <span className="v2-field__cap">{состав.метки.code ?? 'Код'}</span>
                      <input value={новый.code} aria-label="код нового узла"
                        onChange={(e) => setНовый({ ...новый, code: e.target.value })} />
                    </label>
                    <label className="v2-field">
                      <span className="v2-field__cap">{состав.метки.name ?? 'Наименование'}</span>
                      <input value={новый.name} aria-label="имя нового узла"
                        onChange={(e) => setНовый({ ...новый, name: e.target.value })} />
                    </label>
                    <label className="v2-field">
                      <span className="v2-field__cap">{состав.метки.kind ?? 'Вид'}</span>
                      <select value={новый.kind} aria-label="вид нового узла"
                        onChange={(e) => setНовый({ ...новый, kind: e.target.value })}>
                        {(состав.перечни.kind ?? []).map((к) => <option key={к} value={к}>{слово('kind', к)}</option>)}
                      </select>
                    </label>
                    <button type="button" className="v2-primary" disabled={занято || !новый.code.trim() || !новый.name.trim()}
                      title={!новый.code.trim() || !новый.name.trim() ? 'код и имя узла обязательны' : `завести узел под ${добавляем}`}
                      onClick={() => завести(добавляем)}>
                      Завести
                    </button>
                    <button type="button" className="v2-link" title="закрыть строку ввода"
                      onClick={() => setДобавляем(null)}>отмена</button>
                  </div>
                </td>
              </tr>
            )}
          </tbody>
        </table>
      )}

      {карточка && (
        <div className="v2-card-row" data-why="следующий-клик">
          <div className="v2-card__head">
            <span className="v2-card__title">{карточка.code} · {карточка.name}</span>
            <span className="v2-card__count">ступень к {карточка.gate}</span>
            <span className="v2-head__spacer" />
            <button type="button" className="v2-link" title="закрыть карточку узла" onClick={() => setКарточка(null)}>закрыть</button>
          </div>
          <div className="v2-facets">
            {карточка.facets.map((г) => (
              <div key={г.key} className="v2-facet">
                <div className="v2-facet__title">{г.title}</div>
                <div className="v2-facet__body">
                  {г.lines.length === 0
                    ? <div className="v2-dim">{г.expected ?? 'пока пусто'}{г.required_to ? ` · к ${г.required_to}` : ''}</div>
                    : <ul className="v2-why">{г.lines.map((с, i) => <li key={i}>{с.what}</li>)}</ul>}
                </div>
              </div>
            ))}
          </div>
          {карточка.gaps.length > 0 && (
            <div className="v2-empty__why">
              не закрыто к точке: {карточка.gaps.map((р) => `${р.what} (${р.gate})`).join('; ')}
            </div>
          )}
        </div>
      )}

      {состав.каркас && состав.всего === 0 && (
        <div className="v2-form__actions">
          <button type="button" className="v2-primary" disabled={занято}
            title={состав.каркас.note}
            onClick={() => {
              setЗанято(true)
              api.takeFrame(project).then(перечитать).catch((e) => setОтказ(String(e.message ?? e))).finally(() => setЗанято(false))
            }}>
            Взять каркас с полки
          </button>
        </div>
      )}

      <ConfirmBox request={ask} onClose={closeConfirm} />
    </div>
  )
}
