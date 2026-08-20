#!/usr/bin/env python3
"""Исполняемый эталон загрузки внешних данных (шаг 10).

Карта спроса до сих пор строилась из программных популяций. Настоящий слой
населения приходит из внешнего датасета на сетке, и на этом пути живут ошибки,
которые не видны ни в одном отчёте: перепутанные широта и долгота, счёт по числу
ячеек вместо площади, потеря или удвоение при смене разрешения, пропуски над водой.

  координаты   широта и долгота не перепутаны; проверка по известным точкам
  площадь      вес по площади ячейки, а не по числу ячеек
  разрешение   агрегация грубее сохраняет суммарное население
  пропуски     отсутствие данных — ноль, а не пропуск и не NaN
  коэффициент  терминалы = население × коэффициент, применяется после агрегации
  границы      антимеридиан и полюса не теряются
"""
import math, sys

R_EARTH_KM = 6371.0

def cell_area_km2(lat_deg, dlat=1.0, dlon=1.0):
    lat = math.radians(lat_deg)
    h = math.radians(dlat)
    return R_EARTH_KM**2 * math.radians(dlon) * (math.sin(lat + h/2) - math.sin(lat - h/2))

def validate_coords(rec):
    p = []
    if not -90 <= rec['lat'] <= 90:
        p.append(f'широта вне диапазона: {rec["lat"]}')
    if not -180 <= rec['lon'] <= 180:
        p.append(f'долгота вне диапазона: {rec["lon"]}')
    return p

def looks_swapped(records):
    """Дешёвый признак: широта вне ±90. Ловит только однозначный случай."""
    return any(abs(r['lat']) > 90 for r in records)

# Опорные точки: суша с заведомым населением и открытый океан.
# Перестановка координат превращает населённые точки в пустые и наоборот —
# по диапазонам это неразличимо, потому что города Европы укладываются в ±90
# по обеим осям. Нужны именно опорные точки, а не проверка границ.
REFERENCE = [
    {'name': 'Москва',            'lat': 55.75, 'lon': 37.62,  'expect': 'populated'},
    {'name': 'Дели',              'lat': 28.61, 'lon': 77.21,  'expect': 'populated'},
    {'name': 'Тихий океан, центр','lat': -10.0, 'lon': -140.0, 'expect': 'empty'},
    {'name': 'Южный океан',       'lat': -60.0, 'lon': 0.0,    'expect': 'empty'},
]

def reference_check(lookup, points=REFERENCE, populated_threshold=1.0):
    """lookup(lat, lon) -> плотность. Возвращает перечень расхождений с ожиданием."""
    bad = []
    for p in points:
        d = lookup(p['lat'], p['lon'])
        if p['expect'] == 'populated' and d < populated_threshold:
            bad.append(f'{p["name"]}: ожидалось население, получено {d}')
        if p['expect'] == 'empty' and d >= populated_threshold:
            bad.append(f'{p["name"]}: ожидалась пустота, получено {d}')
    return bad

def ingest(records, terminals_per_capita, dlat=1.0, dlon=1.0):
    """Датасет плотности → ячейки с населением и числом терминалов."""
    problems = []
    for r in records:
        problems += validate_coords(r)
    if problems:
        raise ValueError('; '.join(problems[:3]))
    if looks_swapped(records):
        raise ValueError('похоже, широта и долгота перепутаны')
    cells = {}
    for r in records:
        area = cell_area_km2(r['lat'], dlat, dlon)
        dens = r.get('density_per_km2')
        if dens is None:
            dens = 0.0                      # нет данных (вода, пропуск) — ноль, не NaN
        pop = dens * area
        key = (round(r['lat'], 6), round(r['lon'], 6))
        c = cells.setdefault(key, {'lat': r['lat'], 'lon': r['lon'],
                                   'area_km2': area, 'population': 0.0})
        c['population'] += pop
    for c in cells.values():
        c['terminals'] = c['population'] * terminals_per_capita
    return cells

def aggregate(cells, factor):
    """Огрубление сетки: население и терминалы суммируются, площади складываются."""
    if factor < 1 or int(factor) != factor:
        raise ValueError('коэффициент огрубления — целое ≥ 1')
    out = {}
    for c in cells.values():
        key = (math.floor(c['lat'] / factor) * factor, math.floor(c['lon'] / factor) * factor)
        g = out.setdefault(key, {'lat': key[0], 'lon': key[1],
                                 'area_km2': 0.0, 'population': 0.0, 'terminals': 0.0})
        for f in ('area_km2', 'population', 'terminals'):
            g[f] += c[f]
    return out

def total(cells, field='population'):
    return sum(c[field] for c in cells.values())

def weights_by_area(cells):
    """Вес ячейки — по её вкладу, а не по факту существования."""
    t = total(cells)
    return {k: (c['population'] / t if t else 0.0) for k, c in cells.items()}

def library_complete(entries, required_fields):
    """Библиотека пресетов или сценариев: запись без обязательных полей непригодна."""
    return {e.get('id', '?'): [f for f in required_fields if not e.get(f)]
            for e in entries if any(not e.get(f) for f in required_fields)}

# ================= проверки =================
def _run_checks():
    ok = fail = 0
    def check(name, cond, detail=''):
        nonlocal ok, fail
        if cond: ok += 1; print(f"  + {name}")
        else:    fail += 1; print(f"  - {name} {detail}")

    print("Координаты")
    check("корректная запись принята", validate_coords({'lat': 55.7, 'lon': 37.6}) == [])
    check("широта вне диапазона выявлена",
          validate_coords({'lat': 137.6, 'lon': 55.7}) != [])
    check("долгота вне диапазона выявлена",
          validate_coords({'lat': 55.7, 'lon': 237.6}) != [])
    check("широта вне ±90 ловится дешёвым признаком",
          looks_swapped([{'lat': 137.6, 'lon': 55.7}]))
    check("перестановка в пределах ±90 дешёвым признаком не ловится",
          not looks_swapped([{'lat': 37.6, 'lon': 55.7}]))

    # правильный датасет: население там, где суша
    DATA = {(55.75, 37.62): 4000.0, (28.61, 77.21): 11000.0}
    good_lookup = lambda la, lo: DATA.get((round(la, 2), round(lo, 2)), 0.0)
    swapped_lookup = lambda la, lo: DATA.get((round(lo, 2), round(la, 2)), 0.0)
    check("правильный датасет проходит по опорным точкам",
          reference_check(good_lookup) == [], reference_check(good_lookup))
    bad = reference_check(swapped_lookup)
    check("перестановка координат выявлена опорными точками", len(bad) >= 2, bad)
    check("названо, какая точка не сошлась", 'Москва' in bad[0])
    ocean_lookup = lambda la, lo: 500.0
    check("население посреди океана выявлено",
          any('океан' in b for b in reference_check(ocean_lookup)))
    try:
        ingest([{'lat': 100, 'lon': 0, 'density_per_km2': 1}], 0.02)
        check("загрузка с битой широтой отклонена", False)
    except ValueError:
        check("загрузка с битой широтой отклонена", True)

    print("\nПлощадь и вес")
    a0, a60 = cell_area_km2(0), cell_area_km2(60)
    check("площадь ячейки убывает к полюсу", a0 > a60)
    check("ячейка на 60° примерно вдвое меньше", abs(a60 / a0 - 0.5) < 0.02, f"{a60/a0:.3f}")
    uniform = [{'lat': 0, 'lon': 0, 'density_per_km2': 10},
               {'lat': 60, 'lon': 0, 'density_per_km2': 10}]
    cells = ingest(uniform, 0.02)
    w = weights_by_area(cells)
    k0, k60 = (0.0, 0.0), (60.0, 0.0)
    check("при равной плотности вес идёт по площади",
          abs(w[k60] / w[k0] - a60 / a0) < 0.01, f"{w[k60]/w[k0]:.3f} vs {a60/a0:.3f}")
    check("веса нормированы", abs(sum(w.values()) - 1.0) < 1e-9)
    check("население считается из плотности и площади",
          abs(cells[k0]['population'] - 10 * a0) < 1e-6)

    print("\nКоэффициент терминалов")
    check("терминалы = население × коэффициент",
          abs(cells[k0]['terminals'] - cells[k0]['population'] * 0.02) < 1e-9)
    c2 = ingest(uniform, 0.04)
    check("удвоение коэффициента удваивает терминалы",
          abs(c2[k0]['terminals'] - 2 * cells[k0]['terminals']) < 1e-6)
    check("коэффициент не влияет на население",
          abs(c2[k0]['population'] - cells[k0]['population']) < 1e-9)

    print("\nСмена разрешения")
    fine = ingest([{'lat': la, 'lon': lo, 'density_per_km2': 5}
                   for la in (30, 31, 32, 33) for lo in (10, 11, 12, 13)], 0.02)
    coarse = aggregate(fine, 4)
    check("огрубление сохраняет население",
          abs(total(coarse) - total(fine)) < 1e-6, f"{total(coarse):.1f} vs {total(fine):.1f}")
    check("огрубление сохраняет терминалы",
          abs(total(coarse, 'terminals') - total(fine, 'terminals')) < 1e-6)
    check("огрубление сохраняет площадь",
          abs(total(coarse, 'area_km2') - total(fine, 'area_km2')) < 1e-6)
    check("число ячеек уменьшается", len(coarse) < len(fine), f"{len(fine)} → {len(coarse)}")
    check("огрубление вдвое даёт больше ячеек, чем вчетверо",
          len(aggregate(fine, 2)) >= len(coarse))
    try:
        aggregate(fine, 0); check("недопустимый коэффициент отклонён", False)
    except ValueError:
        check("недопустимый коэффициент отклонён", True)

    print("\nПропуски и границы")
    with_gaps = ingest([{'lat': 10, 'lon': 20, 'density_per_km2': 5},
                        {'lat': 11, 'lon': 20}], 0.02)     # вторая — над водой
    check("отсутствие плотности даёт ноль, а не пропуск", len(with_gaps) == 2)
    check("ячейка без данных не вносит население",
          with_gaps[(11.0, 20.0)]['population'] == 0.0)
    check("ячейка без данных сохраняет площадь", with_gaps[(11.0, 20.0)]['area_km2'] > 0)
    edge = ingest([{'lat': 0, 'lon': 180, 'density_per_km2': 3},
                   {'lat': 0, 'lon': -180, 'density_per_km2': 3},
                   {'lat': 89.5, 'lon': 0, 'density_per_km2': 1}], 0.02)
    check("антимеридиан не теряется", len(edge) == 3)
    check("приполюсная ячейка имеет малую, но ненулевую площадь",
          0 < edge[(89.5, 0.0)]['area_km2'] < cell_area_km2(0) * 0.02)

    print("\nПолнота библиотек")
    PRESETS = [{'id': '12U', 'dry_mass_kg': 18, 'sa_area_m2': 0.15, 'battery_wh': 80,
                'links': ['user_uplink'], 'source': 'аналог'},
               {'id': '16U', 'dry_mass_kg': 24, 'sa_area_m2': 0.2, 'battery_wh': 120,
                'links': ['user_uplink'], 'source': 'аналог'},
               {'id': 'micro_50', 'dry_mass_kg': 48, 'sa_area_m2': 0.6, 'battery_wh': 400,
                'links': [], 'source': ''}]
    req = ['dry_mass_kg', 'sa_area_m2', 'battery_wh', 'links', 'source']
    bad = library_complete(PRESETS, req)
    check("неполный пресет выявлен", set(bad) == {'micro_50'}, bad)
    check("названы недостающие поля", set(bad['micro_50']) == {'links', 'source'})
    check("полная библиотека замечаний не даёт",
          library_complete(PRESETS[:2], req) == {})
    SCEN = [{'id': 'agro', 'klass': 'A_prime', 'rate_per_day': 4, 'payload_bytes': 20,
             'geography': 'средние широты'},
            {'id': 'marine', 'klass': 'C_prime', 'rate_per_day': 12, 'payload_bytes': 40,
             'geography': ''}]
    badс = library_complete(SCEN, ['klass', 'rate_per_day', 'payload_bytes', 'geography'])
    check("неполный сценарий потребления выявлен", set(badс) == {'marine'})

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)


if __name__ == '__main__':
    _run_checks()
