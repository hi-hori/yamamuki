"""Prepare iOS bundle resources from the verified Android terrain pack (stdlib only)."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import zipfile

ROOT=Path(__file__).resolve().parent.parent


def prepare(source,output):
    expected=source.with_suffix('.sha256').read_text().strip()
    digest=hashlib.sha256()
    with source.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''): digest.update(chunk)
    if digest.hexdigest()!=expected: raise ValueError('Terrain SHA-256 mismatch; fetch Git LFS assets first')
    output.mkdir(parents=True,exist_ok=True)
    # Clear only obsolete known resource files, never arbitrary supplied directories.
    with zipfile.ZipFile(source) as archive:
        manifest=json.loads(archive.read('manifest.json'))
        terrain=manifest['terrain']
        assert terrain['encoding']=='hillshade-webp' and terrain['tile_size']==512
        assert terrain['min_zoom']==10 and terrain['max_zoom']==12
        assert terrain['rivers_baked_in'] is False
        selected={name:name.removeprefix('terrain/') for name in archive.namelist()
                  if re.fullmatch(r'terrain/(10|11|12)/\d+/\d+\.webp',name)}
        assert len(selected)==terrain['tile_count']
        vectors={name:name for name in archive.namelist()
                 if re.fullmatch(r'rivers/(10|11|12)/\d+/\d+\.riv',name)}
        assert manifest['river_vectors']['encoding']=='RIV1'
        assert len(vectors)==manifest['river_vectors']['tile_count']
        selected.update(vectors)
        waters={name:name for name in archive.namelist()
                if re.fullmatch(r'water/(10|11|12)/\d+/\d+\.wat',name)}
        if 'water_polygons' in manifest:
            assert manifest['water_polygons']['encoding']=='WAT1'
            assert len(waters)==manifest['water_polygons']['tile_count']
            selected.update(waters)
            selected['water.geojson.gz']='water.geojson.gz'
        seas={name:name for name in archive.namelist()
              if re.fullmatch(r'sea/(10|11|12)/\d+/\d+\.wat',name)}
        if 'sea_tiles' in manifest:
            assert manifest['sea_tiles']['encoding']=='WAT1'
            assert manifest['sea_tiles']['extent']==4096
            assert len(seas)==manifest['sea_tiles']['tile_count']==terrain['tile_count']
            selected.update(seas)
        # Include OSM river source and licenses to keep the derived dataset available.
        for name in ('manifest.json','NOTICE.txt','ODbL-1.0.txt','rivers.geojson.gz'):
            selected[name]=name
        destinations=set(selected.values())
        if 'water.geojson.gz' not in destinations:
            (output/'water.geojson.gz').unlink(missing_ok=True)
        for old in output.glob('*/*/*.webp'):
            if old.relative_to(output).as_posix() not in destinations: old.unlink()
        for old in output.glob('rivers/*/*/*.riv'):
            if old.relative_to(output).as_posix() not in destinations: old.unlink()
        for old in output.glob('sea/*/*/*.wat'):
            if old.relative_to(output).as_posix() not in destinations: old.unlink()
        for old in output.glob('sea/*/*/*.png'):
            if old.relative_to(output).as_posix() not in destinations: old.unlink()
        for old in output.glob('water/*/*/*.wat'):
            if old.relative_to(output).as_posix() not in destinations: old.unlink()
        for name,destination in selected.items():
            path=output/destination
            path.parent.mkdir(parents=True,exist_ok=True)
            with archive.open(name) as src,path.open('wb') as dst: shutil.copyfileobj(src,dst)
    with zipfile.ZipFile(output/'rivers-ODbL.zip','w',compression=zipfile.ZIP_DEFLATED) as target:
        for name in ('rivers.geojson.gz','NOTICE.txt','ODbL-1.0.txt','manifest.json'):
            target.write(output/name,arcname=name)
        if (output/'water.geojson.gz').exists():
            target.write(output/'water.geojson.gz',arcname='water.geojson.gz')
    (output/'source.sha256').write_text(expected+'\n')
    print(f'Prepared {terrain["tile_count"]} 512px terrain tiles in {output}')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source',type=Path,default=ROOT/'app/src/main/assets/offline/terrain.zip')
    p.add_argument('--output',type=Path,default=ROOT/'ios/Generated/Terrain')
    args=p.parse_args()
    prepare(args.source,args.output)
