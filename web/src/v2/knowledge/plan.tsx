// План из разбора документа (шип 5 §3.1): действия плана на акцепт и заметки
// ворот приёма. Живёт во вкладке «Документы» — там, где документ разобран.
import { useState } from 'react'
import { api, type TaskPlan } from '../api'

/**
 * План разбора на акцепт. Пакетный приём заперт там же, где его запирает
 * сервер: план материала, источник которого не подтверждён, не принимается
 * ни целиком, ни частью.
 */
export function ПланРазбора({ project, план, onAccepted, onClose, onError }: {
  project: string
  план: TaskPlan
  /** Принято: заметки ворот (что НЕ пропущено и почему) — экрану показать. */
  onAccepted: (заметки: string[]) => void
  onClose: () => void
  onError: (e: string) => void
}) {
  const [выбраны, setВыбраны] = useState<number[]>(() => план.actions.map((д) => д.index))
  const [занято, setЗанято] = useState(false)
  const пакетЗаперт = план.batch_accept === false
  const принятьПлан = () => {
    if (выбраны.length === 0 || пакетЗаперт) return
    setЗанято(true)
    api.acceptPlan(project, план.task, выбраны, 'инженер')
      .then((итог) => onAccepted(итог.notes ?? []))
      .catch((e) => onError(String(e.message ?? e)))
      .finally(() => setЗанято(false))
  }
  /**
   * Подсказка кнопки приёма: серая кнопка обязана назвать причину И путь
   * оживления (правило Ф-11). Причина запрета — серверная, слово в слово.
   */
  const подсказкаПриёма = план.batch_refusal
    ? `${план.batch_refusal}. Источники подтверждаются в карточке исследования ниже`
    : выбраны.length === 0
      ? 'отметьте действия, которые принимаете: снятое остаётся рассмотренным'
      : 'выполнить выбранные действия: сущности получат нить к своим фактам'

  return (
    <div className="v2-kf__src" data-why="следующий-клик">
          <div className="v2-empty__why">
            План из разбора: {план.actions.length} действий. {план.note}
            {' '}Снятое действие остаётся рассмотренным — факт не исчезает.
          </div>
          {/*
            Ранг материала — НАД планом и словами: до 14.09 экран показывал
            галочки и «Принять N из M», не сказав, откуда план собран.
            Сомнительный ранг гасит пакетный приём — теми же словами, какими
            отказывают ворота приёма на сервере.
          */}
          {пакетЗаперт ? (
            <div className="v2-locked" data-why="почему-нельзя">
              Ранг доверия материала: {план.rank_word}
              <span className="v2-empty__why">{план.batch_refusal}</span>
              <span className="v2-empty__why">
                Источники подтверждаются в карточке исследования ниже: отметьте факты,
                источники которых проверили, — материал поднимется до справочного, и план
                станет приниматься. Факты плана из поля не исчезают: они ждут подтверждения.
              </span>
            </div>
          ) : план.rank_word ? (
            <div className="v2-note-line">Ранг доверия материала: {план.rank_word}</div>
          ) : null}
          {план.assessment && (
            <div className="v2-scroll">
              {(план.assessment.gaps?.length ?? 0) > 0 && (
                <div className="v2-empty__why" data-why="почему-нельзя">
                  Дыры ТЗ против нужд — {план.assessment.gaps!.length}:
                  <ul className="v2-list">
                    {план.assessment.gaps!.map((д) => <li key={д}>{д}</li>)}
                  </ul>
                </div>
              )}
              {(план.assessment.needs?.length ?? 0) > 0 && (
                <table className="v2-tab2">
                  <thead><tr><th>Нужда</th><th>Вердикт</th><th>Чего в ТЗ нет</th><th>Требования ТЗ</th></tr></thead>
                  <tbody>
                    {план.assessment.needs!.map((н) => (
                      <tr key={н.need}>
                        <td className="v2-mono">{н.need}</td>
                        <td className={н.verdict === 'uncovered' ? 'v2-bad' : н.verdict === 'partial' ? 'v2-warn' : 'v2-ok'}>
                          {н.verdict === 'covered' ? 'покрыта' : н.verdict === 'partial' ? 'частично' : 'не покрыта'}
                        </td>
                        <td>{н.gap || '—'}</td>
                        <td className="v2-mono">{н.requirements.join(', ') || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
              <table className="v2-tab2">
                <thead><tr><th>Требование ТЗ</th><th>Покрывает нужды</th><th>Вердикт</th><th>Почему</th></tr></thead>
                <tbody>
                  {план.assessment.lines.map((л) => (
                    <tr key={л.fact}>
                      <td><span className="v2-mono">{л.fact}</span> {л.requirement}</td>
                      <td className="v2-mono">{л.needs.join(', ') || '—'}</td>
                      <td className={л.verdict === 'none' ? 'v2-bad' : л.verdict === 'partial' ? 'v2-warn' : 'v2-ok'}>
                        {л.verdict === 'covers' ? 'покрывает' : л.verdict === 'partial' ? 'частично' : 'без нужды'}
                      </td>
                      <td>{л.note || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="v2-note-line">
                непокрытых нужд: {план.assessment.uncovered_needs.length}
                {план.assessment.uncovered_needs.length > 0 && ` (${план.assessment.uncovered_needs.join(', ')}) — RFA заказчику в плане`}
                {' · '}требований без нужды: {план.assessment.orphan_requirements.length}
              </div>
            </div>
          )}
          <table className="v2-tab2">
            <thead><tr><th /><th>Действие</th><th>Что появится</th><th>Сцена</th></tr></thead>
            <tbody>
              {план.actions.map((д) => (
                <tr key={д.index}>
                  <td>
                    <input type="checkbox" checked={выбраны.includes(д.index)}
                      onChange={(e) => setВыбраны(e.target.checked
                        ? [...выбраны, д.index] : выбраны.filter((i) => i !== д.index))} />
                  </td>
                  <td>{д.title}</td>
                  <td>{д.preview}</td>
                  <td className="v2-mono">{д.scene}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="v2-form__actions">
            <button type="button" className="v2-primary"
              disabled={занято || выбраны.length === 0 || пакетЗаперт}
              title={подсказкаПриёма}
              onClick={принятьПлан}>
              {занято ? 'Принимаю…' : `Принять ${выбраны.length} из ${план.actions.length}`}
            </button>
            <button type="button" className="v2-link" onClick={onClose}>позже</button>
          </div>
        </div>
  )
}

/** Что ворота приёма НЕ пропустили: каждое непринятое действие — с причиной (остановка ПМИ-6). */
export function ЗаметкиПриёма({ заметки, onGoGlossary, onHide }: {
  заметки: string[]
  onGoGlossary?: () => void
  onHide: () => void
}) {
  return (
    <div className="v2-kf__src" data-why="почему-нельзя">
          <div className="v2-card__head">
            <span className="v2-card__title">Принято не всё</span>
            <span className="v2-card__count">{заметки.length}</span>
          </div>
          <div className="v2-empty__why">
            Ворота приёма назвали каждое непринятое действие причиной. Факты остались
            в поле: решайте их по одному либо примите план заново, поправив основания.
          </div>
          <ul className="v2-list">
            {заметки.map((з) => (
              <li key={з}>
                {з}
                {/словар/i.test(з) && onGoGlossary && (
                  <>{' '}<button type="button" className="v2-link" onClick={onGoGlossary}
                    title="открыть словарь: кандидат с цитатой ждёт решения — принять, отклонить или слить">к месту: Словарь</button></>
                )}
              </li>
            ))}
          </ul>
          <button type="button" className="v2-link" onClick={onHide}>скрыть</button>
        </div>
  )
}
