import unittest
import numpy as np
from terrain_reconstruction import reconstruct, shade_refined


class UpscaleTest(unittest.TestCase):
    def test_preserves_samples_and_cell_height_bounds(self):
        z=np.random.default_rng(42).uniform(0,1000,(8,9))
        high,mask=reconstruct(z,4)
        np.testing.assert_allclose(high[::4,::4],z,atol=1e-10)
        for y in range(7):
            for x in range(8):
                corners=z[y:y+2,x:x+2]
                cell=high[y*4:y*4+5,x*4:x*4+5]
                self.assertGreaterEqual(cell.min(),corners.min()-1e-9)
                self.assertLessEqual(cell.max(),corners.max()+1e-9)

    def test_plane_and_flat_terrain_are_not_artificially_sharpened(self):
        y,x=np.mgrid[:7,:8]
        high,_=reconstruct(100+2*x+3*y,4)
        yy,xx=np.mgrid[:25,:29]/4
        np.testing.assert_allclose(high,100+2*xx+3*yy,atol=1e-9)

    def test_no_sea_level_cliff_is_invented(self):
        z=np.full((8,8),500.)
        z[:,4:]=np.nan
        high,mask=reconstruct(z,4)
        np.testing.assert_allclose(high,500.)
        self.assertEqual(mask[8,0],1)
        self.assertEqual(mask[8,-1],0)

    def test_image_pixel_centres_keep_geographic_alignment(self):
        y,x=np.mgrid[:16,:17]
        high,_=reconstruct(100+2*x+3*y,2,pixel_centres=True)
        yy,xx=np.mgrid[:32,:34]
        expected=100+2*((xx+.5)/2-.5)+3*((yy+.5)/2-.5)
        np.testing.assert_allclose(high,expected,atol=1e-9)

    def test_refined_tiles_match_a_single_continuous_render(self):
        # Nonlinear terrain crossing both horizontal and vertical tile boundaries.
        y,x=np.mgrid[:80,:80]
        field=500+120*np.sin((x+y)/6)+70*np.cos((2*x-y)/9)
        whole=np.array(shade_refined(field,31.))
        rows=[]
        for top in (0,32):
            rows.append(np.concatenate([np.array(shade_refined(
                field[top:top+48,left:left+48],31.)) for left in (0,32)],axis=1))
        separate=np.concatenate(rows,axis=0)
        self.assertEqual(separate.shape,(128,128,3))
        self.assertLessEqual(np.abs(separate.astype(int)-whole.astype(int)).max(),1)

    def test_refined_all_water_is_skipped(self):
        self.assertIsNone(shade_refined(np.full((32,32),np.nan),31.))

    def test_refined_coast_matches_across_tile_boundaries(self):
        y,x=np.mgrid[:80,:80]
        field=200+30*np.sin(x/5)+20*np.cos(y/7)
        field[x+y>80]=np.nan
        whole=np.array(shade_refined(field,31.))
        separate=np.concatenate([np.concatenate([
            np.array(shade_refined(field[top:top+48,left:left+48],31.))
            for left in (0,32)],axis=1) for top in (0,32)],axis=0)
        self.assertLessEqual(np.abs(separate.astype(int)-whole.astype(int)).max(),1)
