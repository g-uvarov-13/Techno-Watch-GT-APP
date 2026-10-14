package app.gtdial.lab;

import android.graphics.BitmapFactory;

import java.io.IOException;

final class PngInspector {
    static final long MAX_FILE_SIZE = 10L * 1024L * 1024L;
    private static final byte[] PNG_SIGNATURE = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    record Info(String name, long size, int width, int height) {
        String summary() {
            String fileSize = size < 0 ? "размер неизвестен" : Math.max(1, size / 1024) + " KiB";
            return name + " — " + width + "×" + height + ", " + fileSize;
        }
    }

    private PngInspector() {
    }

    static Info inspect(byte[] bytes, String name) throws IOException {
        if (bytes == null || bytes.length < PNG_SIGNATURE.length) throw new IOException("Пустой PNG");
        long size = bytes.length;
        if (size > MAX_FILE_SIZE) {
            throw new IOException("PNG больше 10 MiB");
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (bytes[i] != PNG_SIGNATURE[i]) {
                throw new IOException("Выбранный файл не является PNG");
            }
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            throw new IOException("Не удалось определить размер PNG");
        }
        if (options.outWidth != options.outHeight) {
            throw new IOException("Для GT01 нужен квадратный PNG");
        }
        return new Info(name == null || name.isEmpty() ? "image.png" : name, size, options.outWidth, options.outHeight);
    }
}
