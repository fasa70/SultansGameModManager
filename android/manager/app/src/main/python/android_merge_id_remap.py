"""Catalog-backed Android adapter for the upstream ID remapper."""
from __future__ import annotations

import errno
import hashlib
import json
import os
import shutil
import tempfile
from pathlib import Path
from typing import Any

from sultan_core import overlay_json
from sultan_core.json import JsonDoc

from upstream_sultan.core.data_manager import DataManager
from upstream_sultan.core.mod.id_remap import (
    RemapTable,
    _allocate_tag_name_remaps,
    _collect_all_used_ids,
    allocate_new_ids,
    apply_remap_to_store,
    build_remap_table,
    collect_mod_ids,
    compute_resource_rename,
    detect_conflicts,
)


def _overlay_text(texts: list[str]) -> str:
    """Return native overlay output as text across wheel API revisions."""
    result = overlay_json(texts)
    if isinstance(result, str):
        return result
    serializer = getattr(result, "to_string", None)
    if callable(serializer):
        serialized = serializer()
        if isinstance(serialized, str):
            return serialized
    raise TypeError(f"native overlay returned unexpected type: {type(result).__name__}")


def _raise_path_error(operation: str, path: Path, error: OSError) -> None:
    detail = f"{operation} 失败：{path}"
    if error.errno is not None:
        detail += f"（errno={error.errno}: {error.strerror or '未知文件系统错误'}）"
    raise OSError(error.errno, detail, str(path)) from error


def _copy_file_contents(source: Path, target: Path) -> None:
    """Copy bytes without chmod/utime metadata operations unsupported on Android."""
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        with source.open("rb") as source_stream, target.open("wb") as target_stream:
            shutil.copyfileobj(source_stream, target_stream)
    except OSError as error:
        _raise_path_error("复制文件", target, error)


def _copy_tree_without_metadata(source: Path, target: Path) -> None:
    if source.is_symlink() or not source.is_dir():
        raise ValueError(f"Mod input must be a real directory: {source}")
    try:
        target.mkdir(parents=True, exist_ok=False)
        children = sorted(source.iterdir(), key=lambda path: path.name)
    except OSError as error:
        _raise_path_error("创建或读取合并临时目录", target, error)
    for child in children:
        if child.is_symlink():
            raise ValueError(f"Mod 包含不安全符号链接：{child.name}")
        destination = target / child.name
        if child.is_dir():
            _copy_tree_without_metadata(child, destination)
        elif child.is_file():
            _copy_file_contents(child, destination)
        else:
            raise ValueError(f"Mod 包含非普通文件：{child.name}")


class DirectoryStore:
    """DataManager-shaped store with one explicit config layout per Mod."""

    def __init__(self, roots: list[Path]):
        self.roots = [root.resolve() for root in roots]
        self._config_layout: list[bool] = []
        self.invalid_json: list[dict[str, Any]] = []
        self.layout_warnings: list[dict[str, Any]] = []
        for root in self.roots:
            config = root / "config"
            config_json = _json_files(config) if config.is_dir() else []
            legacy_json = [
                path for path in _json_files(root)
                if "config" not in path.relative_to(root).parts
            ]
            # Info.json is metadata, not a config file.  A second JSON tree is
            # never silently ignored when a Mod contains both layouts.
            legacy_config = [
                path for path in legacy_json
                if path.name.lower() != "info.json"
            ]
            if config_json and legacy_config:
                self.layout_warnings.append({
                    "code": "mixed_layout",
                    "severity": "warning",
                    "message": (
                        f"Mod {root.name} 同时包含 config/ 和 legacy 配置布局；"
                        "已优先使用 config/，legacy JSON 将被跳过。"
                    ),
                    "count": 1,
                })
            self._config_layout.append(bool(config_json))

    def record_invalid_json(self, mod_id: str, rel_path: str, error: Exception) -> None:
        self.invalid_json.append({
            "code": "invalid_json",
            "severity": "warning",
            "message": f"Mod {mod_id} 的 {rel_path} 无法解析，已跳过：{error}",
            "entity_type": "json",
            "count": 1,
        })

    def _root(self, mod_id: str) -> Path:
        index = int(mod_id)
        if index < 0 or index >= len(self.roots):
            raise ValueError(f"invalid mod id: {mod_id}")
        return self.roots[index]

    def _uses_config(self, mod_id: str) -> bool:
        index = int(mod_id)
        if index < 0 or index >= len(self._config_layout):
            raise ValueError(f"invalid mod id: {mod_id}")
        return self._config_layout[index]

    @staticmethod
    def _logical(rel_path: str) -> str:
        path = Path(rel_path.replace("\\", "/"))
        if path.is_absolute() or not path.parts or ".." in path.parts or "." in path.parts:
            raise ValueError(f"unsafe relative path: {rel_path}")
        return "/".join(path.parts)

    def _physical(self, mod_id: str, logical: str, *, for_write: bool = False) -> Path:
        logical = self._logical(logical)
        root = self._root(mod_id)
        candidate = root / "config" / logical if self._uses_config(mod_id) else root / logical
        resolved_parent = candidate.parent.resolve()
        if root not in (resolved_parent, *resolved_parent.parents):
            raise ValueError(f"path escapes mod root: {logical}")
        return candidate

    def has_mod(self, mod_id: str, rel_path: str) -> bool:
        path = self._physical(mod_id, rel_path)
        return path.is_file() and not path.is_symlink()

    def get_mod(self, mod_id: str, rel_path: str):
        path = self._physical(mod_id, rel_path)
        if not path.is_file() or path.is_symlink():
            raise FileNotFoundError(rel_path)
        return JsonDoc.parse_file(str(path))

    def mod_files(self, mod_id: str) -> list[str]:
        root = self._root(mod_id)
        scan = root / "config" if self._uses_config(mod_id) else root
        result: list[str] = []
        for path in _json_files(scan):
            if path.is_symlink() or not path.is_file():
                raise ValueError(f"unsafe mod file: {path}")
            relative = path.relative_to(scan).as_posix()
            if relative.lower() != "info.json":
                result.append(relative)
        return sorted(result)

    def remove_mod_file(self, mod_id: str, rel_path: str) -> None:
        path = self._physical(mod_id, rel_path)
        if path.exists() and path.is_symlink():
            raise ValueError(f"unsafe mod file: {rel_path}")
        path.unlink(missing_ok=True)

    def set_mod(self, mod_id: str, rel_path: str, doc: Any) -> None:
        path = self._physical(mod_id, rel_path, for_write=True)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(doc.to_string(), encoding="utf-8")

    def rename_resource(self, mod_id: str, old_rel: str, new_rel: str) -> None:
        old = self._root(mod_id) / self._logical(old_rel)
        new = self._root(mod_id) / self._logical(new_rel)
        if old.is_symlink() or (new.exists() and new.is_symlink()):
            raise ValueError("resource symlink is not allowed")
        if not old.is_file():
            raise FileNotFoundError(old_rel)
        if new.exists():
            if new.read_bytes() != old.read_bytes():
                raise ValueError(f"resource rename collision: {new_rel}")
            old.unlink()
            return
        new.parent.mkdir(parents=True, exist_ok=True)
        old.rename(new)


def _json_files(root: Path) -> list[Path]:
    if not root.is_dir():
        return []
    return [
        path for path in root.rglob("*")
        if path.is_file() and path.suffix.lower() == ".json"
    ]


def _validate_tree(root: Path) -> None:
    for path in [root, *root.rglob("*")]:
        if path.is_symlink():
            raise ValueError(f"Mod 包含不安全符号链接：{path.name}")
        if not path.is_file() and not path.is_dir():
            raise ValueError(f"Mod 包含非普通文件：{path.name}")


def _load_catalog(path: Path) -> tuple[dict[str, set[str]], set[str]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    required = {
        "profileId", "versionCode", "catalogVersion", "cards", "tagCodes",
        "tagIds", "tagNames", "rite", "event", "over", "loot",
        "riteTemplate", "riteTemplateMappings",
    }
    missing = sorted(required - data.keys())
    if missing:
        raise ValueError(f"catalog 缺少必需字段：{', '.join(missing)}")
    if not isinstance(data["versionCode"], int) or data["versionCode"] <= 0:
        raise ValueError("catalog versionCode 无效")
    if not isinstance(data["catalogVersion"], str) or not data["catalogVersion"]:
        raise ValueError("catalog catalogVersion 无效")
    collections = {
        key: data[key] for key in (
            "cards", "tagCodes", "tagIds", "tagNames", "rite", "event",
            "over", "loot", "riteTemplate", "riteTemplateMappings",
        )
    }
    if any(not isinstance(value, list) for value in collections.values()):
        raise ValueError("catalog 集合字段必须是数组")
    if not any(collections.values()):
        raise ValueError("catalog 为空，禁止合并")
    return ({
        "cards": set(data["cards"]),
        "tag": set(data["tagCodes"]),
        "tag_id": {str(value) for value in data["tagIds"]},
        "rite": set(data["rite"]),
        "event": set(data["event"]),
        "over": set(data["over"]),
        "loot": set(data["loot"]),
        "rite_template": set(data["riteTemplate"]),
        "rite_template_mappings": set(data["riteTemplateMappings"]),
    }, set(data["tagNames"]))


def _table_json(table: RemapTable) -> dict[str, Any]:
    return {
        "cards": table.cards,
        "tag_codes": table.tag_codes,
        "tag_ids": {str(k): v for k, v in table.tag_ids.items()},
        "tag_names": table.tag_names,
        "tag_name_mapping": table.tag_name_mapping,
        "rite": table.rite,
        "event": table.event,
        "over": table.over,
        "loot": table.loot,
        "rite_template": table.rite_template,
        "rite_template_mappings": table.rite_template_mappings,
    }


def _remap_resources(store: DirectoryStore, mod_id: str, table: RemapTable) -> None:
    root = store._root(mod_id)
    for path in sorted(root.rglob("*"), key=lambda p: len(p.parts), reverse=True):
        if not path.is_file() or path.is_symlink() or path.suffix.lower() == ".json":
            continue
        relative = path.relative_to(root).as_posix()
        mapped = compute_resource_rename(relative, table)
        if mapped != relative:
            store.rename_resource(mod_id, relative, mapped)


def _is_ancestor(parent: Path, child: Path) -> bool:
    try:
        child.relative_to(parent)
        return True
    except ValueError:
        return False


def _warnings(
    conflicts: dict[str, dict[str, list[int]]],
    tag_conflicts: dict[str, list[tuple[int, str]]],
) -> list[dict[str, Any]]:
    warnings: list[dict[str, Any]] = []
    for entity_type, values in sorted(conflicts.items()):
        warnings.append({
            "code": "id_conflict",
            "severity": "warning",
            "message": (
                f"检测到 {entity_type} ID 冲突，已继续尝试重映射；"
                "部分引用可能无法完全对应。"
            ),
            "entity_type": entity_type,
            "count": len(values),
        })
    if tag_conflicts:
        warnings.append({
            "code": "tag_name_conflict",
            "severity": "warning",
            "message": "检测到 tag name 冲突，已继续尝试重映射；部分引用可能无法完全对应。",
            "entity_type": "tag_name",
            "count": len(tag_conflicts),
        })
    return warnings


def _merge_output(
    store: DirectoryStore,
    roots: list[Path],
    output: Path,
    display_name: str,
) -> tuple[list[dict[str, Any]], int]:
    warnings: list[dict[str, Any]] = [
        *store.layout_warnings,
        *store.invalid_json,
    ]
    json_inputs: dict[str, list[str]] = {}
    resources: dict[str, Path] = {}
    for index, root in enumerate(roots):
        mod_id = str(index)
        uses_config = store._uses_config(mod_id)
        for logical in store.mod_files(mod_id):
            source = store._physical(mod_id, logical)
            relative = f"config/{logical}" if uses_config else logical
            try:
                text = source.read_text(encoding="utf-8")
                json_inputs.setdefault(relative, []).append(text)
            except Exception as error:
                store.record_invalid_json(mod_id, logical, error)
        for path in root.rglob("*"):
            if not path.is_file() or path.is_symlink():
                continue
            relative = path.relative_to(root).as_posix()
            if path.suffix.lower() == ".json":
                continue
            resources[relative] = path

    warnings = [*store.layout_warnings, *store.invalid_json]
    final = output.parent / f".{output.name}-final-{os.getpid()}"
    if final.exists():
        shutil.rmtree(final)
    try:
        final.mkdir(parents=True, exist_ok=False)
    except OSError as error:
        raise _path_error("创建合并输出临时目录", final, error) from error
    merged_count = 0
    try:
        for relative, source in resources.items():
            target = final / relative
            _copy_file_contents(source, target)
        for relative, texts in sorted(json_inputs.items()):
            valid_texts: list[str] = []
            for text in texts:
                try:
                    _overlay_text([text])
                    valid_texts.append(text)
                except Exception as error:
                    store.record_invalid_json("overlay", relative, error)
            if not valid_texts:
                continue
            try:
                merged = _overlay_text(
                    [valid_texts[-1]]
                    if relative.rsplit("/", 1)[-1].lower() == "sfx_config.json"
                    else valid_texts,
                )
            except Exception as error:
                store.record_invalid_json("overlay", relative, error)
                continue
            target = final / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(merged, encoding="utf-8")
            merged_count += 1
        info = {
            "name": display_name.strip() or "合并Mod - 自动生成",
            "description": "由 Mod 合并管理器自动生成（无本体 JSON 模式）。",
            "tags": ["Merged"],
            "version": hashlib.sha256(
                "\n".join(json_inputs).encode("utf-8")
            ).hexdigest()[:16],
            "synthetic": True,
            "merge_mode": "no-base-json-overlay",
        }
        (final / "Info.json").write_text(
            json.dumps(info, ensure_ascii=False, indent=4) + "\n",
            encoding="utf-8",
        )
        os.replace(final, output)
    except Exception:
        shutil.rmtree(final, ignore_errors=True)
        raise
    return warnings, merged_count


def run(input_roots: list[str], catalog_path: str, output_root: str) -> dict[str, Any]:
    if len(input_roots) < 2:
        raise ValueError("at least two Mod inputs are required")
    source_roots = [Path(value).resolve() for value in input_roots]
    if any(not root.is_dir() or root.is_symlink() for root in source_roots):
        raise ValueError("Mod input must be a real directory")
    for root in source_roots:
        _validate_tree(root)
    output = Path(output_root).resolve()
    if any(_is_ancestor(output, root) or _is_ancestor(root, output) for root in source_roots):
        raise ValueError("invalid worker output root: it overlaps an input directory")
    if output.exists():
        raise ValueError("worker output root already exists")
    try:
        output.parent.mkdir(parents=True, exist_ok=True)
        staging = Path(tempfile.mkdtemp(prefix=f".{output.name}-", dir=str(output.parent)))
    except OSError as error:
        raise _path_error("创建合并工作临时目录", output.parent, error) from error
    roots: list[Path] = []
    previous = DataManager._instance
    try:
        for index, source in enumerate(source_roots):
            target = staging / f"input-{index}"
            _copy_tree_without_metadata(source, target)
            _validate_tree(target)
            roots.append(target)
        base_ids, base_tag_names = _load_catalog(Path(catalog_path).resolve())
        store = DirectoryStore(roots)
        DataManager._instance = store
        configs = [(str(i), source_roots[i].name, roots[i]) for i in range(len(roots))]
        infos = [collect_mod_ids(str(i)) for i in range(len(roots))]
        conflicts, tag_conflicts = detect_conflicts(base_ids, base_tag_names, infos)
        tag_remap = _allocate_tag_name_remaps(tag_conflicts, base_tag_names, configs)
        remap = allocate_new_ids(conflicts, _collect_all_used_ids(base_ids, infos), len(roots))
        tables: dict[str, dict[str, Any]] = {}
        for index in range(len(roots)):
            table = build_remap_table(remap, index, infos[index])
            for (mod_index, code), name in tag_remap.items():
                if mod_index == index:
                    table.tag_names[code] = name
                    old = infos[index].tag_names.get(code, "")
                    if old:
                        table.tag_name_mapping[old] = name
            if not table.is_empty():
                apply_remap_to_store(str(index), table)
                _remap_resources(store, str(index), table)
                tables[str(index)] = _table_json(table)
        overlay_warnings, merged_count = _merge_output(
            store, roots, staging / "merged-output", "",
        )
        result = {
            "status": "ok",
            "best_effort": bool(conflicts or tag_conflicts),
            "warnings": [
                *_warnings(conflicts, tag_conflicts),
                *overlay_warnings,
            ],
            "conflicts": {kind: dict(values) for kind, values in conflicts.items()},
            "tag_name_conflicts": {
                name: [[i, code] for i, code in entries]
                for name, entries in tag_conflicts.items()
            },
            "remap_tables": tables,
            "roots": [f"input-{i}" for i in range(len(roots))],
            "merged_output": "merged-output",
            "merged_entries": merged_count,
        }
        DataManager._instance = previous
        os.replace(staging, output)
        return result
    except Exception:
        shutil.rmtree(staging, ignore_errors=True)
        raise
    finally:
        DataManager._instance = previous


def run_json(request: str) -> str:
    value = json.loads(request)
    return json.dumps(run(value["input_roots"], value["catalog_path"], value["output_root"]), ensure_ascii=False, sort_keys=True)
