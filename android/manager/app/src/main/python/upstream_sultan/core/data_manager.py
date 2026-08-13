"""Minimal DataManager injection point used by the copied upstream module."""


class DataManager:
    _instance = None

    @classmethod
    def instance(cls):
        if cls._instance is None:
            raise RuntimeError("Android merge store has not been installed")
        return cls._instance


__all__ = ["DataManager"]
