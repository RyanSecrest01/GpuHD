#!/usr/bin/env python3
"""Merge ChunkObjectExporter output into a deterministic source dataset."""

import argparse
import csv
import json
import shutil
from collections import defaultdict
from pathlib import Path


TERRAIN_FIELDS = [
    "world_x", "world_y", "plane", "scene_x", "scene_y", "render_level",
    "underlay_id", "overlay_id", "texture_ids", "tile_type", "shape",
    "rotation", "paint_rgb", "paint_sw_hsl", "paint_se_hsl", "paint_ne_hsl",
    "paint_nw_hsl", "model_underlay_color", "model_overlay_color",
    "model_triangle_hsl", "average_rgb", "observation_count", "source_exports",
]
OBJECT_FIELDS = [
    "object_id", "name", "type", "world_x", "world_y", "plane", "orientation",
    "texture_ids", "current_material", "current_authored_slot",
    "observation_count", "source_exports",
]
CONFLICT_FIELDS = ["record_type", "identity_key", "field", "values", "source_exports"]
NON_CONFLICT_FIELDS = {"scene_x", "scene_y", "observation_count", "source_exports"}


def scalar(value):
    if value is None:
        return ""
    if isinstance(value, bool):
        return str(value).lower()
    return str(value)


def list_value(value):
    if value is None:
        return ""
    if isinstance(value, list):
        return "|".join(scalar(item) for item in value)
    return scalar(value).replace(",", "|")


def normalize_terrain(record):
    return {
        "world_x": scalar(record.get("worldX", record.get("world_x"))),
        "world_y": scalar(record.get("worldY", record.get("world_y"))),
        "plane": scalar(record.get("plane")),
        "scene_x": scalar(record.get("sceneX", record.get("scene_x"))),
        "scene_y": scalar(record.get("sceneY", record.get("scene_y"))),
        "render_level": scalar(record.get("renderLevel", record.get("render_level"))),
        "underlay_id": scalar(record.get("underlayId", record.get("underlay_id"))),
        "overlay_id": scalar(record.get("overlayId", record.get("overlay_id"))),
        "texture_ids": list_value(record.get("textureIds", record.get("texture_ids"))),
        "tile_type": scalar(record.get("tileType", record.get("tile_type"))),
        "shape": scalar(record.get("shape")),
        "rotation": scalar(record.get("rotation")),
        "paint_rgb": scalar(record.get("paintRgb", record.get("paint_rgb"))),
        "paint_sw_hsl": scalar(record.get("paintSwHsl", record.get("paint_sw_hsl"))),
        "paint_se_hsl": scalar(record.get("paintSeHsl", record.get("paint_se_hsl"))),
        "paint_ne_hsl": scalar(record.get("paintNeHsl", record.get("paint_ne_hsl"))),
        "paint_nw_hsl": scalar(record.get("paintNwHsl", record.get("paint_nw_hsl"))),
        "model_underlay_color": scalar(record.get("modelUnderlayColor", record.get("model_underlay_color"))),
        "model_overlay_color": scalar(record.get("modelOverlayColor", record.get("model_overlay_color"))),
        "model_triangle_hsl": list_value(record.get("modelTriangleHsl", record.get("model_triangle_hsl"))),
        "average_rgb": scalar(record.get("averageRgb", record.get("average_rgb"))),
    }


def normalize_object(record):
    return {
        "object_id": scalar(record.get("objectId", record.get("object_id"))),
        "name": scalar(record.get("name")),
        "type": scalar(record.get("type")),
        "world_x": scalar(record.get("worldX", record.get("world_x"))),
        "world_y": scalar(record.get("worldY", record.get("world_y"))),
        "plane": scalar(record.get("plane")),
        "orientation": scalar(record.get("orientation")),
        "texture_ids": list_value(record.get("textureIds", record.get("texture_ids"))),
        "current_material": scalar(record.get("currentMaterial", record.get("current_material"))),
        "current_authored_slot": scalar(record.get("currentAuthoredSlot", record.get("current_authored_slot"))),
    }


def identity(record, fields):
    return "|".join(record[field] for field in fields)


def read_exports(root):
    terrain = defaultdict(list)
    objects = defaultdict(list)
    texture_files = defaultdict(set)
    export_dirs = sorted(path for path in root.iterdir() if path.is_dir())
    for export_dir in export_dirs:
        chunk = export_dir / "chunk.json"
        if chunk.exists():
            data = json.loads(chunk.read_text(encoding="utf-8"))
            terrain_records = data.get("terrain", [])
            object_records = data.get("objects", [])
        else:
            terrain_records = read_csv(export_dir / "terrain.csv")
            object_records = read_csv(export_dir / "objects.csv")

        source = export_dir.name
        for record in terrain_records:
            normalized = normalize_terrain(record)
            terrain[identity(normalized, ("world_x", "world_y", "plane"))].append((source, normalized))
            for texture_id in normalized["texture_ids"].split("|") if normalized["texture_ids"] else []:
                if texture_id:
                    candidate = export_dir / "textures" / f"texture-{texture_id}.png"
                    if candidate.exists():
                        texture_files[texture_id].add(candidate)
        for record in object_records:
            normalized = normalize_object(record)
            key = identity(normalized, ("object_id", "world_x", "world_y", "plane", "type", "orientation"))
            objects[key].append((source, normalized))
            for texture_id in normalized["texture_ids"].split("|") if normalized["texture_ids"] else []:
                if texture_id:
                    candidate = export_dir / "textures" / f"texture-{texture_id}.png"
                    if candidate.exists():
                        texture_files[texture_id].add(candidate)
    return terrain, objects, texture_files, export_dirs


def read_csv(path):
    if not path.exists():
        return []
    with path.open(newline="", encoding="utf-8") as stream:
        return list(csv.DictReader(stream))


def canonicalize(records, record_type, identity_key, fields, conflicts):
    records = sorted(records, key=lambda item: item[0])
    source_names = sorted({source for source, _ in records})
    canonical = dict(records[0][1])
    for field in fields:
        if field in NON_CONFLICT_FIELDS:
            continue
        values = sorted({record[field] for _, record in records})
        if len(values) > 1:
            conflicts.append({
                "record_type": record_type,
                "identity_key": identity_key,
                "field": field,
                "values": " || ".join(values),
                "source_exports": "|".join(source_names),
            })
    canonical["observation_count"] = str(len(records))
    canonical["source_exports"] = "|".join(source_names)
    return canonical


def write_csv(path, fields, rows):
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="Raw gpuhd-object-dumps directory")
    parser.add_argument("output", type=Path, help="Master dataset output directory")
    args = parser.parse_args()
    root = args.input.expanduser().resolve()
    output = args.output.resolve()
    if not root.is_dir():
        parser.error(f"Input directory does not exist: {root}")

    terrain, objects, texture_files, export_dirs = read_exports(root)
    conflicts = []
    terrain_rows = [canonicalize(items, "terrain", key, TERRAIN_FIELDS, conflicts)
                    for key, items in sorted(terrain.items())]
    object_rows = [canonicalize(items, "object", key, OBJECT_FIELDS, conflicts)
                   for key, items in sorted(objects.items())]

    output.mkdir(parents=True, exist_ok=True)
    write_csv(output / "terrain.csv", TERRAIN_FIELDS, terrain_rows)
    write_csv(output / "objects.csv", OBJECT_FIELDS, object_rows)
    write_csv(output / "conflicts.csv", CONFLICT_FIELDS,
              sorted(conflicts, key=lambda row: tuple(row[field] for field in CONFLICT_FIELDS)))

    terrain_usage = defaultdict(lambda: {"occurrences": 0, "locations": set()})
    for row in terrain_rows:
        for kind in ("underlay", "overlay"):
            value = row[f"{kind}_id"]
            if value and value != "-1":
                item = terrain_usage[(kind, value)]
                item["occurrences"] += int(row["observation_count"])
                item["locations"].add(f"{row['world_x']}:{row['world_y']}:{row['plane']}")
    usage_rows = []
    for (kind, source_id), value in sorted(terrain_usage.items()):
        usage_rows.append({"source_type": kind, "source_id": source_id,
                           "occurrences": value["occurrences"],
                           "locations": "|".join(sorted(value["locations"]))})
    write_csv(output / "terrain-id-usage.csv",
              ["source_type", "source_id", "occurrences", "locations"], usage_rows)

    texture_ids = set(texture_files)
    for row in terrain_rows + object_rows:
        texture_ids.update(value for value in row["texture_ids"].split("|") if value)
    texture_rows = []
    destination = output / "textures"
    destination.mkdir(exist_ok=True)
    for texture_id in sorted(texture_ids, key=lambda value: int(value)):
        sources = sorted(texture_files.get(texture_id, set()))
        target = destination / f"texture-{texture_id}.png"
        if sources:
            shutil.copyfile(sources[0], target)
        texture_rows.append({
            "texture_id": texture_id,
            "reference_file": f"textures/texture-{texture_id}.png" if target.exists() else "",
            "available": "true" if target.exists() else "false",
            "source_exports": "|".join(path.parent.parent.name for path in sources),
        })
    write_csv(output / "texture-sources.csv",
              ["texture_id", "reference_file", "available", "source_exports"], texture_rows)

    manifest = {
        "schema_version": 1,
        "input_directory": root.name,
        "export_count": len(export_dirs),
        "terrain_observation_count": sum(int(row["observation_count"]) for row in terrain_rows),
        "terrain_record_count": len(terrain_rows),
        "object_observation_count": sum(int(row["observation_count"]) for row in object_rows),
        "object_placement_count": len(object_rows),
        "texture_id_count": len(texture_rows),
        "texture_reference_count": sum(row["available"] == "true" for row in texture_rows),
        "conflict_count": len(conflicts),
        "files": ["terrain.csv", "objects.csv", "terrain-id-usage.csv",
                  "texture-sources.csv", "conflicts.csv", "manifest.json"],
    }
    (output / "manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
