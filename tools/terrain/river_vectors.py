"""RIV1 vector tiles: LE uint32 line count, then uint32 count + uint16 x/y.

Coordinates span 0..4096 in a Web Mercator tile. Lines are simplified before
clipping, so neighbouring tiles share their boundary intersections.
"""
import math
import struct
from pathlib import Path
import build_data as b

MIN_LENGTH_KM = {10: 20., 11: 5., 12: 0.}
TOLERANCE_PIXELS = {10: 2., 11: 1., 12: .3}


def component_lengths(features):
    """Length of connected, equally named sections; never merge by name alone.

    OSM ways split one river into many short sections. Match their endpoints
    within the source's coordinate precision (~1m), including reversed ways.
    Anonymous sections are kept separate rather than joining all tributaries.
    This is a cartographic heuristic, not a statutory river classification.
    """
    parents = list(range(len(features)))
    def root(i):
        while parents[i] != i:
            parents[i] = parents[parents[i]]
            i = parents[i]
        return i
    endpoints, lengths = {}, []
    for i, feature in enumerate(features):
        points = feature['geometry']['coordinates']
        name = (feature.get('properties', {}).get('name') or '').strip()
        if name and len(points) >= 2:
            for lon, lat in (points[0], points[-1]):
                key = name, round(lon, 5), round(lat, 5)
                if key in endpoints: parents[root(i)] = root(endpoints[key])
                else: endpoints[key] = i
        length = 0.
        for (lon1,lat1),(lon2,lat2) in zip(points,points[1:]):
            # Local equirectangular distance is sufficient for ranking short segments.
            x = math.radians(lon2-lon1)*math.cos(math.radians((lat1+lat2)/2))
            y = math.radians(lat2-lat1)
            length += 6371.0088*math.hypot(x,y)
        lengths.append(length)
    totals = {}
    for i,length in enumerate(lengths): totals[root(i)] = totals.get(root(i),0.)+length
    return [totals[root(i)] for i in range(len(features))]


def metadata(tile_count):
    return dict(encoding='RIV1',extent=4096,min_zoom=10,max_zoom=12,tile_count=tile_count,
                min_connected_length_km=MIN_LENGTH_KM,simplify_pixels=TOLERANCE_PIXELS,
                priority='connected-same-name-length-v1')


def simplify(points, tolerance=.3):
    keep = {0, len(points)-1}
    pending = [(0, len(points)-1)]
    while pending:
        first, last = pending.pop()
        ax, ay = points[first]; bx, by = points[last]
        dx, dy = bx-ax, by-ay
        length = dx*dx+dy*dy
        best, index = tolerance*tolerance, None
        for i in range(first+1, last):
            x, y = points[i]
            t = max(0, min(1, ((x-ax)*dx+(y-ay)*dy)/length)) if length else 0
            distance = (x-ax-t*dx)**2+(y-ay-t*dy)**2
            if distance > best: best, index = distance, i
        if index is not None:
            keep.add(index); pending.extend(((first,index),(index,last)))
    return [points[i] for i in sorted(keep)]


def clip_segment(a, b):
    dx, dy = b[0]-a[0], b[1]-a[1]
    low, high = 0., 1.
    for p, q in ((-dx,a[0]),(dx,512-a[0]),(-dy,a[1]),(dy,512-a[1])):
        if p == 0:
            if q < 0: return None
        elif p < 0: low = max(low,q/p)
        else: high = min(high,q/p)
        if low > high: return None
    if low == high: return None
    return [(a[0]+t*dx,a[1]+t*dy) for t in (low,high)]


def clipped_lines(points):
    current = []
    for a, b in zip(points,points[1:]):
        segment = clip_segment(a,b)
        if segment is None:
            if len(current)>1: yield current
            current=[]
            continue
        start, end = [tuple(max(0,min(4096,round(v*8))) for v in p) for p in segment]
        if start == end: continue
        if current and current[-1] != start:
            yield current; current=[]
        if not current: current=[start]
        current.append(end)
    if len(current)>1: yield current


def tiles(features):
    lengths = component_lengths(features)
    for zoom in (10,11,12):
        index = {}
        for feature,length in zip(features,lengths):
            if length < MIN_LENGTH_KM[zoom]: continue
            points = simplify([(x*512,y*512) for x,y in
                (b.xy(lon,lat,zoom) for lon,lat in feature['geometry']['coordinates'])],
                TOLERANCE_PIXELS[zoom])
            if len(points)<2: continue
            xs, ys = zip(*points)
            for y in range(math.floor(min(ys)/512),math.floor(max(ys)/512)+1):
                for x in range(math.floor(min(xs)/512),math.floor(max(xs)/512)+1):
                    lines=list(clipped_lines([(px-x*512,py-y*512) for px,py in points]))
                    if lines: index.setdefault((x,y),[]).extend(lines)
        for (x,y),lines in sorted(index.items()):
            data=bytearray(b'RIV1'+struct.pack('<I',len(lines)))
            for line in lines:
                data.extend(struct.pack('<I',len(line)))
                for point in line: data.extend(struct.pack('<HH',*point))
            yield f'rivers/{zoom}/{x}/{y}.riv',bytes(data)


def rebuild(base, output):
    """Update only vectors; keep hillshade bytes and their original provenance."""
    import gzip
    import json
    import zipfile
    if b.sha(base.read_bytes()) != base.with_suffix('.sha256').read_text().strip():
        raise ValueError('Base pack SHA-256 mismatch')
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix('.rivers.tmp.zip')
    with zipfile.ZipFile(base) as source:
        manifest = json.loads(source.read('manifest.json'))
        if manifest['terrain'].get('rivers_baked_in') is not False:
            raise ValueError('Rebuild hillshade first to remove baked-in rivers')
        features = json.loads(gzip.decompress(source.read('rivers.geojson.gz')))['features']
        vectors = list(tiles(features))
        manifest['river_vectors'] = metadata(len(vectors))
        manifest['river_builder_sha256'] = b.sha(Path(__file__).read_bytes())
        with zipfile.ZipFile(temporary, 'w') as target:
            for name in source.namelist():
                if name.startswith('rivers/') or name == 'manifest.json': continue
                data = (b.ROOT/'tools/terrain/NOTICE.txt').read_bytes() if name == 'NOTICE.txt' else source.read(name)
                b.zip_add(target,name,data)
            b.zip_add(target,'manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2).encode())
            for name,data in vectors: b.zip_add(target,name,data)
    temporary.replace(output)
    output.with_suffix('.sha256').write_text(b.sha(output.read_bytes())+'\n',encoding='ascii')
    print(f'Created {output}: {output.stat().st_size:,} bytes, {len(vectors)} vector tiles',flush=True)


if __name__ == '__main__':
    import argparse
    from pathlib import Path
    parser = argparse.ArgumentParser(description='Rebuild river vectors without regenerating hillshade')
    parser.add_argument('--base',type=Path,default=b.ROOT/'app/src/main/assets/offline/terrain.zip')
    parser.add_argument('--output',type=Path,required=True)
    args = parser.parse_args()
    rebuild(args.base,args.output)
