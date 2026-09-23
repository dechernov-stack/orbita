/**
 * Интересы стороны — список её слов с цитатой (истина 24.09, ОНТОЛОГИЯ-АУДИТ
 * часть 4): `stakeholder.interest: [{statement, quote?, fact?}]`. Прежние
 * записи и эталон несут строку или перечень строк — читаются те же.
 */
export type Интерес = { statement: string; quote?: string }

export function интересы(значение: unknown): Интерес[] {
  if (значение == null) return []
  if (typeof значение === 'string') {
    return значение.split(/[\n;]/).map((т) => т.trim()).filter(Boolean).map((т) => ({ statement: т }))
  }
  if (Array.isArray(значение)) {
    return значение.flatMap((э) => {
      if (typeof э === 'string') return интересы(э)
      if (!э || typeof э !== 'object' || typeof (э as { statement?: unknown }).statement !== 'string') return []
      const формулировка = (э as { statement: string }).statement.trim()
      if (!формулировка) return []
      const цитата = (э as { quote?: unknown }).quote
      return [typeof цитата === 'string' && цитата.trim() ? { statement: формулировка, quote: цитата } : { statement: формулировка }]
    })
  }
  return []
}
