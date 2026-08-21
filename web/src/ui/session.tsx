// Кто работает и как называются коды (шаг 15).
//
// Автор нужен КАЖДОМУ изменению: сессия параллельного проектирования — это
// несколько человек одновременно, и правка без автора это правка, о которой
// некого спросить. Учётных записей в системе нет, поэтому имя вводит сам
// инженер и оно живёт в браузере; подмена автора здесь не пресекается —
// это журнал работы, а не средство разграничения доступа.
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { edit } from '../api/edit'

const AUTHOR_KEY = 'orbita.author'

interface Session {
  author: string
  setAuthor: (name: string) => void
  /** Подпись кода перечисления; неизвестный код возвращается как есть. */
  label: (group: string, code: string | null | undefined) => string
}

const SessionContext = createContext<Session>({
  author: '',
  setAuthor: () => {},
  label: (_group, code) => code ?? '',
})

export function SessionProvider({ children }: { children: ReactNode }) {
  const [author, setAuthorState] = useState<string>(() => {
    try {
      return localStorage.getItem(AUTHOR_KEY) ?? ''
    } catch {
      // приватное окно или запрет хранилища: имя спросим заново, работа не встанет
      return ''
    }
  })
  const [labels, setLabels] = useState<Record<string, Record<string, string>>>({})

  useEffect(() => {
    // Подписи приходят с сервера одной таблицей: словари, рассыпанные
    // по экранам, уже приводили к тому, что на одном экране класс подписан,
    // а на соседнем выходит кодом.
    edit.enumLabels().then(setLabels).catch(() => setLabels({}))
  }, [])

  const setAuthor = useCallback((name: string) => {
    setAuthorState(name)
    try {
      localStorage.setItem(AUTHOR_KEY, name)
    } catch {
      // не сохранилось — имя действует до перезагрузки, и это лучше отказа
    }
  }, [])

  const label = useCallback(
    (group: string, code: string | null | undefined) => {
      if (!code) return '—'
      return labels[group]?.[code] ?? code
    },
    [labels],
  )

  const value = useMemo(() => ({ author, setAuthor, label }), [author, setAuthor, label])
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}

export function useSession(): Session {
  return useContext(SessionContext)
}

/** Поле имени в шапке: без него правка не отправляется, и это видно сразу. */
export function AuthorField() {
  const { author, setAuthor } = useSession()
  return (
    <label className="author" title="Автор изменений: записывается в каждую версию объекта">
      <span className="secondary">Инженер</span>
      <input
        value={author}
        placeholder="представьтесь"
        aria-label="Автор изменений"
        onChange={(e) => setAuthor(e.target.value)}
      />
    </label>
  )
}
