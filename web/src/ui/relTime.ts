// Относительное время — подпись вёрстки: «вчера», «3 дн. назад», дата.
// Живёт в ui, а не в экране: время читают одинаково портфель и схема потока,
// а двух формулировок одного и того же в системе не бывает.
const DAY = 86400000

export function relTime(iso: string): string {
  const at = new Date(iso).getTime()
  const diff = Date.now() - at
  if (diff < DAY) return 'сегодня'
  if (diff < 2 * DAY) return 'вчера'
  for (let n = 2; n <= 6; n++) if (diff < (n + 1) * DAY) return `${n} дн. назад`
  if (diff < 14 * DAY) return 'неделю назад'
  for (let n = 2; n <= 4; n++) if (diff < (n + 1) * 7 * DAY) return `${n} нед. назад`
  const d = new Date(at)
  const dd = `${d.getDate()}`.padStart(2, '0')
  const mm = `${d.getMonth() + 1}`.padStart(2, '0')
  return `${dd}.${mm}.${d.getFullYear()}`
}
