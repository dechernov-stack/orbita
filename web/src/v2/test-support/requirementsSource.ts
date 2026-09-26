// Текст экрана требований целиком — для сторожей, читающих разметку (`?raw`).
// Экран разложен по файлам (шип 5 §4): сторож смотрит на все его части разом,
// а не на один файл, иначе перенос кода «прятал» бы проверяемое.
import главный from '../requirements.tsx?raw'
import общее from '../requirements/common.ts?raw'
import сцена8 from '../requirements/scene8.tsx?raw'
import карточка from '../requirements/card.tsx?raw'
import влияние from '../requirements/impact.tsx?raw'
import деревья from '../requirements/trees.tsx?raw'
import базирование from '../requirements/baseline.tsx?raw'
import форма from '../requirements/form.tsx?raw'

export const ТЕКСТ_ТРЕБОВАНИЙ = [главный, общее, сцена8, карточка, влияние, деревья, базирование, форма].join('\n')
