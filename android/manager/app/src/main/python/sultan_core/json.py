"""Host-test stub: the Android wheel provides the real implementation."""

class JsonDoc:
    @staticmethod
    def parse_file(path: str):
        raise RuntimeError("sultan_core Android wheel is required for JSON operations")
