import unittest
import numpy as np
import build_shaded as s


class ShadedTest(unittest.TestCase):
    def test_water_and_flat_land(self):
        self.assertIsNone(s.shade(np.full((260,260),np.nan),31.))
        field=np.full((260,260),100.)
        field[:,150:]=np.nan
        image=np.array(s.shade(field,31.))
        self.assertEqual(image.shape,(256,256,3))
        np.testing.assert_array_equal(image[100,200],s.b.WATER_COLOR)
        np.testing.assert_array_equal(image[100,20],image[120,30])

    def test_opposite_slopes_have_different_illumination(self):
        up=np.tile(np.arange(260)*3.,(260,1))
        down=up[:,::-1].copy()
        a=np.array(s.shade(up,31.))[128,128].mean()
        b=np.array(s.shade(down,31.))[128,128].mean()
        self.assertGreater(abs(a-b),5)

    def test_tile_halos_prevent_visible_brightness_seams(self):
        x=np.arange(516); y=np.arange(260)
        field=200+100*np.sin(x[None,:]/23)+50*np.cos(y[:,None]/19)
        whole=np.array(s.shade(field,31.)).astype(int)
        left=np.array(s.shade(field[:,:260],31.)).astype(int)
        right=np.array(s.shade(field[:,256:],31.)).astype(int)
        self.assertLessEqual(np.abs(np.concatenate([left,right],axis=1)-whole).max(),1)
