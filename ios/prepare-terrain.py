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
        selected={name:name.removeprefix('terrain/') for name in archive.namelist()
                  if re.fullmatch(r'terrain/(10|11|12)/\d+/\d+\.webp',name)}
        assert len(selected)==terrain['tile_count']
        # Include OSM river source and licenses to keep the derived dataset available.
        for name in ('manifest.json','NOTICE.txt','ODbL-1.0.txt','rivers.geojson.gz'):
            selected[name]=name
        destinations=set(selected.values())
        for old in output.glob('*/*/*.webp'):
            if old.relative_to(output).as_posix() not in destinations: old.unlink()
        for name,destination in selected.items():
            path=output/destination
            path.parent.mkdir(parents=True,exist_ok=True)
            with archive.open(name) as src,path.open('wb') as dst: shutil.copyfileobj(src,dst)
    with zipfile.ZipFile(output/'rivers-ODbL.zip','w',compression=zipfile.ZIP_DEFLATED) as target:
        for name in ('rivers.geojson.gz','NOTICE.txt','ODbL-1.0.txt','manifest.json'):
            target.write(output/name,arcname=name)
    (output/'source.sha256').write_text(expected+'\n')
    print(f'Prepared {terrain["tile_count"]} 512px terrain tiles in {output}')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source',type=Path,default=ROOT/'app/src/main/assets/offline/terrain.zip')
    p.add_argument('--output',type=Path,default=ROOT/'ios/Generated/Terrain')
    args=p.parse_args()
    prepare(args.source,args.output)
