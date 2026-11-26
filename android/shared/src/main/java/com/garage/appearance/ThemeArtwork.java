package com.garage.appearance;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PorterDuff;
import android.widget.ImageView;

import java.io.IOException;
import java.io.InputStream;

public final class ThemeArtwork {
    private ThemeArtwork() {}

    public static boolean show(ImageView view, String asset, int shade) {
        return show(view, asset, shade, 2);
    }

    public static boolean show(ImageView view, String asset, int shade, int sampleSize) {
        if (!asset.equals(view.getTag())) {
            Bitmap bitmap = null;
            if (!asset.isEmpty()) {
                try (InputStream image = view.getContext().getAssets().open(asset)) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = sampleSize;
                    bitmap = BitmapFactory.decodeStream(image, null, options);
                } catch (IOException ignored) {
                    // The surrounding view supplies the classic appearance.
                }
            }
            if (bitmap == null) view.setImageDrawable(null);
            else view.setImageBitmap(bitmap);
            view.setTag(asset);
        }
        view.setColorFilter(shade, PorterDuff.Mode.SRC_ATOP);
        return view.getDrawable() != null;
    }
}
