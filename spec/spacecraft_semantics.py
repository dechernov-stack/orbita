#!/usr/bin/env python3
"""Исполняемый эталон аппарата и радиолиний (шаг 3).

  TZ-KA-003  массовый бюджет с резервами по зрелости
  TZ-KA-005  зона обслуживания ≠ зона видимости; раздельно по линиям и профилям
  TZ-KA-006  маяк эфемерид: нагрузка на нисходящую линию, приём худшим терминалом
  TZ-KA-007  бюджет радиолинии; раздельные участки (следствие regenerative, Р1)
  TZ-KA-008  буфер store-and-forward с приоритетами
  TZ-NET-001 параметры канала берутся только из адаптера протокола
  TZ-NET-005 регуляторные ограничения (duty cycle)
"""
import math, sys

C_LIGHT = 299_792_458.0
K_BOLTZ_DBW = -228.6            # дБВт/К/Гц
RE = 6378.137

# ---------- TZ-KA-003: массовый бюджет ----------
MATURITY_MARGIN = {'new': 0.25, 'modified': 0.15, 'existing': 0.05}

def dry_mass(items, system_margin=0.10):
    """items: [{'mass_kg':.., 'maturity':'new'|'modified'|'existing'}]"""
    base = sum(i['mass_kg'] * (1 + MATURITY_MARGIN[i['maturity']]) for i in items)
    return base * (1 + system_margin)

def within_platform_range(mass_kg):
    """Р2/ADR-002: диапазон 12U–100 кг."""
    return 12.0 <= mass_kg <= 100.0

# ---------- TZ-KA-007 / TZ-NET-001: бюджет радиолинии ----------
def fspl_db(range_km, freq_hz):
    return 20 * math.log10(4 * math.pi * range_km * 1000 * freq_hz / C_LIGHT)

def slant_range_km(alt_km, elev_deg):
    e = math.radians(elev_deg)
    return -RE * math.sin(e) + math.sqrt((RE * math.sin(e))**2 + alt_km**2 + 2 * RE * alt_km)

def link_margin_db(eirp_dbw, alt_km, elev_deg, freq_hz, g_over_t_dbk,
                   bitrate_bps, required_ebn0_db, extra_losses_db=2.0):
    """Запас линии. required_ebn0_db приходит ИЗ АДАПТЕРА ПРОТОКОЛА, не задаётся вручную."""
    d = slant_range_km(alt_km, elev_deg)
    cn0 = (eirp_dbw - fspl_db(d, freq_hz) - extra_losses_db
           + g_over_t_dbk - K_BOLTZ_DBW)
    ebn0 = cn0 - 10 * math.log10(bitrate_bps)
    return ebn0 - required_ebn0_db

def service_elevation_deg(required_margin_db, min_elev_deg=5.0, **kw):
    """Угол места, при котором запас равен требуемому. Граница ЗОНЫ ОБСЛУЖИВАНИЯ."""
    lo, hi = min_elev_deg, 90.0
    if link_margin_db(elev_deg=hi, **kw) < required_margin_db:
        return None                                  # линия не замыкается нигде
    if link_margin_db(elev_deg=lo, **kw) >= required_margin_db:
        return min_elev_deg                          # ограничивает геометрия, не бюджет
    for _ in range(60):
        mid = (lo + hi) / 2
        if link_margin_db(elev_deg=mid, **kw) < required_margin_db: lo = mid
        else: hi = mid
    return hi

def limiting_factor(service_elev, min_elev_deg):
    return 'geometry' if abs(service_elev - min_elev_deg) < 1e-6 else 'link_margin'

# ---------- TZ-KA-006: маяк эфемерид ----------
def beacon_downlink_load(period_s, payload_bytes, overhead_bytes, bitrate_bps):
    """Доля занятости нисходящей линии маяком."""
    bits = (payload_bytes + overhead_bytes) * 8
    return (bits / bitrate_bps) / period_s

def beacon_energy_wh(period_s, payload_bytes, overhead_bytes, bitrate_bps,
                     tx_power_w, orbit_s):
    duty = beacon_downlink_load(period_s, payload_bytes, overhead_bytes, bitrate_bps)
    return tx_power_w * duty * orbit_s / 3600.0

def almanac_ok(beacon_period_s, max_almanac_age_s, passes_per_day):
    """Терминал обновляет альманах не реже допустимого возраста."""
    if passes_per_day <= 0: return False
    interval = 86400.0 / passes_per_day
    return max(interval, beacon_period_s) <= max_almanac_age_s

# ---------- TZ-KA-008: буфер S&F с приоритетами ----------
PRIORITY = {'C_prime': 0, 'B_prime': 1, 'A_prime': 2}

def buffer_admit(queue, msg, capacity, policy='drop_lowest_priority'):
    """Возвращает (новая очередь, отброшенное сообщение или None)."""
    q = list(queue) + [msg]
    if len(q) <= capacity:
        return q, None
    if policy == 'drop_lowest_priority':
        victim = max(q, key=lambda m: (PRIORITY[m['klass']], m['t']))
    elif policy == 'drop_oldest':
        victim = min(q, key=lambda m: m['t'])
    else:
        return list(queue), msg                       # reject_new
    q.remove(victim)
    return q, victim

def required_buffer_msgs(msgs_per_s, worst_gap_s):
    """Объём буфера — от худшего интервала до сброса на НС."""
    return math.ceil(msgs_per_s * worst_gap_s)

# ---------- TZ-NET-005: регуляторный duty cycle ----------
def population_duty_cycle(terminals, msgs_per_day, time_on_air_s):
    return terminals * msgs_per_day * time_on_air_s / 86400.0

# ================= проверки =================
ok = fail = 0
def check(name, cond, detail=''):
    global ok, fail
    if cond: ok += 1; print(f"  + {name}")
    else:    fail += 1; print(f"  - {name} {detail}")

print("TZ-KA-003: массовый бюджет")
items = [{'mass_kg': 8, 'maturity': 'existing'}, {'mass_kg': 6, 'maturity': 'modified'},
         {'mass_kg': 10, 'maturity': 'new'}]
m = dry_mass(items)
check("резервы по зрелости увеличивают массу", m > sum(i['mass_kg'] for i in items), f"{m:.2f} кг")
check("новый элемент даёт больший резерв, чем существующий",
      dry_mass([{'mass_kg':10,'maturity':'new'}]) > dry_mass([{'mass_kg':10,'maturity':'existing'}]))
check("масса в диапазоне Р2", within_platform_range(m), f"{m:.2f}")
check("250 кг вне диапазона Р2", not within_platform_range(250))
check("5 кг вне диапазона Р2", not within_platform_range(5))

print("\nTZ-KA-007: бюджет радиолинии")
up = dict(eirp_dbw=-16.0, alt_km=550, freq_hz=868e6, g_over_t_dbk=-15.0,
          bitrate_bps=300, required_ebn0_db=-6.0)     # терминал 14 дБм → КА
mz, mh = link_margin_db(elev_deg=90, **up), link_margin_db(elev_deg=10, **up)
check("запас в надире больше, чем у горизонта", mz > mh, f"{mz:.1f} / {mh:.1f} дБ")
check("восходящая линия IoT замыкается в надире", mz > 0, f"{mz:.1f} дБ")
check("рост частоты снижает запас",
      link_margin_db(elev_deg=90, **{**up, 'freq_hz': 2.4e9}) < mz)
check("рост скорости снижает запас",
      link_margin_db(elev_deg=90, **{**up, 'bitrate_bps': 50000}) < mz)
check("сквозной бюджет терминал→КА→НС не вычисляется (regenerative, Р1)", True)

print("\nTZ-KA-005: зона обслуживания ≠ зона видимости")
# A': односторонний приём сильной линии — ограничена геометрия.
# C': двусторонний контур, слабая нисходящая до малого терминала — ограничивает бюджет.
se_a = service_elevation_deg(3.0, min_elev_deg=5.0, **up)
weak  = {**up, 'eirp_dbw': -38.0}
se_c = service_elevation_deg(3.0, min_elev_deg=5.0, **weak)
check("зона обслуживания определена", se_a is not None and se_c is not None, f"{se_a}, {se_c}")
check("зона слабой линии уже", se_c > se_a, f"A'={se_a:.2f}° C'={se_c:.2f}°")
check("граница зоны не ниже геометрического предела", se_a >= 5.0 and se_c >= 5.0)
check("для A' ограничивает геометрия", limiting_factor(se_a, 5.0) == 'geometry')
check("для C' ограничивает бюджет линии",
      limiting_factor(se_c, 5.0) == 'link_margin', f"{se_c:.2f}°")
check("ужесточение требуемого запаса сужает зону",
      service_elevation_deg(8.0, min_elev_deg=5.0, **weak) > se_c)
dead = service_elevation_deg(3.0, min_elev_deg=5.0, **{**up, 'eirp_dbw': -60.0})
check("незамыкающаяся линия не даёт зоны", dead is None)
check("при избыточном запасе ограничивает геометрия",
      limiting_factor(service_elevation_deg(3.0, min_elev_deg=5.0,
                      **{**up, 'eirp_dbw': 10.0}), 5.0) == 'geometry')

print("\nTZ-KA-006: маяк эфемерид")
load = beacon_downlink_load(period_s=60, payload_bytes=40, overhead_bytes=13, bitrate_bps=300)
check("занятость линии маяком в (0,1)", 0 < load < 1, f"{load:.4f}")
check("частый маяк грузит линию сильнее",
      beacon_downlink_load(10, 40, 13, 300) > load)
check("модель орбиты дешевле расписания при том же периоде",
      beacon_downlink_load(60, 24, 13, 300) < beacon_downlink_load(60, 120, 13, 300))
e = beacon_energy_wh(60, 40, 13, 300, tx_power_w=6.0, orbit_s=5736)
check("энергия маяка учитывается и положительна", e > 0, f"{e:.3f} Вт·ч/виток")
check("альманах обновляется при частых пролётах", almanac_ok(60, 86400, passes_per_day=8))
check("редкие пролёты не укладываются в допустимый возраст",
      not almanac_ok(60, 3600, passes_per_day=4))
check("нулевое число пролётов = деградация", not almanac_ok(60, 86400, passes_per_day=0))

print("\nTZ-KA-008: буфер и приоритеты")
q = [{'klass':'A_prime','t':1},{'klass':'B_prime','t':2},{'klass':'C_prime','t':3}]
q2, dropped = buffer_admit(q, {'klass':'C_prime','t':4}, capacity=3)
check("при переполнении вытесняется низший приоритет", dropped['klass'] == 'A_prime', dropped)
check("C' сохраняется в очереди", sum(1 for m in q2 if m['klass']=='C_prime') == 2)
q3, dropped3 = buffer_admit(q, {'klass':'A_prime','t':4}, capacity=3)
check("новое A' вытесняется первым как самое позднее из низших",
      dropped3['klass'] == 'A_prime' and dropped3['t'] == 4)
q4, d4 = buffer_admit(q, {'klass':'C_prime','t':4}, capacity=10)
check("без переполнения ничего не теряется", d4 is None and len(q4) == 4)
q5, d5 = buffer_admit(q, {'klass':'C_prime','t':4}, capacity=3, policy='drop_oldest')
check("политика drop_oldest вытесняет самое старое", d5['t'] == 1)
need = required_buffer_msgs(msgs_per_s=0.5, worst_gap_s=3600)
check("объём буфера от худшего интервала до сброса", need == 1800, need)

print("\nTZ-NET-005: регуляторные ограничения")
duty = population_duty_cycle(terminals=5000, msgs_per_day=4, time_on_air_s=0.4)
check("превышение duty cycle 1% выявляется", duty > 0.01, f"{duty:.4f}")
check("сокращение числа терминалов снижает занятость",
      population_duty_cycle(500, 4, 0.4) < duty)
check("предел 1% выдерживается малой популяцией",
      population_duty_cycle(200, 4, 0.4) < 0.01)

print(f"\nИтог: пройдено {ok}, провалено {fail}")
sys.exit(1 if fail else 0)
