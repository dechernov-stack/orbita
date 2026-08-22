#!/usr/bin/env python3
"""Исполняемый эталон вывода связей из документа (Шаг 16 §3.1, ADR-027).

Документ — ЕДИНСТВЕННЫЙ источник связей trace, allocation и derive: при каждой
записи новой версии связи пересчитываются по документу. До этого обычный ход
работы — создал требование, потом привязал правкой — не давал ни одной связи:
их заводил только ingest при создании, а правка связей не трогала. Дерево
оставалось плоским, матрица трассировки пустой.

Правила пересчёта:
  - ссылка исчезла из документа → связь удаляется;
  - ссылка появилась → связь создаётся;
  - уцелевшая связь правится НА МЕСТЕ, а не пересоздаётся: derivation_kind,
    выставленный операцией deriveAs, сохраняется. Удаление и вставка молча
    вернули бы 'allocated' — производное требование стало бы распределённым
    и вошло бы в свёртку бюджета: самый дорогой из возможных тихих сбоев;
  - ручное создание связей этих трёх видов запрещено: два источника связей
    разошлись бы; вид verification маршрут /links сохраняет.

Связь need→service объявляется с ДВУХ сторон (need.traces_down и
service.traces_up): она удаляется, только когда её не объявляет ни один из
двух документов — иначе правка сервиса молча рвала бы нить, которую нужда
продолжает объявлять.
"""
import sys

OWNED_KINDS = ('trace', 'allocation', 'derive')


def desired_links(obj_type, obj_id, doc):
    """Связи, которые документ объявляет. Ключ: (from, to, kind)."""
    out = {}
    if obj_type == 'need':
        for sv in doc.get('traces_down', []):
            out[(obj_id, sv, 'trace')] = {}
    if obj_type == 'service':
        for nd in doc.get('traces_up', []):
            out[(nd, obj_id, 'trace')] = {}
    if obj_type == 'requirement':
        for t in doc.get('traces_up', []):
            out[(t['ref'], obj_id, 'trace')] = {'consumer_class': t.get('consumer_class')}
        for a in doc.get('allocated_to', []):
            target = a.get('component') or a.get('interface')
            out[(obj_id, target, 'allocation')] = {
                'allocation_kind': a.get('kind', 'full'),
                'rationale': a.get('rationale'),
            }
        for parent in doc.get('derives_from', []):
            out[(parent, obj_id, 'derive')] = {'derivation_kind': 'allocated'}
    return out


def sync_links(obj_type, obj_id, doc, links, other_docs=None):
    """Пересчёт связей объекта по документу. links — весь список связей модели,
    каждая: {'from','to','kind', ...атрибуты}. other_docs: id → (type, doc) —
    для двустороннего вида need↔service."""
    other_docs = other_docs or {}
    desired = desired_links(obj_type, obj_id, doc)

    def owned(link):
        if link['kind'] not in OWNED_KINDS:
            return False
        if obj_type == 'need':
            return link['kind'] == 'trace' and link['from'] == obj_id
        if obj_type == 'service':
            return link['kind'] == 'trace' and link['to'] == obj_id
        if obj_type == 'requirement':
            return ((link['kind'] == 'trace' and link['to'] == obj_id)
                    or (link['kind'] == 'allocation' and link['from'] == obj_id)
                    or (link['kind'] == 'derive' and link['to'] == obj_id))
        return False

    def declared_by_other_end(link):
        """Объявляет ли связь документ другого конца (двусторонний trace)."""
        if link['kind'] != 'trace' or obj_type == 'requirement':
            return False
        other_id = link['to'] if obj_type == 'need' else link['from']
        other = other_docs.get(other_id)
        if other is None:
            return False
        other_type, other_doc = other
        key = (link['from'], link['to'], link['kind'])
        return key in desired_links(other_type, other_id, other_doc)

    kept = []
    for link in links:
        key = (link['from'], link['to'], link['kind'])
        if not owned(link):
            kept.append(link)
        elif key in desired:
            # уцелевшая связь правится НА МЕСТЕ: несущностные атрибуты — из
            # документа, derivation_kind остаётся как выставлен deriveAs
            updated = dict(link)
            for attr, value in desired[key].items():
                if attr != 'derivation_kind':
                    updated[attr] = value
            kept.append(updated)
            del desired[key]
        elif declared_by_other_end(link):
            kept.append(link)
        # иначе — ссылка исчезла из документа, связь удаляется
    for key, attrs in desired.items():
        kept.append({'from': key[0], 'to': key[1], 'kind': key[2], **attrs})
    return kept


def manual_link_allowed(kind):
    """POST /links: три выводимых вида запрещены — два источника разойдутся."""
    return kind not in OWNED_KINDS


# ================= проверки =================
def _run_checks():
    ok = fail = 0

    def check(name, cond, detail=''):
        nonlocal ok, fail
        ok, fail = (ok + 1, fail) if cond else (ok, fail + 1)
        print(f"  {'+' if cond else '-'} {name}" + ('' if cond else f' {detail}'))

    print("Шаг 16 §3.1 / ADR-027: связи выводятся из документа")

    # 1. создать без родителя → правкой добавить derives_from → родитель в дереве
    links = sync_links('requirement', 'RQ-2', {'traces_up': []}, [])
    check("создание без родителя не даёт derive-связи",
          not any(l['kind'] == 'derive' for l in links))
    links = sync_links('requirement', 'RQ-2', {'derives_from': ['RQ-1']}, links)
    check("правкой добавили derives_from — родитель виден в дереве",
          {'from': 'RQ-1', 'to': 'RQ-2', 'kind': 'derive', 'derivation_kind': 'allocated'} in links)

    # 2. правкой убрать → связь исчезла
    links = sync_links('requirement', 'RQ-2', {'derives_from': []}, links)
    check("правкой убрали — связь исчезла", not any(l['kind'] == 'derive' for l in links))

    # 3. deriveAs('derived') переживает правку постороннего поля
    links = sync_links('requirement', 'RQ-2', {'derives_from': ['RQ-1']}, [])
    links[0]['derivation_kind'] = 'derived'          # операция deriveAs
    links = sync_links('requirement', 'RQ-2',
                       {'derives_from': ['RQ-1'], 'statement': 'новая формулировка'}, links)
    check("вид декомпозиции не сброшен правкой постороннего поля",
          links[0]['derivation_kind'] == 'derived', links)

    # 4. ручной POST /links kind=trace — отказ
    check("ручное создание trace запрещено", not manual_link_allowed('trace'))
    check("ручное создание allocation запрещено", not manual_link_allowed('allocation'))
    check("ручное создание derive запрещено", not manual_link_allowed('derive'))
    check("verification создаётся маршрутом", manual_link_allowed('verification'))

    # атрибуты уцелевшей связи правятся на месте
    links = sync_links('requirement', 'RQ-3',
                       {'allocated_to': [{'component': 'CM-1', 'kind': 'full'}]}, [])
    links = sync_links('requirement', 'RQ-3',
                       {'allocated_to': [{'component': 'CM-1', 'kind': 'partial',
                                          'rationale': 'делит массу'}]}, links)
    check("атрибуты распределения обновлены на месте",
          links[0]['allocation_kind'] == 'partial' and links[0]['rationale'] == 'делит массу')

    # двусторонний trace need↔service
    nd = {'traces_down': ['SV-1']}
    sv = {'traces_up': ['ND-1']}
    links = sync_links('need', 'ND-1', nd, [], {'SV-1': ('service', sv)})
    links = sync_links('service', 'SV-1', {'traces_up': []}, links,
                       {'ND-1': ('need', nd)})
    check("связь жива, пока её объявляет хотя бы один из двух документов",
          {'from': 'ND-1', 'to': 'SV-1', 'kind': 'trace'} in links)
    links = sync_links('need', 'ND-1', {'traces_down': []}, links,
                       {'SV-1': ('service', {'traces_up': []})})
    check("связь удалена, когда её не объявляет ни один документ", links == [])

    # чужие виды пересчёт не трогает
    foreign = [{'from': 'RQ-9', 'to': 'EV-1', 'kind': 'verification'}]
    links = sync_links('requirement', 'RQ-9', {'traces_up': []}, foreign)
    check("verification-связь пересчётом не затронута", foreign[0] in links)

    print(f"\nИтог: пройдено {ok}, провалено {fail}")
    sys.exit(1 if fail else 0)


if __name__ == '__main__':
    _run_checks()
