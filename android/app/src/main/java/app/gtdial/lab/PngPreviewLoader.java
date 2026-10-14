package app.gtdial.lab;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;

final class PngPreviewLoader {
    private PngPreviewLoader() { }

    static Bitmap load(ContentResolver resolver, Uri uri, int targetWidth, int targetHeight) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IOException("Не удалось прочитать PNG");
            BitmapFactory.decodeStream(input, null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Не удалось прочитать PNG");
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth && bounds.outHeight / (sample * 2) >= targetHeight) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream input = resolver.openInputStream(uri)) {
            Bitmap bitmap = input == null ? null : BitmapFactory.decodeStream(input, null, options);
            if (bitmap == null) throw new IOException("Не удалось декодировать PNG");
            return bitmap;
        }
    }
}
