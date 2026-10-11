#!/usr/bin/env python3
"""Create credentials for authenticated development-only M1 probes."""
import os
from pathlib import Path
import secrets

root = Path(__file__).resolve().parents[1]
path = root / 'secrets/dev/m1.env'
if path.exists():
    print('Existing M1 development credentials retained.')
else:
    path.parent.mkdir(parents=True, mode=0o700, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as file:
        file.write("M1_SMOKE_USERNAME='m1_dev'\n")
        file.write(f"M1_SMOKE_PASSWORD='{secrets.token_hex(24)}'\n")
    print('M1 development credentials saved in secrets/dev/m1.env; not printed.')
