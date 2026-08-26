-- Библиотечные полки (СТРУКТУРА-БИБЛИОТЕКИ §2): пять новых видов объектов.
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'normative_document';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'mission_class';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'stakeholder_profile';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'typical_risk';
ALTER TYPE object_type ADD VALUE IF NOT EXISTS 'library_fragment';
