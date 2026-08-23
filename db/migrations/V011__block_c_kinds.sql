-- Блок C задания «прогон до KDP B»: материал точек — шесть видов, на которые
-- регламенты ссылались, а система не хранила: цели миссии и MOE (Прил. 3 §1
-- БП-PPA), альтернативы AoA (Прил. 3 §2), оценки стоимости и сроков (Д8/Д9),
-- оценка орбитального засорения (NASA-STD-8719.14), замечания обзора RFA/RID,
-- элементы WBS.

ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'mission_goal';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'alternative';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'cost_estimate';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'oda';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'review_item';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'wbs_element';

ALTER TABLE objects DROP CONSTRAINT IF EXISTS id_pattern;
ALTER TABLE objects ADD CONSTRAINT id_pattern
  CHECK (id ~ '^(ND|SV|RQ|CM|SC|EV|VA|IF|RSK|CN|SP|DM|TP|GS|PA|CO|TL|DN|PJ|DI|MG|AL|CE|OD|RF|WB)-[0-9]{4}$');
