"""Preserve OSM inland-water polygons, including island holes, as WAT1 tiles."""
import argparse
import gzip
import json
import math
from pathlib import Path
import struct
import time
import urllib.error
import urllib.parse
import zipfile
from concurrent.futures import ThreadPoolExecutor
import shapely
from shapely.geometry import Polygon, LineString, box, mapping, shape
from shapely.ops import polygonize_full, unary_union, transform
import build_data as b

BANDS = [(20,30),(30,32),(32,34),(34,35),(35,36),(36,38),(38,40),(40,42),(42,44),(44,46)]
SERVERS = ['https://overpass-api.de/api/interpreter', 'https://overpass.private.coffee/api/interpreter']


def request_json(inputs, name, query, server):
    for attempt in range(3):
        try:
            raw=inputs.get(name,server+'?data='+urllib.parse.quote(query),timeout=40,attempts=1)
            data=json.loads(raw)
            if data.get('remark'): raise ValueError(data['remark'])
            return data
        except urllib.error.HTTPError as exc:
            if exc.code not in (429,406) or attempt==2: raise
            print('Water server busy; backing off for 30 seconds',flush=True)
            time.sleep(30)
    raise RuntimeError('Water query failed')


def country_boundary(cache, offline):
    inputs=b.Inputs(cache,offline)
    data=inputs.get('japan.poly','https://download.geofabrik.de/asia/japan.poly')
    coordinates=[]
    for line in data.decode().splitlines():
        values=line.split()
        if len(values)==2: coordinates.append(tuple(map(float,values)))
    return Polygon(coordinates),inputs.records


def fetch(cache, offline):
    boundary,boundary_records=country_boundary(cache,offline)
    def band(bounds):
        south,north=bounds
        inputs=b.Inputs(cache,offline)
        # Reuse complete snapshots before subdividing expensive regional queries.
        for prefix in ('','bbox-'):
            for server in SERVERS:
                name=f'water/{prefix}{south}-{north}-{urllib.parse.urlparse(server).hostname}.json'
                meta=cache/(name+'.json')
                if meta.exists():
                    record=json.loads(meta.read_text())
                    data=json.loads(inputs.get(name,record['url']))
                    if not data.get('remark'):
                        print(f'Water {south}..{north}: {len(data["elements"])} cached elements',flush=True)
                        return data['elements'],inputs.records
        if north-south > .5:
            found=[]; records={}
            start=south
            while start<north:
                end=min(start+.5,north)
                items,meta=band((start,end)); found.extend(items); records.update(meta)
                start=end
            return found,records
        west,_,east,_=boundary.intersection(box(120,south,155,north)).bounds
        query=f'[out:json][timeout:25][maxsize:67108864];('
        for tag,value in [('natural','water'),('waterway','riverbank'),('landuse','reservoir')]:
            for kind in ('way','rel'):
                query+=f'{kind}["{tag}"="{value}"]({south},{west},{north},{east});'
        query+=');out tags geom;'
        errors=[]
        for server in SERVERS:
            name=f'water/bbox-{south}-{north}-{urllib.parse.urlparse(server).hostname}.json'
            try:
                data=request_json(inputs,name,query,server)
                if data.get('remark'): raise ValueError(data['remark'])
                print(f'Water {south}..{north}: {len(data["elements"])} elements',flush=True)
                return data['elements'],inputs.records
            except Exception as exc:
                errors.append(str(exc)); print(f"Water fetch retry: {server}: {exc}",flush=True)
        raise RuntimeError(f'Water {south}..{north} failed: {errors}')
    elements={}; records=dict(boundary_records)
    with ThreadPoolExecutor(max_workers=1) as pool:
        for found,meta in pool.map(band,BANDS):
            for item in found: elements[(item['type'],item['id'])]=item
            records.update(meta)
    ids=sorted(e['id'] for e in elements.values() if e['type']=='relation')
    def complete(chunk):
        query='[out:json][timeout:25][maxsize:67108864];rel(id:'+','.join(map(str,chunk))+');out body geom;'
        inputs=b.Inputs(cache,offline); errors=[]
        for server in SERVERS:
            name=f'water/relations-{b.sha(query.encode())[:16]}-{urllib.parse.urlparse(server).hostname}.json'
            try:
                data=request_json(inputs,name,query,server)
                if data.get('remark'): raise ValueError(data['remark'])
                found={e['id'] for e in data['elements'] if 'members' in e}
                if found!=set(chunk): raise ValueError('Missing relation body')
                return data['elements'],inputs.records
            except Exception as exc:
                errors.append(str(exc)); print(f"Water fetch retry: {server}: {exc}",flush=True)
        raise RuntimeError(f'Relation geometry failed: {errors}')
    chunks=[ids[i:i+256] for i in range(0,len(ids),256)]
    with ThreadPoolExecutor(max_workers=1) as pool:
        for i,(found,meta) in enumerate(pool.map(complete,chunks)):
            for item in found: elements[(item['type'],item['id'])]=item
            records.update(meta)
            print(f'Water relation geometry: {i+1}/{len(chunks)}',flush=True)
    return list(elements.values()),records,boundary


def polygons(geometry):
    if geometry.geom_type == 'Polygon':
        if not geometry.is_empty: yield geometry
    elif hasattr(geometry,'geoms'):
        for part in geometry.geoms: yield from polygons(part)


def assemble(elements, boundary=None):
    """Resolve multipolygon roles before simplification; never fill island holes."""
    features=[]; used=set(); skipped=[]
    ordered=sorted(elements,key=lambda e:(e['type']!='relation',e['id']))
    for item in ordered:
        tags=item.get('tags',{})
        if tags.get('tunnel')=='yes' or tags.get('covered')=='yes': continue
        kind=1 if tags.get('water') in ('river','canal') or tags.get('waterway')=='riverbank' else 0
        try:
            if item['type']=='relation':
                if not item.get('members'): raise ValueError('Missing relation members')
                rings={'outer':[],'inner':[]}
                members=[]
                for member in item.get('members',[]):
                    role=member.get('role') or 'outer'
                    if role not in rings: continue
                    if member['type']!='way' or not member.get('geometry'):
                        raise ValueError('Missing/nested multipolygon member geometry')
                    points=[(p['lon'],p['lat']) for p in member['geometry']]
                    if len(points)<2: raise ValueError('Incomplete member')
                    rings[role].append(LineString(points)); members.append(member['ref'])
                areas={}
                for role,lines in rings.items():
                    if not lines: areas[role]=[]; continue
                    parts,cuts,dangles,invalid=polygonize_full(unary_union(lines))
                    if parts.is_empty or not cuts.is_empty or not dangles.is_empty or not invalid.is_empty:
                        raise ValueError(f'Unclosed/invalid {role} ring')
                    areas[role]=[Polygon(p.exterior) for p in parts.geoms]
                shells=areas['outer']
                holes=[[] for _ in shells]
                for hole in areas['inner']:
                    owners=[i for i,shell in enumerate(shells) if shell.covers(hole)]
                    if not owners: raise ValueError('Island outside outer ring')
                    holes[min(owners,key=lambda i:shells[i].area)].append(hole)
                geometry=unary_union([shell.difference(unary_union(holes[i])) for i,shell in enumerate(shells)])
                used.update(members)
            else:
                if item['id'] in used: continue
                points=[(p['lon'],p['lat']) for p in item.get('geometry',[])]
                if len(points)<4 or points[0]!=points[-1]: raise ValueError('Unclosed way')
                geometry=Polygon(points)
            if not geometry.is_valid: geometry=shapely.make_valid(geometry)
            for part in polygons(geometry):
                if boundary is not None and not boundary.covers(part.representative_point()): continue
                features.append(dict(type='Feature',properties=dict(osm_type=item['type'],osm_id=item['id'],
                    name=tags.get('name',''),kind=kind),geometry=mapping(part)))
        except (ValueError,KeyError) as exc:
            skipped.append(dict(type=item['type'],id=item['id'],reason=str(exc)))
    return features,skipped


def tiles(features):
    for zoom in (10,11,12):
        index={}
        for feature in features:
            # Simplify complete polygons first so tile-boundary crossings coincide.
            geometry=transform(lambda x,y,z=None: b.xy(x,y,zoom),shape(feature['geometry']))
            geometry=geometry.simplify({10:1.,11:.5,12:.2}[zoom]/512,preserve_topology=True)
            if geometry.area*512*512 < {10:4.,11:2.,12:.5}[zoom]: continue
            west,north,east,south=geometry.bounds
            for y in range(math.floor(north),math.floor(south)+1):
                for x in range(math.floor(west),math.floor(east)+1):
                    for part in polygons(geometry.intersection(box(x,y,x+1,y+1))):
                        index.setdefault((x,y),{}).setdefault(feature['properties']['kind'],[]).append(part)
        for (x,y),groups in sorted(index.items()):
            areas=[]
            for kind,parts in sorted(groups.items()):
                # Merge overlapping source polygons before even-odd batch rendering.
                for part in polygons(unary_union(parts)):
                    rings=[]
                    for ring_index,ring in enumerate([part.exterior,*part.interiors]):
                        points=[]
                        for px,py in ring.coords:
                            p=(max(0,min(4096,round((px-x)*4096))),max(0,min(4096,round((py-y)*4096))))
                            if not points or p!=points[-1]: points.append(p)
                        if len(points)>1 and points[-1]==points[0]: points.pop()
                        if len(set(points))>=3: rings.append(points)
                        elif ring_index == 0: break
                    if rings: areas.append((kind,rings))
            if not areas: continue
            data=bytearray(b'WAT1'+struct.pack('<I',len(areas)))
            for kind,rings in areas:
                data.extend(struct.pack('<II',kind,len(rings)))
                for ring in rings:
                    data.extend(struct.pack('<I',len(ring)))
                    for point in ring: data.extend(struct.pack('<HH',*point))
            yield f'water/{zoom}/{x}/{y}.wat',bytes(data)


def build(args):
    elements,records,boundary=fetch(args.cache,args.offline)
    features,skipped=assemble(elements,boundary)
    if not features: raise ValueError('No water polygons')
    print(f'Assembled {len(features)} polygons; skipped {len(skipped)} invalid OSM objects',flush=True)
    vectors=list(tiles(features))
    geojson=gzip.compress(json.dumps(dict(type='FeatureCollection',features=features),ensure_ascii=False,separators=(',',':')).encode(),mtime=0)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    temporary=args.output.with_suffix('.water.tmp.zip')
    if b.sha(args.base.read_bytes())!=args.base.with_suffix('.sha256').read_text().strip(): raise ValueError('Base hash mismatch')
    with zipfile.ZipFile(args.base) as source,zipfile.ZipFile(temporary,'w') as target:
        manifest=json.loads(source.read('manifest.json'))
        manifest['water_polygons']=dict(encoding='WAT1',extent=4096,min_zoom=10,max_zoom=12,
            tile_count=len(vectors),feature_count=len(features),skipped_count=len(skipped),shapely=shapely.__version__)
        manifest['water_builder_sha256']=b.sha(Path(__file__).read_bytes())
        for name in source.namelist():
            if name in ('manifest.json','NOTICE.txt','water.geojson.gz','water-source-lock.json','water-skipped.json') or name.startswith('water/'): continue
            b.zip_add(target,name,source.read(name))
        b.zip_add(target,'manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2).encode())
        b.zip_add(target,'NOTICE.txt',(Path(__file__).parent/'NOTICE.txt').read_bytes())
        b.zip_add(target,'water.geojson.gz',geojson)
        b.zip_add(target,'water-source-lock.json',json.dumps(records,sort_keys=True,indent=2).encode())
        b.zip_add(target,'water-skipped.json',json.dumps(skipped,indent=2).encode())
        for name,data in vectors: b.zip_add(target,name,data)
    temporary.replace(args.output)
    args.output.with_suffix('.sha256').write_text(b.sha(args.output.read_bytes())+'\n',encoding='ascii')
    print(f'Created {args.output}: {args.output.stat().st_size:,} bytes',flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',type=Path,default=b.ROOT/'app/src/main/assets/offline/terrain.zip')
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--cache',type=Path,default=b.ROOT/'build/offline-data/sources')
    parser.add_argument('--offline',action='store_true')
    build(parser.parse_args())
