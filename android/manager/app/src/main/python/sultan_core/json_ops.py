"""Host-test stub: the Android wheel provides the real implementation."""

def _missing(*args, **kwargs):
    raise RuntimeError("sultan_core Android wheel is required for JSON operations")

extract_root_field_ints = _missing
extract_root_field_strs = _missing
extract_root_keys = _missing
remap_all_ints = _missing
remap_all_str_ids = _missing
replace_field_ints = _missing
replace_field_strs = _missing
replace_root_keys = _missing
