package app.gtdial.lab;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Bounded diagnostic tail; disk I/O is deliberately never performed on the caller thread. */
final class DiagnosticLog {
    private static final long MAX_BYTES = 5L * 1024L * 1024L, MAX_PENDING_BYTES=1024L*1024L;
    private static final int MAX_TAIL=18_000;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private volatile File file;
    private long fileStartedAt=System.currentTimeMillis();
    private final Handler writer;
    private final HandlerThread thread;
    private final StringBuilder tail=new StringBuilder(MAX_TAIL);
    private long pendingBytes;
    private boolean limitReached,overflowMarked,closed;

    DiagnosticLog(Context context) {
        File directory = new File(context.getFilesDir(), "logs");
        boolean ready = directory.isDirectory() || directory.mkdirs();
        file = ready
                ? new File(directory, "gt-dial-lab-" + LocalDateTime.now().format(FILE_TIME) + ".txt")
                : null;
        DiagnosticRetention.protect(file);
        thread=new HandlerThread("gt-dial-journal");
        thread.start();
        writer=new Handler(thread.getLooper());
        if(file!=null)writer.post(()->{try{DiagnosticFiles.retainText(file);}catch(IOException e){append("Не удалось удалить старые журналы: "+e.getClass().getSimpleName());}});
    }

    synchronized String append(String message) {
        String safeMessage = message == null ? "<null>" : message.replace('\r', ' ').replace('\n', ' ');
        String line = LocalDateTime.now().format(LINE_TIME) + "  " + safeMessage;
        byte[] bytes = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        appendTail(line+System.lineSeparator());
        if(closed||file==null||limitReached)return line;
        if(pendingBytes+bytes.length>MAX_PENDING_BYTES){markOverflow();return line;}
        pendingBytes+=bytes.length;
        writer.post(()->write(bytes));
        return line;
    }

    synchronized String tail(){return tail.toString();}

    /** Only the owner closes this journal; quitSafely drains already queued lines. */
    synchronized void close(){if(closed)return;closed=true;writer.post(()->DiagnosticRetention.release(file));thread.quitSafely();}

    boolean isAvailable() {
        return file != null && file.isFile() && file.canRead();
    }

    File file() {
        return file;
    }

    long clear()throws Exception {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("LOG_CLEAR_ON_UI");
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        long[] removed={0};Exception[] error={null};
        if(!writer.post(()->{try{
            if(file==null)throw new IOException("LOG_UNAVAILABLE");
            synchronized(DiagnosticFiles.TEXT_LOCK){removed[0]=DiagnosticFiles.clear(file.getParentFile(),DiagnosticFiles.TEXT);}
            synchronized(this){tail.setLength(0);limitReached=false;overflowMarked=false;}
        }catch(Exception e){error[0]=e;}finally{done.countDown();}}))throw new IOException("LOG_WRITER_UNAVAILABLE");
        if(!done.await(15,java.util.concurrent.TimeUnit.SECONDS))throw new IOException("LOG_CLEAR_TIMEOUT");
        if(error[0]!=null)throw error[0];return removed[0];
    }

    /** Drain queued lines before a background export; never block the UI thread. */
    boolean flush(long timeoutMs) throws InterruptedException {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            throw new IllegalStateException("DIAGNOSTIC_FLUSH_ON_UI");
        }
        java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
        if (!writer.post(drained::countDown)) return false;
        return drained.await(Math.max(1L, Math.min(5_000L, timeoutMs)),
                java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private synchronized void appendTail(String line){tail.append(line);if(tail.length()>MAX_TAIL)tail.delete(0,tail.length()-MAX_TAIL);}
    private void markOverflow(){if(!overflowMarked){overflowMarked=true;appendTail("--- Journal writer queue overflow; lines omitted. ---"+System.lineSeparator());}}
    private void write(byte[] bytes) {
        if(System.currentTimeMillis()-fileStartedAt>=DiagnosticRetention.WEEK&&!DiagnosticRetention.leased()){
            File previous=file,next=new File(previous.getParentFile(),"gt-dial-lab-"+LocalDateTime.now().format(FILE_TIME)+".txt");
            if(!next.equals(previous)){DiagnosticRetention.protect(next);file=next;fileStartedAt=System.currentTimeMillis();synchronized(this){limitReached=false;}DiagnosticRetention.release(previous);try{DiagnosticFiles.retainText(next);}catch(IOException ignored){}}
        }
        boolean writeLimit=false;
        synchronized(this){
            if(!limitReached&&file.length()+bytes.length>MAX_BYTES){limitReached=true;writeLimit=true;}
        }
        try {
            if(writeLimit)writeDirect(("--- Journal file limit of 5 MiB reached; further lines remain visible on screen only. ---"+System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            else if(!limitReached)writeDirect(bytes);
        } finally { synchronized(this){pendingBytes=Math.max(0,pendingBytes-bytes.length);} }
    }
    private void writeDirect(byte[] bytes){
        synchronized(DiagnosticFiles.TEXT_LOCK){
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(bytes);
        } catch (IOException ignored) {
            // The on-screen journal remains available when internal storage is temporarily unavailable.
        }
        }
    }
}
