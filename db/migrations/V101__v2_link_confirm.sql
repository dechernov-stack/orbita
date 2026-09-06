-- Связь как объект (МОДЕЛЬ-ТРЕБОВАНИЯ-И-КОМПОНЕНТЫ §2.4): у неё есть вид
-- уточнения и есть подтверждение человеком.
--
-- Зачем подтверждение в базе, а не в снимке базирования: снимок НЕИЗМЕНЯЕМ
-- (инвариант 5), значит «подозрение снято» писать в него нельзя. Подозрение
-- вычисляется по версиям, а снимает его человек — отметкой на самой связи.
ALTER TABLE orbita_kernel.link ADD COLUMN IF NOT EXISTS subtype      TEXT;
ALTER TABLE orbita_kernel.link ADD COLUMN IF NOT EXISTS confirmed_by TEXT;
ALTER TABLE orbita_kernel.link ADD COLUMN IF NOT EXISTS confirmed_at TIMESTAMPTZ;
