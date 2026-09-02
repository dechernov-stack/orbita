// Навигация оболочки (блок D, дизайн docs/ui/reference): рейка разделов и
// экраны панели операций. Ключи экранов совпадают с полем screen реестра
// операций (operations.json) — операция ведёт на своё рабочее место.

export interface NavScreen {
  key: string
  title: string
}

export interface NavSection {
  key: string
  label: string
  screens: NavScreen[]
}

export const SECTIONS: NavSection[] = [
  {
    key: 'work',
    label: 'Работа',
    screens: [
      { key: 'phasework', title: 'Работа фазы' },
    ],
  },
  {
    key: 'portfolio',
    label: 'Портфель',
    screens: [
      { key: 'portfolio', title: 'Все проекты' },
    ],
  },
  {
    key: 'library',
    label: 'Библиотека',
    screens: [
      { key: 'sourcedocs', title: 'Материалы проекта' },
      { key: 'docparse', title: 'Разбор документов' },
      { key: 'shelves', title: 'Полки' },
      { key: 'libknowledge', title: 'Знание полки' },
      { key: 'references', title: 'Справочники' },
      // Пачка SEMP (ШАБЛОН-SEMP п. 8): вход → работа → результат. Выпуски
      // документов — карточками с авторством; результат прошлого проекта —
      // кандидат на «обобщить в образец»
      { key: 'results', title: 'Результаты' },
    ],
  },
  {
    key: 'goals',
    label: 'Постановка',
    screens: [
      { key: 'goals', title: 'Цели и нужды' },
      { key: 'stakeholders', title: 'Стейкхолдеры' },
      { key: 'needs', title: 'Нужды и их сервисы' },
      { key: 'services', title: 'Сервисы и QoS' },
      { key: 'conops', title: 'Сценарии ConOps' },
    ],
  },
  {
    key: 'req',
    label: 'Требования',
    screens: [
      { key: 'req', title: 'Реестр требований' },
      { key: 'matrix', title: 'Матрицы' },
      { key: 'trace', title: 'Трассировка и impact' },
    ],
  },
  {
    key: 'aoa',
    label: 'Концепция',
    screens: [
      { key: 'aoa', title: 'Альтернативы и решения' },
      { key: 'wbs', title: 'Элементы и интерфейсы' },
      { key: 'composition', title: 'Дерево состава' },
      { key: 'wbstree', title: 'Структура работ (WBS)' },
      { key: 'cost', title: 'Оценки стоимости' },
    ],
  },
  {
    key: 'ballistics',
    label: 'Модели',
    screens: [
      { key: 'siminputs', title: 'Входы моделирования' },
      { key: 'spacecraft', title: 'Модель аппарата' },
      { key: 'demand', title: 'Карта спроса' },
      { key: 'ground', title: 'Наземный сегмент' },
      { key: 'ballistics', title: 'Глобус' },
      { key: 'coverage', title: 'Карта покрытия' },
      { key: 'constcompare', title: 'Сравнение построений' },
      { key: 'compare', title: 'Сравнение вариантов' },
    ],
  },
  {
    key: 'documents',
    label: 'Документы',
    screens: [
      { key: 'docs', title: 'Комплект фазы и выпуски' },
    ],
  },
  {
    key: 'readiness',
    label: 'Контроль',
    screens: [
      { key: 'lifecycle', title: 'Жизненный цикл' },
      { key: 'readiness', title: 'Готовность к точке' },
      { key: 'mytasks', title: 'Мои задания' },
      { key: 'operations', title: 'Порядок работы' },
      { key: 'projreg', title: 'Паспорт проекта' },
      { key: 'rfa', title: 'Замечания обзора' },
      { key: 'inspection', title: 'Инспекция обзора' },
      { key: 'risks', title: 'Риски' },
      { key: 'riskmatrix', title: 'Матрица рисков' },
      { key: 'trl', title: 'Технологии' },
      { key: 'oda', title: 'Орбитальное засорение' },
      { key: 'vv', title: 'Верификация и валидация' },
      { key: 'system', title: 'Система в целом' },
    ],
  },
  {
    key: 'ai',
    label: 'Инструменты',
    screens: [
      { key: 'seeddemand', title: 'Затравка спроса' },
      { key: 'aiservice', title: 'Служба ИИ' },
      { key: 'aiprofiles', title: 'Профили службы' },
      { key: 'ai', title: 'Предложения ИИ (контур пакетов)' },
      { key: 'importb', title: 'Загрузка пачкой' },
      { key: 'terminals', title: 'Импорт терминалов' },
    ],
  },
]

/** Раздел, которому принадлежит экран. */
export function sectionOf(screen: string): string {
  for (const s of SECTIONS) if (s.screens.some((x) => x.key === screen)) return s.key
  return 'portfolio'
}
