#!/usr/bin/env python3
"""Исполняемый эталон баллистики (шаг 3).

Замкнутые формулы, независимые от Orekit. Реализация на Orekit обязана сходиться
с этими значениями в пределах указанных допусков — это внешняя проверка ядра,
а не его дублирование.

  TZ-BAL-001  пропагация: период, ССО-наклонение
  TZ-BAL-002  предрасчёт: геометрия видимости, двухуровневая сетка (ADR-013)
  TZ-BAL-003  конфигуратор Walker
  TZ-BAL-004  энергетика витка: тень, худший и лучший сезон
  TZ-BAL-005  спрос-взвешенное качество по зонам обслуживания
  TZ-BAL-009  время баллистического существования, увод
"""
import math, sys

MU    = 398600.4418      # км³/с², гравитационный параметр Земли
RE    = 6378.137         # км, экваториальный радиус
J2    = 1.08262668e-3
DEG   = math.pi / 180.0
YEAR  = 365.2422 * 86400 # с, тропический год

# ---------- TZ-BAL-001: базовая механика ----------
def orbital_period_s(alt_km):
    a = RE + alt_km
    return 2 * math.pi * math.sqrt(a**3 / MU)

def mean_motion_rad_s(alt_km):
    a = RE + alt_km
    return math.sqrt(MU / a**3)

def sso_inclination_deg(alt_km):
    """Наклонение солнечно-синхронной орбиты: прецессия ВДУ равна 360°/год."""
    a = RE + alt_km
    raan_rate = 2 * math.pi / YEAR                     # рад/с, требуемая прецессия
    n = mean_motion_rad_s(alt_km)
    cos_i = -raan_rate * a**2 / (1.5 * J2 * RE**2 * n)
    return math.degrees(math.acos(cos_i))

# ---------- TZ-BAL-002: геометрия видимости ----------
def central_angle_deg(alt_km, min_elev_deg):
    """Центральный угол зоны видимости при заданном минимальном угле места."""
    e = min_elev_deg * DEG
    r = RE + alt_km
    return math.degrees(math.acos(RE / r * math.cos(e)) - e)

def footprint_radius_km(alt_km, min_elev_deg):
    return RE * central_angle_deg(alt_km, min_elev_deg) * DEG

def slant_range_km(alt_km, elev_deg):
    """Наклонная дальность до точки на поверхности под углом места elev."""
    e = elev_deg * DEG
    return -RE * math.sin(e) + math.sqrt((RE * math.sin(e))**2 + alt_km**2 + 2 * RE * alt_km)

def max_pass_duration_s(alt_km, min_elev_deg):
    """Длительность пролёта через зенит — верхняя оценка для сеанса."""
    lam = central_angle_deg(alt_km, min_elev_deg) * DEG
    return orbital_period_s(alt_km) * lam / math.pi

# ---------- TZ-BAL-004: энергетика витка ----------
def eclipse_fraction(alt_km, beta_deg):
    """Доля витка в тени (цилиндрическая модель). beta — угол Солнца к плоскости орбиты."""
    r = RE + alt_km
    b = abs(beta_deg) * DEG
    denom = r * math.cos(b)
    num = math.sqrt(r**2 - RE**2)
    if num >= denom:          # орбита не входит в тень
        return 0.0
    return math.acos(num / denom) / math.pi

def orbit_energy_wh(alt_km, beta_deg, sa_area_m2, sa_eff, cos_loss=0.85, solar_wm2=1361.0):
    """Собранная за виток энергия, Вт·ч."""
    T = orbital_period_s(alt_km)
    sunlit = 1.0 - eclipse_fraction(alt_km, beta_deg)
    p = solar_wm2 * sa_area_m2 * sa_eff * cos_loss
    return p * T * sunlit / 3600.0

def allowed_duty_cycle(gen_wh, bus_w, payload_w, alt_km):
    """Допустимая скважность ПН — величина производная, а не задаваемая."""
    T_h = orbital_period_s(alt_km) / 3600.0
    spare = gen_wh - bus_w * T_h
    if spare <= 0 or payload_w <= 0:
        return 0.0
    return min(1.0, spare / (payload_w * T_h))

# ---------- TZ-BAL-003: конфигуратор Walker ----------
def walker_delta(inc_deg, T, P, F, alt_km):
    """Walker Delta i:T/P/F → перечень орбит (RAAN, средняя аномалия)."""
    if T % P:
        raise ValueError(f"T={T} не делится на P={P}")
    S = T // P
    sats = []
    for p in range(P):
        raan = 360.0 * p / P
        for s in range(S):
            ma = 360.0 * s / S + 360.0 * F * p / T
            sats.append({'plane': p, 'raan': raan, 'ma': ma % 360.0,
                         'inc': inc_deg, 'alt': alt_km})
    return sats

def launch_campaigns(sats):
    """Прокси-экономика: число уникальных пар (наклонение × высота)."""
    return len({(round(s['inc'], 3), round(s['alt'], 3)) for s in sats})

# ---------- TZ-BAL-009: время существования ----------
def decay_years(alt_km, mass_kg, area_m2, cd=2.2):
    """Грубая оценка времени баллистического существования (экспоненциальная атмосфера)."""
    H, rho0, h0 = 60.0, 1.0e-12, 400.0                 # км, кг/м³, км
    rho = rho0 * math.exp(-(alt_km - h0) / H)
    a = (RE + alt_km) * 1000.0
    bc = mass_kg / (cd * area_m2)                      # баллистический коэффициент
    da_dt = -2 * math.pi * rho * a**2 / bc             # м за виток
    if da_dt >= 0:
        return float('inf')
    revs = (alt_km - 100.0) * 1000.0 / abs(da_dt)
    return revs * orbital_period_s(alt_km) / YEAR

# ---------- TZ-BAL-005: спрос-взвешенное качество ----------
def demand_weighted_score(cells, in_service_fn):
    """Интеграл по весам спроса. Использует зону ОБСЛУЖИВАНИЯ, не footprint."""
    return sum(c['weight'] * in_service_fn(c) for c in cells)

# ================= проверки =================
ok = fail = 0
def check(name, cond, detail=''):
    global ok, fail
    if cond: ok += 1; print(f"  + {name}")
    else:    fail += 1; print(f"  - {name} {detail}")

print("TZ-BAL-001: механика")
T550 = orbital_period_s(550)
check("период на 550 км ≈ 95.6 мин", abs(T550/60 - 95.6) < 0.3, f"{T550/60:.2f} мин")
check("период растёт с высотой", orbital_period_s(1200) > orbital_period_s(550))
i700 = sso_inclination_deg(700)
check("ССО на 700 км ≈ 98.2°", abs(i700 - 98.2) < 0.2, f"{i700:.3f}°")
i550 = sso_inclination_deg(550)
check("ССО на 550 км ≈ 97.6°", abs(i550 - 97.6) < 0.2, f"{i550:.3f}°")
check("ССО-наклонение растёт с высотой", sso_inclination_deg(1000) > i550)

print("\nTZ-BAL-002: геометрия видимости")
# сверка с реальностью: Starlink на 550 км при угле места 25° даёт радиус зоны ≈ 940 км
check("сверка геометрии с известной группировкой (550 км / 25° ≈ 940 км)",
      abs(footprint_radius_km(550, 25) - 940) < 25, f"{footprint_radius_km(550,25):.0f} км")
lam10 = central_angle_deg(550, 10)
check("центральный угол на 550 км / 10° ≈ 15.0°", abs(lam10 - 14.96) < 0.3, f"{lam10:.2f}°")
check("зона сужается с ростом угла места", central_angle_deg(550, 25) < lam10)
check("зона расширяется с высотой", central_angle_deg(1200, 10) > lam10)
check("надирная дальность равна высоте", abs(slant_range_km(550, 90) - 550) < 0.5)
check("дальность у горизонта много больше высоты", slant_range_km(550, 5) > 2 * 550)
fp = footprint_radius_km(550, 10)
check("радиус footprint ≈ 1665 км", 1600 < fp < 1750, f"{fp:.0f} км")
dur = max_pass_duration_s(550, 10)
check("максимальный пролёт 550 км / 10° ≈ 8 мин", 420 < dur < 540, f"{dur/60:.1f} мин")

print("\nADR-013: двухуровневая сетка не теряет пролёты")
COARSE_KM = 800.0
check("грубая ячейка меньше диаметра footprint",
      COARSE_KM < 2 * footprint_radius_km(550, 10), f"{2*fp:.0f} км")
check("условие держится и на минимальной высоте 400 км",
      COARSE_KM < 2 * footprint_radius_km(400, 10), f"{2*footprint_radius_km(400,10):.0f} км")
check("при угле места 45° на 400 км условие нарушается — сетка теряет пролёты",
      COARSE_KM > 2 * footprint_radius_km(400, 45), f"{2*footprint_radius_km(400,45):.0f} км")
check("граница применимости лежит между 40° и 45°",
      2*footprint_radius_km(400,40) > COARSE_KM > 2*footprint_radius_km(400,45))

print("\nTZ-BAL-004: энергетика витка")
f_eq = eclipse_fraction(550, 0)
check("доля тени при beta=0 ≈ 0.38", abs(f_eq - 0.38) < 0.03, f"{f_eq:.3f}")
check("тень убывает с ростом beta", eclipse_fraction(550, 60) < f_eq)
check("терминаторная ССО почти без тени", eclipse_fraction(550, 90) == 0.0)
SA_M2 = 0.2            # реалистичная площадь СБ малого аппарата класса 12U–16U
e_worst = orbit_energy_wh(550, 0, SA_M2, 0.30)
e_best  = orbit_energy_wh(550, 75, SA_M2, 0.30)
check("энергия лучшего сезона выше худшего", e_best > e_worst, f"{e_worst:.1f} / {e_best:.1f} Вт·ч")
check("энергия худшего витка положительна", e_worst > 0)
d = allowed_duty_cycle(e_worst, bus_w=15, payload_w=60, alt_km=550)
check("допустимая скважность в (0,1]", 0 < d <= 1.0, f"{d:.3f}")
check("рост потребления шины снижает скважность",
      allowed_duty_cycle(e_worst, 25, 60, 550) < d,
      f"{allowed_duty_cycle(e_worst,25,60,550):.3f} vs {d:.3f}")
check("нехватка энергии даёт нулевую скважность",
      allowed_duty_cycle(e_worst, 500, 60, 550) == 0.0)

print("\nTZ-BAL-003: конфигуратор Walker")
sats = walker_delta(53.0, 40, 5, 1, 550)
check("40/5 даёт 40 аппаратов", len(sats) == 40)
check("5 плоскостей", len({s['plane'] for s in sats}) == 5)
check("8 аппаратов в плоскости", sum(1 for s in sats if s['plane'] == 0) == 8)
check("ВДУ равномерны по 72°", sorted({round(s['raan']) for s in sats}) == [0, 72, 144, 216, 288])
check("фазовый сдвиг F смещает соседнюю плоскость",
      abs(sats[8]['ma'] - sats[0]['ma']) > 1e-9)
check("одна пара (наклонение, высота) = одна кампания", launch_campaigns(sats) == 1)
mixed = walker_delta(53, 20, 4, 1, 550) + walker_delta(97.6, 12, 3, 1, 700)
check("разнородная группировка требует двух кампаний", launch_campaigns(mixed) == 2)
try:
    walker_delta(53, 41, 5, 1, 550); check("T не делится на P — ошибка", False)
except ValueError: check("T не делится на P — ошибка", True)

print("\nTZ-BAL-009: время существования")
low, high = decay_years(300, 50, 0.5), decay_years(700, 50, 0.5)
check("на 300 км сходит быстро", low < 25, f"{low:.1f} лет")
check("на 700 км держится дольше", high > low, f"{high:.0f} лет")
check("большая площадь ускоряет сход", decay_years(500, 50, 2.0) < decay_years(500, 50, 0.5))
check("норма 25 лет: 700 км без ДУ не проходит", high > 25)

print("\nTZ-BAL-005: качество по зонам обслуживания")
cells = [{'lat': 45, 'weight': 0.6}, {'lat': 75, 'weight': 0.05}, {'lat': 15, 'weight': 0.35}]
zone_wide   = lambda c: 1.0                       # зона A': покрывает всё
zone_narrow = lambda c: 1.0 if abs(c['lat']) < 50 else 0.0   # зона C': уже
check("качество по широкой зоне выше", demand_weighted_score(cells, zone_wide)
      > demand_weighted_score(cells, zone_narrow))
check("узкая зона теряет ровно вес непокрытых ячеек",
      abs(demand_weighted_score(cells, zone_narrow) - 0.95) < 1e-9,
      demand_weighted_score(cells, zone_narrow))

print(f"\nИтог: пройдено {ok}, провалено {fail}")
sys.exit(1 if fail else 0)
