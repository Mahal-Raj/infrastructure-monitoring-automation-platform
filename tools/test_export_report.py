from __future__ import annotations

import unittest

from export_report import markdown, percentage


class ReportExportTests(unittest.TestCase):
    def test_markdown_renders_node_metrics(self) -> None:
        report = {
            "generatedAt": "2026-01-01T00:00:00Z",
            "nodes": [
                {
                    "status": {"id": "east", "name": "East", "online": True, "lastSuccessAt": "now"},
                    "availability": {"availabilityPercent": 99.5},
                    "latestMetrics": {
                        "metrics": {
                            "cpu": {"usagePercent": 12.3},
                            "memory": {"usedPercent": 45.6},
                            "disk": {"usedPercent": 67.8},
                        }
                    },
                }
            ],
        }
        output = markdown(report)
        self.assertIn("| East | online | 99.50% |", output)
        self.assertIn("12.30%", output)
        self.assertIn("All registered nodes", output)

    def test_markdown_flags_offline_node(self) -> None:
        report = {
            "generatedAt": "now",
            "nodes": [
                {
                    "status": {"id": "west", "online": False, "latestError": "connection refused"},
                    "availability": {"availabilityPercent": 0},
                    "latestMetrics": None,
                }
            ],
        }
        output = markdown(report)
        self.assertIn("Investigate `west`: connection refused", output)
        self.assertIn("n/a", output)

    def test_percentage_handles_missing_value(self) -> None:
        self.assertEqual("n/a", percentage(None))
        self.assertEqual("10.00%", percentage(10))


if __name__ == "__main__":
    unittest.main(verbosity=2)
