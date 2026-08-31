import unittest

from tools.arcaea_db_schema import (
    BYD_TYPE_BEYOND,
    BYD_TYPE_INSCRIBED,
    classification_from_semantic,
    classification_from_songlist,
    migrate_database,
    semantic_from_songlist,
)


class ArcaeaDatabaseSchemaTest(unittest.TestCase):
    def test_beyond_and_inscribed_share_rating_class_but_not_byd_type(self):
        beyond = classification_from_semantic("BYD")
        inscribed = classification_from_semantic("INS")

        self.assertEqual(3, beyond.rating_class)
        self.assertEqual(3, inscribed.rating_class)
        self.assertEqual(BYD_TYPE_BEYOND, beyond.byd_type)
        self.assertEqual(BYD_TYPE_INSCRIBED, inscribed.byd_type)
        self.assertIsNone(beyond.rating_class_alias)
        self.assertEqual(1, inscribed.rating_class_alias)

    def test_songlist_alias_one_is_inscribed(self):
        value = classification_from_songlist(3, 1)
        self.assertEqual("INS", value.semantic)
        self.assertEqual(1, value.byd_type)
        self.assertEqual("INS", semantic_from_songlist(3, 1))

    def test_rating_class_three_without_alias_is_beyond(self):
        value = classification_from_songlist(3, None)
        self.assertEqual("BYD", value.semantic)
        self.assertEqual(0, value.byd_type)

    def test_legacy_migration_keeps_semantic_keys_and_adds_classification(self):
        legacy = {
            "format": "arcaea_wiki_entries",
            "schema_version": 1,
            "entries": [
                {
                    "song": {"id": "dreadarea", "title": "DREAD AREA"},
                    "charts": {
                        "FTR": {"level": "10", "constant": 10.3, "notes": 1405},
                        "INS": {"level": "11", "constant": 11.4, "notes": 1663},
                    },
                }
            ],
        }
        migrated, warnings = migrate_database(legacy)

        self.assertEqual([], warnings)
        self.assertEqual("arcaea_tracker_database", migrated["format"])
        self.assertEqual(2, migrated["schema_version"])
        self.assertIn("FTR", migrated["entries"][0]["charts"])
        self.assertIn("INS", migrated["entries"][0]["charts"])
        ins = migrated["entries"][0]["charts"]["INS"]["classification"]
        self.assertEqual(3, ins["ratingClass"])
        self.assertEqual(1, ins["ratingClassAlias"])
        self.assertEqual(1, ins["bydType"])
        self.assertEqual("legacy-v1-semantic", ins["source"])

    def test_songlist_enrichment_replaces_inference_with_source_classification(self):
        legacy = {
            "format": "arcaea_wiki_entries",
            "schema_version": 1,
            "entries": [
                {
                    "song": {"id": "dreadarea", "title": "DREAD AREA"},
                    "charts": {
                        "FTR": {"level": "10", "notes": 1405},
                        "INS": {"level": "11", "notes": 1663},
                    },
                }
            ],
        }
        songlist = [
            {
                "id": "dreadarea",
                "difficulties": [
                    {"ratingClass": 2},
                    {
                        "ratingClass": 3,
                        "ratingClassAlias": 1,
                        "hiddenUntilUnlocked": True,
                    },
                ],
            }
        ]

        migrated, warnings = migrate_database(legacy, songlist)
        self.assertEqual([], warnings)
        ins = migrated["entries"][0]["charts"]["INS"]
        self.assertEqual("songlist", ins["classification"]["source"])
        self.assertEqual(1, ins["classification"]["ratingClassAlias"])
        self.assertEqual(True, ins["visibility"]["hiddenUntilUnlocked"])


if __name__ == "__main__":
    unittest.main()
