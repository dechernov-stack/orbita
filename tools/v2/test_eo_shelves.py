"""Проверки связности поставки ДЗЗ перед публикацией в библиотеке Orbita."""
import unittest

from eo_shelves import records


class EarthObservationShelves(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = list(records())
        cls.by_kind = {}
        for kind, doc in cls.rows:
            cls.by_kind.setdefault(kind, []).append(doc)

    def test_catalog_identity(self):
        codes = [doc["code"] for _, doc in self.rows]
        self.assertEqual(len(codes), len(set(codes)))
        self.assertEqual("observation_task", self.rows[0][0])
        self.assertEqual("mission_class", self.rows[-1][0])
        for _, doc in self.rows:
            self.assertIn("mission:MC-EO", doc["tags"])
        tasks = self.by_kind["observation_task"]
        self.assertEqual(156, len(tasks))
        self.assertEqual(8, sum(t["source_id"].startswith("ИК-") for t in tasks))
        self.assertTrue({"1.1", "1.10"} <= {t["source_id"] for t in tasks})
        for task in tasks:
            self.assertEqual("EO-TASK-" + task["source_id"], task["code"])
            self.assertTrue(all(task[k].strip() for k in ("name", "section", "profile", "validation")))

    def test_structure_and_references(self):
        nodes = self.by_kind["pbs_template"][0]["nodes"]
        work = self.by_kind["wbs_template"][0]["packages"]
        for rows in (nodes, work):
            codes = {row["code"] for row in rows}
            self.assertEqual(len(codes), len(rows))
            parents = {row["code"]: row.get("parent") for row in rows}
            for code in codes:
                path = set()
                while code:
                    self.assertIn(code, codes)
                    self.assertNotIn(code, path)
                    path.add(code)
                    code = parents[code]
        levels = {row["code"]: row["level"] for row in nodes}
        for n in nodes:
            self.assertEqual(0 if n["parent"] is None else levels[n["parent"]] + 1, n["level"])
        for pkg in work:
            self.assertTrue(pkg["cross_cutting"] or pkg["pbs_refs"], pkg["code"])
            for ref in pkg["pbs_refs"]:
                self.assertIn(ref.removeprefix("@"), levels, pkg["code"])
        models = {r["code"] for r in self.by_kind["model_template"][0]["models"]}
        interfaces = self.by_kind["interface_template"][0]["interfaces"]
        interface_codes = {i["code"] for i in interfaces}
        arch = self.by_kind["architecture_template"][0]
        functions = {f["code"] for f in arch["functions"]}
        for i in interfaces:
            self.assertTrue({i["a"], i["b"]} <= levels.keys(), i["code"])
            self.assertTrue(set(i.get("models", [])) <= models, i["code"])
        for f in arch["functions"]:
            self.assertIn(f["allocated_to"], levels)
        for exchange in arch["exchanges"]:
            self.assertTrue({exchange["src"], exchange["dst"]} <= functions)
            self.assertIn(exchange["interface"], interface_codes)
        for chain in arch["chains"]:
            self.assertTrue(set(chain["steps"]) <= functions, chain["code"])
        mission = self.by_kind["mission_class"][0]
        self.assertEqual(set(mission["default_constraints"]), {r["code"] for r in self.by_kind["constraint"]})
        self.assertTrue({m["model_code"] for m in mission["mandatory_models"]} <= models)


if __name__ == "__main__":
    unittest.main()
