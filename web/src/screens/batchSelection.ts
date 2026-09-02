// Г-02 (ПМИ-3): счётчик кнопки «Принять пачкой (N)» и состав отправляемой
// пачки считаются ОДНОЙ функцией от фактического выбора. Кнопка с нулём при
// непустом выборе невозможна по построению: обе стороны читают один список.
// Ключ строки — тот же, под которым строка стоит в списке и по которому
// отказ пачки снимает отметку (source_id перебитого id).

export interface Shown { item: { id?: unknown } }

export function rowKey(s: Shown): string {
  return String(s.item.id ?? '')
}

/** Строки, оставшиеся отмеченными: не снятые ни рукой, ни отказом пачки. */
export function chosenRows<T extends Shown>(shown: readonly T[], excluded: ReadonlySet<string>): T[] {
  return shown.filter((s) => !excluded.has(rowKey(s)))
}
