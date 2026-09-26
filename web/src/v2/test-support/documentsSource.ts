// Текст экрана документов целиком — для сторожей, читающих разметку (`?raw`).
// Экран разложен по файлам (шип 5 §5): сторож смотрит на все его части разом.
import главный from '../documents.tsx?raw'
import комплект from '../documents/kit.tsx?raw'
import тело from '../documents/body.tsx?raw'
import раздел from '../documents/section.tsx?raw'
import базирование from '../documents/baselines.tsx?raw'
import проверка from '../documents/fieldcheck.tsx?raw'

export const ТЕКСТ_ДОКУМЕНТОВ = [главный, комплект, тело, раздел, базирование, проверка].join('\n')
