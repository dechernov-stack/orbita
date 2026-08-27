// Подписи чисел созданного (круг 3 §1): «34 требования · 13 шаблонов
// документов». Морфология подписи — вёрстка, не расчёт.
const TYPE_PLURALS: Record<string, [string, string, string]> = {
  requirement: ['требование', 'требования', 'требований'],
  document_template: ['шаблон документов', 'шаблона документов', 'шаблонов документов'],
  component: ['элемент', 'элемента', 'элементов'],
  component_usage: ['вхождение', 'вхождения', 'вхождений'],
  interface: ['интерфейс', 'интерфейса', 'интерфейсов'],
  typical_risk: ['типовой риск', 'типовых риска', 'типовых рисков'],
  stakeholder_profile: ['профиль', 'профиля', 'профилей'],
  normative_document: ['норматив', 'норматива', 'нормативов'],
  wbs_element: ['работа WBS', 'работы WBS', 'работ WBS'],
  cost_estimate: ['оценка стоимости', 'оценки стоимости', 'оценок стоимости'],
}

export function countPhrase(type: string, n: number): string {
  const forms = TYPE_PLURALS[type] ?? [type, type, type]
  const d10 = n % 10
  const d100 = n % 100
  const form = d10 === 1 && d100 !== 11 ? forms[0]
    : d10 >= 2 && d10 <= 4 && (d100 < 12 || d100 > 14) ? forms[1] : forms[2]
  return `${n} ${form}`
}
