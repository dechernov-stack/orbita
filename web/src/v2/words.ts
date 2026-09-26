/**
 * Слова уровней знания (ОНТОЛОГИЯ-АУДИТ часть 2, «одно слово на уровень»):
 * у факта — учтён · принят · отклонён · оспорен · устарел (диспозиция, слова
 * истины схем); у предложения — принять · отклонить · отложить (решение) и
 * действия сверки ниже; у сущности — базировать · отменить (статус).
 *
 * Перечень действий сверки — серверный (код действия приходит в `offers`);
 * слово к коду живёт ЗДЕСЬ одной таблицей: вторых формулировок для того же
 * действия на разных экранах не бывает.
 */
export const ДЕЙСТВИЕ_СВЕРКИ: Record<string, string> = {
  accept_new: 'принять новым',
  merge_into: 'слить',
  refine: 'уточнить',
  generalize: 'обобщить',
  link_basis: 'привязать основание',
  mark_contested: 'оспорить',
  fix_input: 'исправить ввод',
  dismiss: 'отклонить находку',
}

/**
 * Слова досье документа (шип 4 §3): вид факта, решение по факту, ранг
 * источника, вид записи. Значения приходят с сервера кодами, на экран
 * выходят словами — латинского имени вида в интерфейсе не бывает.
 */
export const СЛОВО_ВИДА_ФАКТА: Record<string, string> = {
  framing: 'рамка',
  quantity: 'величина',
  capability: 'способность',
  obligation: 'обязательство',
  event: 'событие',
  assessment: 'оценка',
  relation: 'связь',
  assumption: 'допущение',
  external_target: 'чужая цель',
  external_need: 'чужая нужда',
}

export const СЛОВО_РЕШЕНИЯ: Record<string, string> = {
  free: 'не рассмотрен',
  noted: 'учтён',
  assumed: 'допущение',
  adopted: 'принят',
  rejected: 'отклонён',
  contested: 'оспорен',
  superseded: 'устарел',
}

export const СЛОВО_РАНГА: Record<string, string> = {
  mandatory: 'обязательный',
  expert: 'экспертный',
  reference: 'справочный',
  doubtful: 'сомнительный',
}

export const СЛОВО_ЗАПИСИ: Record<string, string> = {
  stakeholder: 'сторона',
  need: 'нужда',
  goal: 'цель',
  service: 'сервис',
  constraint: 'ограничение',
  requirement: 'требование',
  normative_document: 'норматив',
  intent: 'замысел',
  risk: 'риск',
  finding: 'замечание',
  component: 'узел',
  parameter: 'параметр',
  milestone: 'веха',
  glossary_term: 'термин',
  opportunity: 'применимость',
  assumption: 'допущение',
}

export const СЛОВО_ПРОГОНА: Record<string, string> = {
  running: 'идёт',
  done: 'готов',
  rolled_back: 'откачен',
  superseded: 'заменён',
}

/**
 * Статус записи словами (шип 5 §1.3, шапка карточки): у сущностей постановки
 * модели статусов нет — они черновики, пока их не базирует документ.
 */
export const СЛОВО_СТАТУСА: Record<string, string> = {
  draft: 'черновик',
  proposed: 'предложено',
  accepted: 'принято',
  baselined: 'базировано',
  cancelled: 'снято',
  withdrawn: 'снято',
  obsolete: 'устарело',
  open: 'открыт',
  closed: 'закрыт',
}
