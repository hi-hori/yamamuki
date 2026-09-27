"""Terrain-aware elevation interpolation and hillshade rendering."""
import numpy as np
from PIL import Image
from scipy import ndimage as ndi
from scipy.interpolate import PchipInterpolator
import build_data as b


def reconstruct(field, scale, pixel_centres=False):
    """Blend two orderings of monotone cubic interpolation using slope orientation.

    Both orderings stay within each cell's four corner heights. Their convex blend
    retains that bound. This prevents interpolation overshoot (not a proof of
    globally preserved terrain topology). Missing heights are not treated as sea level.
    """
    valid=np.isfinite(field)
    if not valid.any(): raise ValueError('No elevations')
    nearest=ndi.distance_transform_edt(~valid,return_distances=False,return_indices=True)
    z=field[tuple(nearest)].astype(np.float64)
    h,w=z.shape
    x=np.arange(w); y=np.arange(h)
    if pixel_centres:
        # Image samples lie at pixel centres, not tile edges. Keep the world
        # footprint unchanged when increasing the number of image pixels.
        xx=(np.arange(w*scale)+.5)/scale-.5
        yy=(np.arange(h*scale)+.5)/scale-.5
    else:
        xx=np.arange((w-1)*scale+1)/scale
        yy=np.arange((h-1)*scale+1)/scale
    # Elevation slopes define the direction across the terrain feature.
    gy,gx=np.gradient(z)
    jxx=ndi.gaussian_filter(gx*gx,1.)
    jyy=ndi.gaussian_filter(gy*gy,1.)
    jxy=ndi.gaussian_filter(gx*gy,1.)
    trace=jxx+jyy+1e-9
    coherence=np.sqrt((jxx-jyy)**2+4*jxy*jxy)/trace
    weight=.5+.45*coherence*(jyy-jxx)/trace
    grid=np.meshgrid(yy,xx,indexing='ij')
    weight=ndi.map_coordinates(weight,grid,order=1,mode='nearest')
    xy=PchipInterpolator(y,PchipInterpolator(x,z,axis=1)(xx),axis=0)(yy)
    yx=PchipInterpolator(x,PchipInterpolator(y,z,axis=0)(yy),axis=1)(xx)
    result=weight*xy+(1-weight)*yx
    if valid.all(): coverage=np.ones(result.shape)
    else:
        signed=ndi.distance_transform_edt(valid)-ndi.distance_transform_edt(~valid)
        distance=ndi.map_coordinates(signed,grid,order=1,mode='nearest')
        coverage=np.clip(distance*scale+.5,0,1)
    return result,coverage


def illuminate(z,coverage,metres,scale=1):
    # Filter normals at a fixed fraction of the measured DEM spacing. A fixed
    # output-pixel filter exposes the interpolation grid at large magnification.
    gy,gx=np.gradient(z,metres)
    gx=ndi.gaussian_filter(gx,.4*scale)
    gy=ndi.gaussian_filter(gy,.4*scale)
    gx*=1.5; gy*=1.5
    light=np.clip((-.5*gx+.5*gy+.707)/np.sqrt(gx*gx+gy*gy+1),0,1)
    palette=np.array([[232,239,227],[204,223,188],[167,193,143],[167,176,137],[215,208,183]])
    rgb=np.stack([np.interp(z,[0,150,500,1000,1800],palette[:,i]) for i in range(3)],axis=-1)
    rgb*=.58+.58*light[:,:,None]
    rgb=rgb*coverage[:,:,None]+np.array(b.WATER_COLOR)*(1-coverage[:,:,None])
    return Image.fromarray(np.uint8(np.clip(rgb,0,255)))


def shade_refined(field, metres, scale=2, halo=8):
    """Render a halo-padded DEM at aligned image pixel centres."""
    if not np.isfinite(field).any():
        return None
    z,coverage=reconstruct(field,scale,pixel_centres=True)
    image=illuminate(z,coverage,metres/scale,scale)
    margin=halo*scale
    return image.crop((margin,margin,image.width-margin,image.height-margin))
