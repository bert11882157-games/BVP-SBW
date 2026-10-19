"""Authored cockpits: one module per aircraft type (build(ac, tub, sw, atlas) -> the cockpit_lib.build tuple)."""
import importlib


def module(vid):
    try:
        return importlib.import_module(f'authored.{vid}')
    except ModuleNotFoundError as e:
        if e.name == f'authored.{vid}':
            return None
        raise
