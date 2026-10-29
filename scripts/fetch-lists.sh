#!/usr/bin/env bash
# Merge bounded Block List Project snapshots with the bundled local seed entries.
set -euo pipefail
cd "$(dirname "$0")/.."
staging=$(mktemp -d)
trap 'rm -rf "$staging"' EXIT
for spec in adult:porn:60000 gambling:gambling:15000 ads:ads:20000; do
  IFS=: read -r category source limit <<< "$spec"
  url="https://raw.githubusercontent.com/blocklistproject/Lists/master/${source}.txt"
  curl --fail --location --proto '=https' --proto-redir '=https' --retry 2 \
    --connect-timeout 15 --max-time 180 --max-filesize 33554432 \
    "$url" -o "$staging/$category.raw"
  python3 - "$category" "$limit" "$url" "$staging" <<'PY'
import pathlib, re, sys
category, limit, url, stage = sys.argv[1:]
stage = pathlib.Path(stage)
domains = set()
for line in (stage / (category + '.raw')).read_text().splitlines():
    fields = line.split('#', 1)[0].split()
    if len(fields) < 2 or fields[0] not in ('0.0.0.0', '127.0.0.1'):
        continue
    for host in fields[1:]:
        host = host.lower().rstrip('.')
        if re.fullmatch(r'(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}', host):
            domains.add(host)
if len(domains) < 100:
    raise SystemExit('Too few domains: ' + category)
# Stable, explicitly bounded sample. Keep seed entries when regenerating.
selected = set(sorted(domains)[:int(limit)])
asset = pathlib.Path('app/src/main/assets/categories') / (category + '.txt')
for line in asset.read_text().splitlines():
    parts = line.split('#', 1)[0].split()
    if parts and not line.startswith('# Source:'):
        host = parts[-1].lower()
        if re.fullmatch(r'(?:[a-z0-9-]+\.)+[a-z]{2,63}', host):
            selected.add(host)
text = f'# Source: {url}\n# License: Unlicense — https://github.com/blocklistproject/Lists/blob/master/LICENSE\n# Bounded snapshot; sorted and deduplicated.\n'
text += ''.join('0.0.0.0 ' + host + '\n' for host in sorted(selected))
(stage / (category + '.txt')).write_text(text)
print(f'{category}: {len(selected)} domains, {len(text.encode())} bytes')
PY
done
python3 - "$staging" <<'PY'
import pathlib, sys
stage = pathlib.Path(sys.argv[1])
assets = pathlib.Path('app/src/main/assets/categories')
total = sum(p.stat().st_size for p in stage.glob('*.txt')) + (assets / 'malware.txt').stat().st_size
if total >= 5_000_000:
    raise SystemExit(f'Asset budget exceeded: {total}')
for path in stage.glob('*.txt'):
    (assets / path.name).write_bytes(path.read_bytes())
print(f'Total categories: {total} bytes (budget 5 MB)')
PY
