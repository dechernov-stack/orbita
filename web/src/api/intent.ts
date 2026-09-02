// Намерение перехода между экранами (список после MCR, п. 7а): разрыв
// документа и строка готовности называют ОБЪЕКТ — клик обязан привести к
// нему, а не к пустому реестру. Экраны не знают друг друга; намерение —
// узкий почтовый ящик: отправитель кладёт, получатель забирает при
// монтировании. Не URL-параметры сознательно: оболочка держит экран
// состоянием, и второй механизм адресации разошёлся бы с первым.

let pendingObject: string | null = null
let pendingDocTemplate: string | null = null

/** Положить объект для открытия на целевом экране (до перехода). */
export function requestObject(id: string) {
  pendingObject = id
}

/** Забрать объект (одноразово): вернувший экран его и открывает. */
export function takeObject(): string | null {
  const id = pendingObject
  pendingObject = null
  return id
}

/** Положить шаблон документа для открытия на экране «Документы». */
export function requestDocTemplate(code: string) {
  pendingDocTemplate = code
}

export function takeDocTemplate(): string | null {
  const code = pendingDocTemplate
  pendingDocTemplate = null
  return code
}

/**
 * Экран, где живёт объект с данным идентификатором. Карта повторяет
 * маршрутизацию App.tsx: вид объекта — из префикса (TZ-MOD-002).
 */
const PREFIX_SCREEN: Record<string, string> = {
  MG: 'goals', ND: 'goals', SV: 'services', CO: 'conops',
  RQ: 'req',
  AL: 'aoa', CM: 'wbs', IF: 'wbs', WB: 'wbstree', CE: 'cost',
  SC: 'siminputs', CN: 'siminputs', DM: 'siminputs',
  // ADR-044: модель аппарата — вид узла КА; прежние SP живут только в истории
  SP: 'spacecraft', CU: 'composition',
  TP: 'siminputs', GS: 'siminputs', PA: 'siminputs',
  PJ: 'projreg', RF: 'rfa', RSK: 'risks', TL: 'trl', OD: 'oda', SD: 'sourcedocs',
  EV: 'vv', VA: 'vv', DI: 'docs', AP: 'aiprofiles',
}

export function screenOfObject(id: string): string | null {
  return PREFIX_SCREEN[id.split('-')[0]] ?? null
}

/** Идентификатор объекта в тексте — то, что можно сделать ссылкой. */
export const OBJECT_ID = /^[A-Z]{2,3}-[0-9]{4}$/
