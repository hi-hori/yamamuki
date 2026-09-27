"""Rebuild the Japan peak-only pack from cached or downloaded OSM inputs (Python stdlib)."""
import argparse, csv, gzip, hashlib, io, json, math, re, time, urllib.error, urllib.parse, urllib.request, zipfile
from pathlib import Path
from datetime import datetime, timezone

OSM_QUERY = '[out:json][timeout:180];area["ISO3166-1"="JP"]["admin_level"="2"]->.j;node["natural"~"^(peak|volcano)$"](area.j);out body;'
ENDPOINTS = ["https://overpass-api.de/api/interpreter", "https://overpass.private.coffee/api/interpreter"]

def sha(data):
    return hashlib.sha256(data).hexdigest()

class Inputs:
    def __init__(self, path: Path, offline=False):
        self.path, self.offline = path, offline
        self.records = {}

    def get(self, name, url, allow_missing=False, timeout=30, attempts=3):
        path = self.path / name
        meta_path = path.with_suffix(path.suffix + ".json")
        if meta_path.exists():
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            if meta["url"] != url:
                raise ValueError(f"Cached URL changed: {name}; use a new --cache directory")
            data = path.read_bytes() if meta["status"] == 200 else None
            if data is not None and sha(data) != meta["sha256"]:
                raise ValueError(f"Corrupt cache: {path}")
            self.records[name] = meta
            return data
        if self.offline:
            raise FileNotFoundError(f"Uncached input in --offline mode: {name}")
        for attempt in range(attempts):
            try:
                req = urllib.request.Request(url, headers={"User-Agent": "yamamuki-offline-data-builder/1.0"})
                with urllib.request.urlopen(req, timeout=timeout) as response:
                    data = response.read()
                    modified = response.headers.get("Last-Modified")
                status = 200
                break
            except urllib.error.HTTPError as exc:
                if exc.code == 404 and allow_missing:
                    status, data, modified = 404, None, None
                    break
                if attempt == attempts-1:
                    raise
                time.sleep(2 ** attempt)
            except OSError:
                if attempt == attempts-1:
                    raise
                time.sleep(2 ** attempt)
        path.parent.mkdir(parents=True, exist_ok=True)
        if data is not None:
            temp = path.with_suffix(path.suffix + ".tmp")
            temp.write_bytes(data)
            temp.replace(path)
        meta = dict(url=url, status=status, sha256=sha(data) if data is not None else None,
                    bytes=len(data) if data is not None else 0, last_modified=modified,
                    fetched_at=datetime.now(timezone.utc).isoformat())
        meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
        self.records[name] = meta
        return data

def elevation(raw):
    text=str(raw or "").split(';')[0].strip().lower()
    m=re.fullmatch(r"(-?[\d,]*\.?\d+)\s*(m|meters?|metres?|ft|feet|')?",text)
    if not m: return ""
    value=float(m[1].replace(',',''))
    if m[2] in ('ft','feet',"'"): value*=.3048
    return format(value,'.6f').rstrip('0').rstrip('.')

def build_peaks(inputs):
    errors=[]
    for endpoint in ENDPOINTS:
        url=endpoint+'?data='+urllib.parse.quote(OSM_QUERY,safe='')
        try:
            data=inputs.get('osm/'+urllib.parse.urlparse(endpoint).hostname+'.json',url,timeout=210)
            source=json.loads(data)
            if 'remark' in source: raise ValueError(source['remark'])
            rows=[]
            seen=set()
            for obj in source['elements']:
                tags=obj.get('tags',{})
                name=str(tags.get('name:ja') or tags.get('name') or '').strip()
                if not name or obj['type']!='node' or tags.get('natural') not in ('peak','volcano'): continue
                if obj['id'] in seen: continue
                seen.add(obj['id'])
                rows.append([obj['id'],format(obj['lat'],'.7f'),format(obj['lon'],'.7f'),name,elevation(tags.get('ele'))])
            if len(rows)<10000: raise ValueError(f"Unexpectedly few named peaks: {len(rows)}")
            rows.sort(key=lambda r:r[0])
            stream=io.StringIO(newline='')
            writer=csv.writer(stream,lineterminator='\n')
            writer.writerow(['osm_id','latitude','longitude','name','elevation_m'])
            writer.writerows(rows)
            return gzip.compress(stream.getvalue().encode('utf-8'),compresslevel=9,mtime=0),dict(
                count=len(rows),osm_timestamp=source['osm3s']['timestamp_osm_base'],query=OSM_QUERY,source=endpoint)
        except Exception as exc:
            errors.append(f'{endpoint}: {exc}')
            print(errors[-1],flush=True)
    raise RuntimeError('\n'.join(errors))

def zip_add(archive,name,data):
    info=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0))
    info.compress_type=zipfile.ZIP_STORED if name.endswith(('.webp','.gz')) else zipfile.ZIP_DEFLATED
    info.external_attr=0o644<<16
    archive.writestr(info,data)


def build(cache, output, offline=False):
    inputs = Inputs(cache, offline)
    peaks, metadata = build_peaks(inputs)
    manifest = dict(format_version=1, peaks=metadata,
        input_last_fetched=max(r['fetched_at'] for r in inputs.records.values()),
        sources=inputs.records, builder_sha256=sha(Path(__file__).read_bytes()))
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = output.with_suffix('.tmp')
    with zipfile.ZipFile(temp, 'w') as archive:
        zip_add(archive, 'peaks.csv.gz', peaks)
        zip_add(archive, 'manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2).encode())
        for name in ['NOTICE.txt', 'ODbL-1.0.txt']:
            zip_add(archive, name, (Path(__file__).parent / name).read_bytes())
    temp.replace(output)
    output.with_suffix('.sha256').write_bytes((sha(output.read_bytes())+'\r\n').encode())
    print(f"{metadata['count']} peaks; {output.stat().st_size} bytes: {output}")

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, default=Path('build/peaks-inputs'))
    parser.add_argument('--output', type=Path, default=Path('app/src/main/assets/offline/peaks.zip'))
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    build(args.cache, args.output, args.offline)
