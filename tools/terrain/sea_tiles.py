"""Build sea polygons from the same cached DEM as the hillshade."""
import argparse
from functools import lru_cache
import struct
import contourpy
from shapely.geometry import Polygon, box
from water_polygons import polygons
import json
from pathlib import Path
import zipfile
import numpy as np
from scipy import ndimage as ndi
import build_data as b



def sea_polygons(field):
    valid = np.isfinite(field)
    if valid.all(): return []
    if not valid.any(): return [box(0, 0, 1, 1)]
    signed = ndi.distance_transform_edt(valid) - ndi.distance_transform_edt(~valid)
    # Same pixel centres and signed-distance coast as the shaded DEM.
    axis = (np.arange(field.shape[0]) + .5 - 8) / 256
    points, offsets = contourpy.contour_generator(x=axis, y=axis, z=signed,
        fill_type='OuterOffset').filled(-10000, 0)
    result = []
    for coords, starts in zip(points, offsets):
        rings = [coords[a:b] for a,b in zip(starts[:-1], starts[1:])]
        area = Polygon(rings[0], rings[1:])
        # Simplify before clipping to keep neighbouring tile crossings identical.
        area = area.simplify(.15/512, preserve_topology=True).intersection(box(0,0,1,1))
        result.extend(polygons(area))
    return result


def encode(areas):
    encoded = []
    for area in areas:
        rings = []
        for i, ring in enumerate([area.exterior, *area.interiors]):
            points = []
            for x,y in ring.coords:
                point = (max(0,min(4096,round(x*4096))), max(0,min(4096,round(y*4096))))
                if not points or point != points[-1]: points.append(point)
            if len(points)>1 and points[-1]==points[0]: points.pop()
            if len(set(points))>=3: rings.append(points)
            elif i==0: break
        if rings: encoded.append(rings)
    data = bytearray(b'WAT1'+struct.pack('<I',len(encoded)))
    for rings in encoded:
        data.extend(struct.pack('<II',1,len(rings)))
        for ring in rings:
            data.extend(struct.pack('<I',len(ring)))
            for point in ring: data.extend(struct.pack('<HH',*point))
    return bytes(data)


def build(args):
    inputs = b.Inputs(args.cache, args.offline)
    @lru_cache(maxsize=96)
    def dem(z, x, y):
        data = inputs.get(f'dem/{z}/{x}/{y}.png', b.GSI.format(z=z, x=x, y=y), allow_missing=True)
        return b.decode(data) if data is not None else np.full((256, 256), np.nan)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix('.sea.tmp.zip')
    assert b.sha(args.base.read_bytes()) == args.base.with_suffix('.sha256').read_text().strip()
    with zipfile.ZipFile(args.base) as source, zipfile.ZipFile(temporary, 'w') as target:
        names = sorted(n for n in source.namelist() if n.startswith('terrain/') and n.endswith('.webp'))
        for i, name in enumerate(names):
            z, x, y = map(int, name.removeprefix('terrain/').removesuffix('.webp').split('/'))
            field = np.full((272, 272), np.nan)
            for dy in (-1, 0, 1):
                for dx in (-1, 0, 1):
                    yy, xx = 8 + dy*256, 8 + dx*256
                    top, left, bottom, right = max(0, yy), max(0, xx), min(272, yy+256), min(272, xx+256)
                    field[top:bottom, left:right] = dem(z, x+dx, y+dy)[top-yy:bottom-yy, left-xx:right-xx]
            b.zip_add(target, f'sea/{z}/{x}/{y}.wat', encode(sea_polygons(field)))
            if (i+1) % 1000 == 0: print(f'Sea tiles: {i+1}/{len(names)}', flush=True)
        manifest = json.loads(source.read('manifest.json'))
        manifest['sea_tiles'] = dict(encoding='WAT1', extent=4096, tile_count=len(names), contourpy=contourpy.__version__)
        manifest['sea_builder_sha256'] = b.sha(Path(__file__).read_bytes())
        for name in source.namelist():
            if name not in ('manifest.json', 'sea-source-lock.json') and not name.startswith('sea/'): b.zip_add(target, name, source.read(name))
        b.zip_add(target, 'manifest.json', json.dumps(manifest, ensure_ascii=False, indent=2).encode())
        b.zip_add(target, 'sea-source-lock.json', json.dumps(inputs.records, sort_keys=True, indent=2).encode())
    temporary.replace(args.output)
    args.output.with_suffix('.sha256').write_bytes((b.sha(args.output.read_bytes())+'\r\n').encode())
    print(f'Created {args.output}: {args.output.stat().st_size} bytes', flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', type=Path, default=b.ROOT/'app/src/main/assets/offline/terrain.zip')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cache', type=Path, default=b.ROOT/'build/offline-data/sources')
    parser.add_argument('--offline', action='store_true')
    build(parser.parse_args())
