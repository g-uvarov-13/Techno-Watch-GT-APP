package app.gtdial.lab;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes a bounded, read-only watch transfer into app-private storage and packages it for sharing. */
final class WatchBackup {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private final File directory;
    private final File zipFile;
    private BufferedOutputStream current;
    private File currentFile;

    WatchBackup(Context context) throws IOException {
        File root = new File(context.getFilesDir(), "backups");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create backup directory");
        String id = "gt01-user-" + LocalDateTime.now().format(TIME);
        directory = new File(root, id);
        if (!directory.mkdirs()) throw new IOException("Cannot create backup session");
        zipFile = new File(root, id + ".zip");
    }

    void startFile(String remotePath) throws IOException {
        closeCurrent();
        if (!remotePath.startsWith("/user/")) throw new IOException("Path outside /user");
        String relative = remotePath.substring("/user/".length());
        if (relative.contains("..") || relative.indexOf('\0') >= 0) throw new IOException("Unsafe remote path");
        File target = new File(directory, relative);
        String rootPath = directory.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(rootPath)) throw new IOException("Path escaped backup directory");
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create local path");
        currentFile = target;
        current = new BufferedOutputStream(new FileOutputStream(target));
    }

    void append(byte[] data, int offset, int length) throws IOException {
        if (current == null) throw new IOException("No current backup file");
        current.write(data, offset, length);
    }

    void finishFile() throws IOException {
        closeCurrent();
    }

    File finishArchive() throws IOException {
        closeCurrent();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            addDirectory(zip, directory, directory);
        }
        return zipFile;
    }

    void abort() {
        try { closeCurrent(); } catch (IOException ignored) { }
    }

    private void closeCurrent() throws IOException {
        if (current != null) {
            current.close();
            current = null;
            currentFile = null;
        }
    }

    private void addDirectory(ZipOutputStream zip, File root, File item) throws IOException {
        File[] children = item.listFiles();
        if (children == null) return;
        byte[] buffer = new byte[8192];
        for (File child : children) {
            if (child.isDirectory()) {
                addDirectory(zip, root, child);
                continue;
            }
            String name = root.toPath().relativize(child.toPath()).toString().replace(File.separatorChar, '/');
            zip.putNextEntry(new ZipEntry("user/" + name));
            try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(child))) {
                int read;
                while ((read = input.read(buffer)) >= 0) zip.write(buffer, 0, read);
            }
            zip.closeEntry();
        }
    }
}
