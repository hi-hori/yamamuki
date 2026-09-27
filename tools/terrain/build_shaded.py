"""Precompute high-resolution hillshade for fast cached rendering on Android."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from functools import lru_cache
import gzip
import io
import json
import math
from pathlib import Path
import zipfile
import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage as ndi
import build_data as b
import terrain_reconstruction as reconstruction


def shade(field, metres):
    """Return the central 256px, using a two-pixel halo to avoid tile seams."""
    mask=np.isfinite(field)
    if not mask.any(): return None
    nearest=ndi.distance_transform_edt(~mask,return_distances=False,return_indices=True)
    filled=field[tuple(nearest)]
    # A sub-pixel filter removes DEM noise without the former one-pixel blur.
    gy,gx=np.gradient(ndi.gaussian_filter(filled,.6),metres)
    gx*=1.5; gy*=1.5
    light=np.clip((-.5*gx+.5*gy+.707)/np.sqrt(gx*gx+gy*gy+1),0,1)
    colors=np.array([[232,239,227],[204,223,188],[167,193,143],[167,176,137],[215,208,183]])
    rgb=np.stack([np.interp(filled,[0,150,500,1000,1800],colors[:,i]) for i in range(3)],axis=-1)
    rgb=np.uint8(np.clip(rgb*(.58+.58*light[:,:,None]),0,255))
    rgb[~mask]=b.WATER_COLOR
    return Image.fromarray(rgb[2:-2,2:-2])


def render_image(key,dem,index):
    """Generate an uncompressed 512px tile including rivers, from source DEM."""
    z,x,y=key
    field=np.full((272,272),np.nan)
    for dy in (-1,0,1):
        for dx in (-1,0,1):
            yy,xx=8+dy*256,8+dx*256
            top,left=max(0,yy),max(0,xx)
            bottom,right=min(272,yy+256),min(272,xx+256)
            field[top:bottom,left:right]=dem((z,x+dx,y+dy))[top-yy:bottom-yy,left-xx:right-xx]
    _,lat=b.lonlat(x+.5,y+.5,z)
    metres=40075016.6856*math.cos(math.radians(lat))/(256*2**z)
    image=reconstruction.shade_refined(field,metres,2,8)
    if image is not None:
        draw=ImageDraw.Draw(image)
        for line in index.get(key,[]):
            draw.line([(px*2,py*2) for px,py in line],fill=b.RIVER_COLOR,width=4,joint='curve')
    return image


def build(args):
    with zipfile.ZipFile(args.base) as archive:
        names=archive.namelist()
        catalog=sorted({tuple(map(int,n.split('/')[1:3]+[n.split('/')[3].split('.')[0]]))
            for n in names if n.startswith(('terrain/','vector/')) and n.split('/')[1] in ('10','11','12')})
        assert len(catalog)>10000, 'A nationwide z12 terrain/vector pack is required'
        retained={n:archive.read(n) for n in names if not n.startswith(('terrain/','vector/'))}
    inputs=b.Inputs(args.cache,args.offline)
    needed=sorted({(z,x+dx,y+dy) for z,x,y in catalog for dx in (-1,0,1) for dy in (-1,0,1)})
    def fetch(tile):
        z,x,y=tile
        inputs.get(f'dem/{z}/{x}/{y}.png',b.GSI.format(z=z,x=x,y=y),allow_missing=True)
    with ThreadPoolExecutor(max_workers=4) as pool:
        for i,_ in enumerate(pool.map(fetch,needed)):
            if (i+1)%1000==0: print(f'DEM {i+1}/{len(needed)}',flush=True)
    @lru_cache(maxsize=96)
    def dem(tile):
        z,x,y=tile
        name=f'dem/{z}/{x}/{y}.png'
        return b.decode((args.cache/name).read_bytes()) if inputs.records[name]['status']==200 else np.full((256,256),np.nan)
    rivers=json.loads(gzip.decompress(retained['rivers.geojson.gz']))['features']
    work=args.cache.parent/'shaded-rendered'
    rendered=[]
    for zoom in (10,11,12):
        # Keep the geographic stroke margin at one source pixel. Indexing at
        # the enlarged size would omit rivers whose wider strokes cross a tile.
        index=b.river_tiles(rivers,zoom,256)
        tiles=[key for key in catalog if key[0]==zoom]
        def render(key):
            z,x,y=key
            image=render_image(key,dem,index)
            if image is None: return None
            path=work/f'{z}/{x}/{y}.webp'
            path.parent.mkdir(parents=True,exist_ok=True)
            image.save(path,format='WEBP',quality=args.quality,method=4)
            return key,path
        with ThreadPoolExecutor(max_workers=4) as pool:
            for i,result in enumerate(pool.map(render,tiles)):
                if result: rendered.append(result)
                if (i+1)%500==0: print(f'Hillshade z{zoom}: {i+1}/{len(tiles)}',flush=True)
    lock=json.loads(retained['source-lock.json']); lock.update(inputs.records)
    retained['source-lock.json']=json.dumps(dict(sorted(lock.items())),ensure_ascii=False,sort_keys=True,indent=2).encode()
    manifest=json.loads(retained['manifest.json'])
    manifest['terrain']=dict(encoding='hillshade-webp',min_zoom=10,max_zoom=12,tile_size=512,
        tile_count=len(rendered),webp_quality=args.quality,webp_lossless=False,source=b.GSI,
        source_grid_size=256,approx_source_metres_at_36N=30.9194,shade_exaggeration=1.5,
        water_color_rgb=b.WATER_COLOR,river_color_rgb=b.RIVER_COLOR)
    manifest['terrain']['refinement']=dict(zooms=[10,11,12],scale=2,
        algorithm='direction-weighted-pchip-relight-v1',normal_sigma_source_pixels=.4,
        halo_source_pixels=8,pixel_centres=True)
    manifest['terrain_reconstruction_sha256']=b.sha(Path(reconstruction.__file__).read_bytes())
    manifest.pop('vector_builder_sha256',None); manifest.pop('vector_encoder',None)
    manifest['dem_utils_sha256']=b.sha(Path(b.__file__).read_bytes())
    manifest['shaded_builder_sha256']=b.sha(Path(__file__).read_bytes())
    manifest['source_lock_sha256']=b.sha(retained['source-lock.json'])
    manifest['input_last_fetched']=max(r['fetched_at'] for r in lock.values())
    retained['manifest.json']=json.dumps(manifest,ensure_ascii=False,indent=2).encode()
    retained['NOTICE.txt']=(Path(__file__).parent/'NOTICE.txt').read_bytes()
    args.output.parent.mkdir(parents=True,exist_ok=True)
    temp=args.output.with_suffix('.shaded.tmp.zip')
    with zipfile.ZipFile(temp,'w') as archive:
        for n,data in sorted(retained.items()): b.zip_add(archive,n,data)
        for (z,x,y),path in sorted(rendered): b.zip_add(archive,f'terrain/{z}/{x}/{y}.webp',path.read_bytes())
    temp.replace(args.output)
    args.output.with_suffix('.sha256').write_text(b.sha(args.output.read_bytes())+'\n',encoding='ascii')
    print(f'Created {args.output}: {args.output.stat().st_size:,} bytes, {len(rendered)} tiles',flush=True)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base',type=Path,default=b.ROOT/'app/src/main/assets/offline/terrain.zip')
    parser.add_argument('--output',type=Path,default=b.ROOT/'app/src/main/assets/offline/terrain.zip')
    parser.add_argument('--cache',type=Path,default=b.ROOT/'build/offline-data/sources')
    parser.add_argument('--offline',action='store_true')
    parser.add_argument('--quality',type=int,choices=range(1,101),default=80)
    build(parser.parse_args())
