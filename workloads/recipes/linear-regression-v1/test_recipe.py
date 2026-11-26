import importlib.util
import json
from pathlib import Path
import unittest


SPEC = importlib.util.spec_from_file_location("recipe", Path(__file__).with_name("run.py"))
recipe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recipe)


def valid():
    return {"schemaVersion": 1, "features": [[0], [1], [2], [3]],
            "targets": [1, 3, 5, 7], "epochs": 100, "learningRate": 0.01}


class RecipeTests(unittest.TestCase):
    def test_example_is_valid(self):
        raw = Path(__file__).with_name("example-request.json").read_bytes()
        self.assertEqual(valid(), recipe.parse_request(raw))

    def test_rejects_unsafe_or_unbounded_input(self):
        cases = [
            {**valid(), "epochs": 101},
            {**valid(), "epochs": True},
            {**valid(), "features": [[0], [1, 2]]},
            {**valid(), "features": [[0]]},
            {**valid(), "targets": [1]},
            {**valid(), "learningRate": -1},
            {**valid(), "features": [[0], [1], [2], [float("nan")]]},
            {**valid(), "extra": "arbitrary code"},
        ]
        for case in cases:
            with self.subTest(case=case), self.assertRaises(ValueError):
                recipe.parse_request(json.dumps(case).encode())
        with self.assertRaises(ValueError):
            recipe.parse_request(b'{"schemaVersion":1,"schemaVersion":1}')
        with self.assertRaises(ValueError):
            recipe.parse_request(b" " * (recipe.MAX_INPUT_BYTES + 1))

    @unittest.skipUnless(importlib.util.find_spec("torch"), "PyTorch not installed")
    def test_cpu_training_smoke(self):
        result = recipe.train(valid(), device="cpu")
        self.assertEqual("cpu", result["device"])
        self.assertEqual(1, len(result["weights"]))
        self.assertLess(result["meanSquaredError"], 1.0)


if __name__ == "__main__":
    unittest.main()
