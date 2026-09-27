import hashlib, io, json, math, time, urllib.error, urllib.request, zipfile
from datetime import datetime, timezone
from pathlib import Path
import numpy as np
from PIL import Image
ROOT = Path(__file__).resolve().parents[2]
GSI = "https://cyberjapandata.gsi.go.jp/xyz/dem_png/{z}/{x}/{y}.png"
WATER_COLOR = (82, 145, 180)
RIVER_COLOR = (134, 187, 212)

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

def xy(lon, lat, z):
    return (lon+180)/360*2**z, (1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*2**z

def lonlat(x, y, z):
    return x/2**z*360-180, math.degrees(math.atan(math.sinh(math.pi*(1-2*y/2**z))))

def decode(data):
    rgb=np.array(Image.open(io.BytesIO(data)).convert("RGB"),dtype=np.int32)
    if rgb.shape != (256,256,3): raise ValueError("Invalid DEM dimensions")
    packed=rgb[:,:,0]*65536+rgb[:,:,1]*256+rgb[:,:,2]
    dem=np.where(packed>8388608,packed-16777216,packed).astype(np.float32)*.01
    dem[packed==8388608]=np.nan
    return dem

def river_tiles(features, zoom, size):
    tiles={}
    for feature in features:
        points=[xy(lon,lat,zoom) for lon,lat in feature['geometry']['coordinates']]
        xs,ys=zip(*points)
        # Include neighbouring tiles touched by the two-pixel stroke.
        for y in range(math.floor(min(ys)-1/size),math.floor(max(ys)+1/size)+1):
            for x in range(math.floor(min(xs)-1/size),math.floor(max(xs)+1/size)+1):
                tiles.setdefault((zoom,x,y),[]).append([((px-x)*size,(py-y)*size) for px,py in points])
    return tiles

def zip_add(archive,name,data):
    info=zipfile.ZipInfo(name,date_time=(1980,1,1,0,0,0))
    info.compress_type=zipfile.ZIP_STORED if name.endswith(('.webp','.gz')) else zipfile.ZIP_DEFLATED
    info.external_attr=0o644<<16
    archive.writestr(info,data)
