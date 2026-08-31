// Мелкие элементы отображения. Ни один из них не считает — все получают
// готовые значения с сервера и только выбирают, как их нарисовать.
import type { BudgetBar, ConditionView } from '../api/types'
import { Tooltip } from './Tooltip'
import { STATUS_MEANING } from './maturity'

/**
 * Статусная точка. Раньше несла в подсказке машинное имя статуса («Draft»),
 * то есть повторяла саму себя. Теперь — расшифровку смысла: что этот статус
 * значит для работы (§2.3 процесс-задачи).
 */
export function StatusDot({ status }: { status: string }) {
  const meaning = STATUS_MEANING[status]
  return (
    <Tooltip text={meaning ? `${status}: ${meaning}` : status}>
      <span className={`dot status-${status}`} title={meaning ?? status} />
    </Tooltip>
  )
}

/**
 * Условие как СТРУКТУРНОЕ значение, а не текст (STEP-6 §3.2). Строку собрал
 * сервер: оператор, число и подпись единицы — из renderConstraint, поэтому
 * «≤ 60 кг» здесь и в отчёте выглядят одинаково по построению.
 */
export function Condition({ condition }: { condition: ConditionView | null }) {
  if (!condition?.rendered) return <span className="secondary">—</span>
  return (
    <span className="mono" title={condition.name ?? undefined}>
      {condition.rendered}
    </span>
  )
}

/**
 * Полоса бюджета. Превышение краснеет и показывает величину — обрезать полосу
 * до «100%» значило бы спрятать ровно то, ради чего она рисуется (ловушка 4).
 * Доля заполнения приходит с сервера готовым процентом: делить used на limit
 * в клиенте — уже заготовка второй реализации правила (ловушка 5).
 */
export function BudgetGauge({ bar }: { bar: BudgetBar | null }) {
  if (!bar) return <span className="secondary">—</span>
  return (
    <span>
      <span className="bar">
        <span
          className={`bar__fill${bar.overrun ? ' bar__fill--overrun' : ''}`}
          style={{ width: `${bar.fillPercent}%` }}
        />
      </span>
      <span className={`mono${bar.overrun ? ' warn' : ''}`}>
        {bar.used} / {bar.limit} ·{' '}
        {bar.overrun ? `превышение ${bar.overrunValue}` : `остаток ${bar.remaining}`}
      </span>
    </span>
  )
}

/** Метод проверки и начало описания подхода; отсутствие подхода — замечание. */
export function Verification({
  method,
  approach,
  issues,
}: {
  method: string | null
  approach: string | null
  issues: string[]
}) {
  if (!method) return <span className="secondary">—</span>
  // одна строка 26 px (§3.6): подход — подсказкой, а не второй строкой
  return (
    <span style={{ whiteSpace: 'nowrap' }}>
      <span className="chip" title={approach ?? undefined}>{method}</span>
      {!approach && (
        <span className="amber" title="не описано, как выполняется проверка"> △</span>
      )}
      {issues.length > 0 && (
        <span className="amber" title={issues.join('\n')}>
          {' '}
          △ {issues.length}
        </span>
      )}
    </span>
  )
}
