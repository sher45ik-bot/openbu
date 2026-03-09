#!/usr/bin/env python3
"""
Generate filament_catalog.json from BBL filament profiles.

Walks the profile inheritance chain to resolve filament_type and
nozzle temperature ranges, then finds P1S-compatible printer-specific
profiles to get setting_id values.

Usage:
    python3 scripts/generate_filament_catalog.py
"""

import json
import os
import sys

PROFILES_DIR = os.path.join(os.path.dirname(__file__), '..', 'profiles', 'BBL', 'filament')


def load_json(path):
    with open(path) as f:
        return json.load(f)


def resolve_field(profiles, profile_name, field):
    """Walk inheritance chain to resolve a field value."""
    visited = set()
    name = profile_name
    while name and name not in visited:
        visited.add(name)
        p = profiles.get(name)
        if p is None:
            break
        if field in p:
            val = p[field]
            if isinstance(val, list) and len(val) > 0:
                return val[0]
            return val
        name = p.get('inherits', '')
    return None


def main():
    profiles = {}

    # Load all JSON profiles
    for fname in os.listdir(PROFILES_DIR):
        if not fname.endswith('.json'):
            continue
        path = os.path.join(PROFILES_DIR, fname)
        try:
            data = load_json(path)
        except Exception as e:
            print(f"Warning: failed to load {fname}: {e}", file=sys.stderr)
            continue
        name = data.get('name', fname.replace('.json', ''))
        profiles[name] = data

    # Find @base profiles (these have filament_id)
    base_profiles = {}
    for name, data in profiles.items():
        if '@base' in name and 'filament_id' in data:
            base_profiles[name] = data

    # For each base profile, find P1S-compatible printer-specific profiles
    catalog = []
    for base_name, base_data in sorted(base_profiles.items()):
        filament_id = base_data['filament_id']
        filament_type = resolve_field(profiles, base_name, 'filament_type')
        temp_min = resolve_field(profiles, base_name, 'nozzle_temperature_range_low')
        temp_max = resolve_field(profiles, base_name, 'nozzle_temperature_range_high')

        if not filament_type or not temp_min or not temp_max:
            print(f"Warning: skipping {base_name} - missing type/temp data", file=sys.stderr)
            continue

        # Find printer-specific profiles that inherit from this base
        # and are compatible with P1S
        setting_id = None
        for name, data in profiles.items():
            if data.get('inherits') != base_name:
                continue
            if 'setting_id' not in data:
                continue
            compatible = data.get('compatible_printers', [])
            is_p1s = any('P1S' in p or 'P1P' in p for p in compatible)
            # Some profiles are for X1C but also list P1S compatibility
            if is_p1s or not compatible:
                setting_id = data['setting_id']
                break

        # If no P1S-specific profile, try to find any setting_id
        if not setting_id:
            for name, data in profiles.items():
                if data.get('inherits') != base_name:
                    continue
                if 'setting_id' in data:
                    setting_id = data['setting_id']
                    break

        if not setting_id:
            print(f"Warning: no setting_id found for {base_name}", file=sys.stderr)
            continue

        display_name = base_name.replace(' @base', '')

        catalog.append({
            'filament_id': filament_id,
            'name': display_name,
            'type': filament_type,
            'nozzle_temp_min': int(temp_min),
            'nozzle_temp_max': int(temp_max),
            'setting_id': setting_id,
        })

    # Sort by type then name
    catalog.sort(key=lambda x: (x['type'], x['name']))

    output_path = os.path.join(os.path.dirname(__file__), '..',
                               'app', 'src', 'main', 'assets', 'filament_catalog.json')
    with open(output_path, 'w') as f:
        json.dump(catalog, f, indent=2)

    print(f"Generated {len(catalog)} filament entries -> {output_path}")


if __name__ == '__main__':
    main()
