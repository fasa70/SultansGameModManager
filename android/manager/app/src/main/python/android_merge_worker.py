"""Lightweight Android entry point for ID remapping.

Chaquopy can invoke :func:`run_json` from Kotlin without importing any desktop
service. The worker copies its inputs before changing files and returns a
JSON-serializable result containing conflicts and remap tables.
"""

from android_merge_id_remap import run_json

__all__ = ["run_json"]
