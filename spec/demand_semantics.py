#!/usr/bin/env python3
"""Исполняемый эталон поведения карты спроса (шаг 2).

  TZ-USR-004  трёхслойная сборка; равноплощадность сетки
  TZ-USR-005  суточные и сезонные профили активности
  TZ-USR-007  агрегация ячейка → пояс → вариант; спрос-взвешенное качество
Ключевая ловушка — проекция: равноугольная сетка завышает вес высоких широт.
"""
import math, sys, copy

R_EARTH_KM = 6371.0

def cell_area_km2(lat_deg, dlat_deg=1.0, dlon_deg=1.0):
    """Площадь равноугольной ячейки: убывает как cos(широты)."""
    lat = math.radians(lat_deg)
    dlat, dlon = math.radians(dlat_deg), math.radians(dlon_deg)
    return R_EARTH_KM**2 * dlon * (math.sin(lat + dlat/2) - math.sin(lat - dlat/2)) / dlat * dlat

# ---------- TZ-USR-004: трёхслойная сборка ----------
def build_demand_map(population_layer, point_objects=None, scenarios=None):
    """Слои: население → единичные объекты → сценарная библиотека.
    Возвращает ячейки с интенсивностями и НОРМИРОВАННЫМИ весами."""
    cells = {}
    for c in population_layer:
        area = cell_area_km2(c['lat'])
        terminals = c['pop_density_per_km2'] * area * c['terminals_per_capita']
        cells[c['id']] = {'id': c['id'], 'lat': c['lat'],
                          'terminals': {c['klass']: terminals},
                          'msgs_per_day': {c['klass']: terminals * c['msgs_per_terminal_day']},
                          'area_km2': area}
    for p in (point_objects or []):
        cell = cells.setdefault(p['cell_id'], {'id': p['cell_id'], 'lat': p['lat'],
                                               'terminals': {}, 'msgs_per_day': {},
                                               'area_km2': cell_area_km2(p['lat'])})
        cell['terminals'][p['klass']] = cell['terminals'].get(p['klass'], 0) + p['terminals']
        cell['msgs_per_day'][p['klass']] = (cell['msgs_per_day'].get(p['klass'], 0)
                                            + p['terminals'] * p['msgs_per_terminal_day'])
    for s in (scenarios or []):
        cell = cells.setdefault(s['cell_id'], {'id': s['cell_id'], 'lat': s['lat'],
                                               'terminals': {}, 'msgs_per_day': {},
                                               'area_km2': cell_area_km2(s['lat'])})
        cell['terminals'][s['klass']] = cell['terminals'].get(s['klass'], 0) + s['terminals']
        cell['msgs_per_day'][s['klass']] = (cell['msgs_per_day'].get(s['klass'], 0)
                                            + s['terminals'] * s['msgs_per_terminal_day'])
    total = sum(sum(c['msgs_per_day'].values()) for c in cells.values())
    for c in cells.values():
        c['weight'] = (sum(c['msgs_per_day'].values()) / total) if total else 0.0
    return cells

# ---------- TZ-USR-005: профили активности ----------
def intensity_at(cell, hour, month, diurnal=None, seasonal=None):
    base = sum(cell['msgs_per_day'].values()) / 24.0
    d = (diurnal or [1.0]*24)[hour]
    s = (seasonal or [1.0]*12)[month]
    return base * d * s

def worst_case_hour_month(cell, diurnal, seasonal):
    """Худшее сочетание: пик спроса. Совмещается с худшей энергетикой витка."""
    best = max(((h, m) for h in range(24) for m in range(12)),
               key=lambda x: intensity_at(cell, x[0], x[1], diurnal, seasonal))
    return best

# ---------- TZ-USR-007: агрегация и спрос-взвешенное качество ----------
def demand_weighted_quality(cells, quality_fn):
    """Интеграл качества по весам спроса. Ячейка с нулевым спросом не влияет."""
    return sum(c['weight'] * quality_fn(c) for c in cells.values())

def latitude_profile(cells, quality_fn, band=15):
    """Широтный профиль: качество и вес спроса по поясам."""
    bands = {}
    for c in cells.values():
        b = int(math.floor(c['lat'] / band) * band)
        e = bands.setdefault(b, {'band': b, 'w': 0.0, 'q_w': 0.0})
        e['w'] += c['weight']; e['q_w'] += c['weight'] * quality_fn(c)
    for e in bands.values():
        e['quality'] = e['q_w'] / e['w'] if e['w'] else 0.0
    return [bands[k] for k in sorted(bands)]

# ================= проверки =================
# Проверки исполняются только при прямом запуске: модуль импортируется
# другими эталонами (в частности demo_project.py), и sys.exit при импорте
# обрывал бы их на середине — CI видел бы код 0 при невыполненных проверках.
if __name__ == '__main__':
    ok = fail = 0
    def check(name, cond, detail=''):
        global ok, fail
        if cond: ok += 1; print(f"  + {name}")
        else:    fail += 1; print(f"  - {name} {detail}")

    print("TZ-USR-004: равноплощадность (ловушка проекции)")
    a0, a60, a80 = cell_area_km2(0), cell_area_km2(60), cell_area_km2(80)
    check("площадь ячейки убывает к полюсу", a0 > a60 > a80, f"{a0:.0f} {a60:.0f} {a80:.0f}")
    check("ячейка на 60° примерно вдвое меньше экваториальной", abs(a60/a0 - 0.5) < 0.02, f"{a60/a0:.3f}")
    # при ОДИНАКОВОЙ плотности населения на км² вес ячейки обязан быть пропорционален площади
    uniform = [{'id':'c0','lat':0,'pop_density_per_km2':10,'terminals_per_capita':0.1,
                'msgs_per_terminal_day':4,'klass':'A_prime'},
               {'id':'c60','lat':60,'pop_density_per_km2':10,'terminals_per_capita':0.1,
                'msgs_per_terminal_day':4,'klass':'A_prime'}]
    m = build_demand_map(uniform)
    check("равная плотность → вес по площади, а не по числу ячеек",
          abs(m['c60']['weight']/m['c0']['weight'] - a60/a0) < 0.01,
          f"{m['c60']['weight']/m['c0']['weight']:.3f} vs {a60/a0:.3f}")
    check("веса нормированы", abs(sum(c['weight'] for c in m.values()) - 1.0) < 1e-9)

    print("\nTZ-USR-004: слои спроса")
    # реалистичный слой: несколько населённых ячеек средних широт
    pop = [{'id':f'agro{lat}','lat':lat,'pop_density_per_km2':d,'terminals_per_capita':0.02,
            'msgs_per_terminal_day':4,'klass':'A_prime'}
           for lat, d in [(30,40),(40,55),(45,50),(50,35),(55,20)]]
    pts = [{'cell_id':'smp','lat':75,'terminals':400,'msgs_per_terminal_day':12,'klass':'C_prime'}]
    m2 = build_demand_map(pop, point_objects=pts)
    check("слой населения создаёт ячейки", all(f'agro{l}' in m2 for l in (30,40,45,50,55)))
    check("единичные объекты добавляют ячейку вне населения", 'smp' in m2)
    check("вес северной ячейки мал, но не нулевой",
          0 < m2['smp']['weight'] < 0.05, f"{m2['smp']['weight']:.4f}")
    m3 = build_demand_map(pop)
    check("карта строится при наличии только слоя населения",
          len(m3) == 5 and abs(sum(c['weight'] for c in m3.values()) - 1.0) < 1e-9)

    print("\nTZ-USR-005: профили активности")
    cell = m2['agro45']
    diurnal = [0.3]*6 + [1.5]*12 + [0.6]*6
    seasonal = [1.4,1.3,1.0,0.8,0.7,0.6,0.6,0.7,0.9,1.1,1.3,1.5]
    h, mo = worst_case_hour_month(cell, diurnal, seasonal)
    check("худший час в дневном окне", 6 <= h < 18, h)
    check("худший месяц зимний", mo in (0,11), mo)
    check("отсутствие профиля = равномерность",
          abs(intensity_at(cell, 3, 5) - sum(cell['msgs_per_day'].values())/24) < 1e-9)

    print("\nTZ-USR-007: спрос-взвешенное качество (эффект ССО)")
    # Население: средние широты. Плюс единичные объекты на севере.
    cells = build_demand_map(
        [{'id':f'c{lat}','lat':lat,'pop_density_per_km2':d,'terminals_per_capita':0.02,
          'msgs_per_terminal_day':4,'klass':'A_prime'}
         for lat, d in [(15,20),(30,40),(45,50),(60,8),(75,0.2)]])
    polar   = lambda c: 1.0 if abs(c['lat']) >= 60 else 0.35   # ССО: отлично у полюсов
    midlat  = lambda c: 0.9 if abs(c['lat']) < 60 else 0.4     # наклонное построение
    q_polar, q_mid = demand_weighted_quality(cells, polar), demand_weighted_quality(cells, midlat)
    check("ССО-построение не выигрывает при населённой карте спроса",
          q_mid > q_polar, f"ССО={q_polar:.3f}, наклонное={q_mid:.3f}")
    zero = copy.deepcopy(cells); zero['c75']['weight'] = 0.0   # глубокая копия: ячейки вложенные
    check("ячейка с нулевым спросом не влияет на интеграл",
          abs(demand_weighted_quality(zero, polar)
              - sum(c['weight']*polar(c) for c in zero.values() if c['weight'] > 0)) < 1e-12)
    prof = latitude_profile(cells, midlat)
    check("широтный профиль покрывает все пояса", len(prof) == len({int(math.floor(c['lat']/15)*15) for c in cells.values()}))
    check("сумма весов поясов равна единице", abs(sum(p['w'] for p in prof) - 1.0) < 1e-9)
    check("вес пояса 75° мал", [p for p in prof if p['band']==75][0]['w'] < 0.01)

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)
