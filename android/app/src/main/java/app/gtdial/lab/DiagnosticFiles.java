package app.gtdial.lab;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/** Exact flat diagnostic filenames only. Installation evidence is never a cleanup target. */
final class DiagnosticFiles {
    static final Object TEXT_LOCK=new Object();
    static final String TEXT="gt-dial-lab-[0-9]{8}-[0-9]{6}[.]txt";
    static final String SNAPSHOT="operations-export-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[.]jsonl";
    static final String REPORT="diagnostics-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[.]zip";
    static File[] matching(File directory,String pattern)throws IOException {
        if(Files.isSymbolicLink(directory.toPath()))throw new IOException("LOG_DIRECTORY_LINK");
        File root=directory.getCanonicalFile();
        java.util.ArrayList<File> found=new java.util.ArrayList<>();
        if(!root.exists())return new File[0];
        try(java.nio.file.DirectoryStream<java.nio.file.Path> stream=Files.newDirectoryStream(root.toPath())){int count=0;for(java.nio.file.Path path:stream){if(++count>10000)throw new IOException("LOG_DIRECTORY_LIMIT");File candidate=path.toFile();if(candidate.getName().matches(pattern))found.add(candidate);}}
        File[] files=found.toArray(new File[0]);
        if(files==null){if(!root.exists())return new File[0];throw new IOException("LOG_DIRECTORY_UNREADABLE");}
        for(File file:files)if(!file.isFile()||Files.isSymbolicLink(file.toPath())||!file.getCanonicalFile().equals(file.getAbsoluteFile())||!root.equals(file.getCanonicalFile().getParentFile()))throw new IOException("LOG_PATH_REJECTED");
        return files;
    }
    static long remove(File file)throws IOException {
        long bytes=file.length();if(file.exists()&&!file.delete())throw new IOException("LOG_DELETE_FAILED");return bytes;
    }
    static long clear(File directory,String pattern)throws IOException {
        long bytes=0;for(File file:matching(directory,pattern))bytes+=remove(file);return bytes;
    }
    static void retainText(File current)throws IOException {
        DiagnosticRetention.whileUnleased(()->{synchronized(TEXT_LOCK){
            File[] files=matching(current.getParentFile(),TEXT);
            Arrays.sort(files,(a,b)->Long.compare(b.lastModified(),a.lastModified()));
            int removed=0;long bytes=0;int kept=1; // Reserve one of the four slots for the writer's current file.
            for(File file:files){if(file.getCanonicalFile().equals(current.getCanonicalFile())||DiagnosticRetention.protectedText(file))continue;if(kept++>=4){if(removed>=128||bytes+file.length()>32L*1024*1024)throw new IOException("TEXT_RETENTION_LIMIT");bytes+=remove(file);removed++;}}
        }});
    }
}
