package app.gtdial.lab;

import android.os.Handler;
import android.os.Looper;
import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;

/** Minimal allow-listed MCF/uRPC encoder used only for read-only diagnostics. */
final class McfProtocolProbe {
    interface Scheduler {
        void removeCallbacks(Runnable runnable);
        void postDelayed(Runnable runnable, long delayMs);
    }

    interface BackupFactory {
        WatchBackup create(Context context) throws IOException;
    }

    interface Transport {
        boolean write(byte[] frame);
        void log(String message);
        void finished(boolean success);
        void backupReady(File file);
        void albumFinished(boolean success);
        void activityFinished(boolean success, ActivitySnapshot snapshot);
        default void currentDialFinished(boolean success,String currentJson,String infoJson) { }
    }

    private static final String[] STEPS = {
            "_link_up", "_link_up2", "udbd.mtu", "udbd.ver", "udbd.zlib.decompress",
            "svc_dial_get_current", "svc_dial_info"
    };
    private static final String[] INITIAL_DISCOVERY_PATHS = {
            "/", "/user", "/user/app", "/user/apps", "/user/dial", "/user/dials",
            "/user/widgets", "/user/widgets/indexDial", "/user/widgets/dials",
            "/user/widgets/tmpDial", "/apps", "/download"
    };
    private static final int MAX_DISCOVERY_PATHS = 500;
    private static final int MAX_FILES = 1000;
    private static final long MAX_FILE_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_TOTAL_BYTES = 128L * 1024L * 1024L;
    private static final int READ_CHUNK = 4096;

    private final Transport transport;
    private final Context context;
    private final Scheduler scheduler;
    private final BackupFactory backupFactory;
    private final Runnable timeout = () -> cancel("timeout waiting for " + currentStepName());
    private final ByteArrayOutputStream receiveBuffer = new ByteArrayOutputStream();
    private final List<String> discoveryPaths = new ArrayList<>();
    private final List<String> remoteFiles = new ArrayList<>();
    private int step;
    private int packetId = 1;
    private int expectedPacketId = -1;
    private int frameId = 1;
    private int discoveryIndex = -1;
    private Pending pending = Pending.INITIAL;
    private WatchBackup backup;
    private int fileIndex;
    private int remoteFd;
    private long remoteFileSize;
    private long remoteOffset;
    private int requestedRead;
    private long totalDownloaded;
    private boolean currentFileAccepted;
    private boolean running;
    private boolean albumMode;
    private boolean activityMode;
    private boolean currentDialMode;
    private String currentDialJson, dialInfoJson;
    private AlbumState albumState = AlbumState.IDLE;
    private String albumSessionId;

    private enum Pending { INITIAL, PATH_TYPE, DIRECTORY_LIST, FILE_OPEN, FILE_LENGTH, FILE_READ, FILE_CLOSE, ALBUM_CONFIG, ALBUM_CONVENTION, ACTIVITY }
    private enum AlbumState { IDLE, INITIAL_READS, CONFIG_READ, CONVENTION_READ, COMPLETE, FAILED }

    McfProtocolProbe(Context context, Transport transport) {
        this(context, transport, new Scheduler() {
            private final Handler handler = new Handler(Looper.getMainLooper());
            @Override public void removeCallbacks(Runnable runnable) { handler.removeCallbacks(runnable); }
            @Override public void postDelayed(Runnable runnable, long delayMs) { handler.postDelayed(runnable, delayMs); }
        }, WatchBackup::new);
    }

    McfProtocolProbe(Context context, Transport transport, Scheduler scheduler, BackupFactory backupFactory) {
        this.context = context == null ? null : context.getApplicationContext();
        this.transport = transport;
        this.scheduler = scheduler;
        this.backupFactory = backupFactory;
    }

    boolean isRunning() {
        return running;
    }

    /** Reserve the next idle-session request id without sending a command. */
    int reservePacketId(){
        if(running)throw new IllegalStateException("Protocol probe is running");
        int reserved=packetId&255;packetId=(packetId+1)&255;return reserved;
    }

    void start() {
        if (running) return;
        running = true;
        albumMode = false;
        activityMode = false;
        step = 0;
        discoveryIndex = -1;
        discoveryPaths.clear();
        discoveryPaths.addAll(Arrays.asList(INITIAL_DISCOVERY_PATHS));
        remoteFiles.clear();
        fileIndex = 0;
        totalDownloaded = 0;
        currentFileAccepted = false;
        pending = Pending.INITIAL;
        receiveBuffer.reset();
        transport.log("READ-ONLY PROBE started; allow-list=" + String.join(", ", STEPS));
        sendCurrentStep();
    }

    void startAlbum() {
        if (running) return;
        running = true;
        albumMode = true;
        activityMode = false;
        albumState = AlbumState.INITIAL_READS;
        albumSessionId = Long.toHexString(System.currentTimeMillis());
        step = 0;
        receiveBuffer.reset();
        transport.log("ALBUM session=" + albumSessionId + " state=INITIAL_READS; one session per BLE connection");
        sendCurrentStep();
    }
    void startActivity() { startActivity(false); }
    void startActivity(boolean initialReadsComplete) {
        if (running) return;
        running=true; albumMode=false; activityMode=true; step=0; pending=Pending.INITIAL;
        receiveBuffer.reset(); transport.log("ACTIVITY state="+(initialReadsComplete?"DIRECT_READ":"INITIAL_READS"));
        if(initialReadsComplete)sendActivityRequest();else sendCurrentStep();
    }
    /** Uses the already proven handshake, current and inventory request sequence only. */
    void startCurrentDial() {
        if(running)return;
        running=true;albumMode=false;activityMode=false;currentDialMode=true;step=0;pending=Pending.INITIAL;
        currentDialJson=null;dialInfoJson=null;receiveBuffer.reset();transport.log("CURRENT_DIAL state=INITIAL_READS");sendCurrentStep();
    }

    private void sendActivityRequest() {
        try {
            JSONObject request=new JSONObject().put("keys",new JSONArray().put("target.steps").put("target.calorie").put("target.distance")).put("pool","health");
            expectedPacketId=packetId&0xFF;pending=Pending.ACTIVITY;
            sendRpc("svc_dcm_get",service("svc_dcm_get",request.toString().getBytes(StandardCharsets.UTF_8)));
            scheduler.removeCallbacks(timeout);if(running)scheduler.postDelayed(timeout,3000);
        }catch(Exception e){cancel("activity request: "+e.getMessage());}
    }

    void cancel(String reason) {
        if (!running) return;
        scheduler.removeCallbacks(timeout);
        if (backup != null) backup.abort();
        running = false;
        if (activityMode) {
            activityMode = false;
            transport.log("ACTIVITY state=FAILED terminal result=FAILED: " + reason);
            transport.activityFinished(false, null);
        } else if(currentDialMode){
            currentDialMode=false;
            transport.currentDialFinished(false,null,null);
        } else if (albumMode) {
            albumState = AlbumState.FAILED;
            transport.log("ALBUM session=" + albumSessionId + " state=FAILED terminal result=FAILED: " + reason);
            transport.albumFinished(false);
        } else {
            transport.log("READ-ONLY PROBE stopped: " + reason);
            transport.finished(false);
        }
    }

    void onChunk(byte[] value) {
        if (!running || value == null || value.length == 0) return;
        receiveBuffer.write(value, 0, value.length);
        parseFrames();
    }

    private void parseFrames() {
        byte[] data = receiveBuffer.toByteArray();
        int offset = 0;
        while (data.length - offset >= 3) {
            if ((data[offset] & 0xFF) != 0xFC) {
                offset++;
                continue;
            }
            int length = ((data[offset + 1] & 0xFF) << 8) | (data[offset + 2] & 0xFF);
            if (length < 8 || length > 65535) {
                offset++;
                continue;
            }
            if (data.length - offset < length) break;
            byte[] frame = Arrays.copyOfRange(data, offset, offset + length);
            offset += length;
            handleFrame(frame);
        }
        receiveBuffer.reset();
        if (offset < data.length) receiveBuffer.write(data, offset, data.length - offset);
    }

    private void handleFrame(byte[] frame) {
        if (!running) return;
        if ((frame[frame.length - 1] & 0xFF) != 0xCF) {
            transport.log("MCF rejected: missing CF terminator");
            return;
        }
        int receivedCrc = ((frame[frame.length - 3] & 0xFF) << 8) | (frame[frame.length - 2] & 0xFF);
        int calculatedCrc = crc16(frame, 1, frame.length - 4);
        if (receivedCrc != calculatedCrc) {
            transport.log(String.format(Locale.US, "MCF rejected: CRC %04X != %04X", receivedCrc, calculatedCrc));
            return;
        }
        // MCF payload: transport protocol, D2D src/dst/id/attributes, then uRPC payload.
        if (frame.length < 13 || frame[5] != 1) {
            transport.log("MCF received unsupported transport frame: " + hex(frame));
            return;
        }
        int source = frame[6] & 0xFF;
        int destination = frame[7] & 0xFF;
        int id = frame[8] & 0xFF;
        int type = (frame[9] & 0xFF) >>> 6;
        byte[] payload = Arrays.copyOfRange(frame, 10, frame.length - 3);
        transport.log(String.format(Locale.US,
                "MCF response src=%d dst=%d id=%d type=%d payload=%d B: %s",
                source, destination, id, type, payload.length, hex(payload)));
        if (type == 0) {
            transport.log("MCF incoming watch request id=" + id
                    + " ignored by diagnostic build (possible My Health/background traffic)");
            return;
        }
        if (type != 1 || id != expectedPacketId) {
            transport.log("MCF unrelated frame ignored; waiting for response id=" + expectedPacketId);
            return;
        }
        scheduler.removeCallbacks(timeout);
        if (albumMode && payload.length > AlbumConfiguration.MAX_RAW_BYTES) {
            cancel("ALBUM raw response exceeds " + AlbumConfiguration.MAX_RAW_BYTES + " B");
            return;
        }
        if (pending == Pending.INITIAL) {
            String completed=STEPS[step];
            describeStepResult(completed, payload);
            if(currentDialMode){if("svc_dial_get_current".equals(completed))currentDialJson=new String(payload,StandardCharsets.UTF_8).trim();if("svc_dial_info".equals(completed))dialInfoJson=new String(payload,StandardCharsets.UTF_8).trim();}
            step++;
            if (step >= STEPS.length) {
                if (activityMode) sendActivityRequest();
                else if(currentDialMode){scheduler.removeCallbacks(timeout);running=false;currentDialMode=false;transport.log("CURRENT_DIAL state=COMPLETE");transport.currentDialFinished(true,currentDialJson,dialInfoJson);}
                else if (albumMode) {
                    albumState = AlbumState.CONFIG_READ;
                    transport.log("ALBUM session=" + albumSessionId + " state=CONFIG_READ");
                    sendAlbumConfiguration(AlbumConfiguration.IMAGE_KEY);
                } else {
                    discoveryIndex = 0;
                    sendPathTypeProbe();
                }
            } else {
                sendCurrentStep();
            }
        } else if (activityMode && pending == Pending.ACTIVITY) { try { ActivitySnapshot a=ActivitySnapshot.parse(payload); scheduler.removeCallbacks(timeout); running=false; activityMode=false; transport.log("ACTIVITY state=COMPLETE terminal result=COMPLETE"); transport.activityFinished(true,a); } catch(Exception e){ cancel("activity response: "+e.getMessage()); } }
        else if (albumMode && pending == Pending.ALBUM_CONFIG) {
            handleAlbumConfiguration(AlbumConfiguration.IMAGE_KEY, payload);
        } else if (albumMode && pending == Pending.ALBUM_CONVENTION) {
            handleAlbumConfiguration(AlbumConfiguration.CONVENTION_KEY, payload);
        } else if (pending == Pending.PATH_TYPE) {
            int pathType = ffiReturnSigned(payload);
            String path = discoveryPaths.get(discoveryIndex);
            transport.log("FILE MAP type " + path + " = " + pathType + typeLabel(pathType));
            if (pathType == 1) sendDirectoryList();
            else advanceDiscovery();
        } else if (pending == Pending.DIRECTORY_LIST) {
            String json = new String(payload, StandardCharsets.UTF_8).trim();
            String path = discoveryPaths.get(discoveryIndex);
            transport.log("FILE MAP list " + path + ": " + printable(json));
            queueUserEntries(path, json);
            advanceDiscovery();
        } else if (pending == Pending.FILE_OPEN) {
            remoteFd = ffiReturnSigned(payload);
            if (remoteFd < 0) {
                transport.log("BACKUP skipped; open failed " + currentRemoteFile() + " code=" + remoteFd);
                nextFile();
            } else sendFileLength();
        } else if (pending == Pending.FILE_LENGTH) {
            remoteFileSize = Integer.toUnsignedLong(ffiReturnSigned(payload));
            remoteOffset = 0;
            currentFileAccepted = false;
            if (remoteFileSize > MAX_FILE_BYTES || totalDownloaded + remoteFileSize > MAX_TOTAL_BYTES) {
                transport.log("BACKUP skipped by size limit " + currentRemoteFile() + " size=" + remoteFileSize);
                sendFileClose();
            } else {
                try {
                    backup.startFile(currentRemoteFile());
                    currentFileAccepted = true;
                    if (remoteFileSize == 0) sendFileClose(); else sendFileRead();
                } catch (IOException error) {
                    cancel("local backup error: " + error.getMessage());
                }
            }
        } else if (pending == Pending.FILE_READ) {
            handleFileRead(payload);
        } else if (pending == Pending.FILE_CLOSE) {
            try {
                if (currentFileAccepted) {
                    backup.finishFile();
                    transport.log("BACKUP saved " + currentRemoteFile() + " " + remoteFileSize + " B");
                    totalDownloaded += remoteFileSize;
                }
            } catch (IOException error) {
                cancel("local backup close error: " + error.getMessage());
                return;
            }
            nextFile();
        }
    }

    private void describeStepResult(String name, byte[] payload) {
        if (name.startsWith("svc_dial_")) {
            String text = new String(payload, StandardCharsets.UTF_8).trim();
            transport.log(name + " decoded UTF-8: " + printable(text));
        } else if (name.startsWith("udbd.")) {
            transport.log(name + " printable response: " + printable(new String(payload, StandardCharsets.UTF_8)));
        } else {
            transport.log(name + " acknowledged");
        }
    }

    private void sendCurrentStep() {
        String name = STEPS[step];
        byte[] rpc;
        if ("_link_up".equals(name)) {
            rpc = service("ffi", concat(arrayArg("_link_up"), new byte[0]));
        } else if ("_link_up2".equals(name)) {
            rpc = service("_link_up2", "{\"version\":66560}".getBytes(StandardCharsets.UTF_8));
        } else if (name.startsWith("udbd.")) {
            rpc = service("ffi", concat(arrayArg("_dev_info"), arrayArg(name + "\0"), editableArrayArg(32)));
        } else {
            rpc = service(name, new byte[0]);
        }
        expectedPacketId = packetId & 0xFF;
        pending = Pending.INITIAL;
        sendRpc(name, rpc);
    }

    private void sendPathTypeProbe() {
        if (discoveryIndex >= discoveryPaths.size()) {
            beginBackup();
            return;
        }
        String path = discoveryPaths.get(discoveryIndex);
        byte[] rpc = service("ffi", concat(arrayArg("is_dir"), arrayArg(path + "\0")));
        expectedPacketId = packetId & 0xFF;
        pending = Pending.PATH_TYPE;
        sendRpc("is_dir " + path, rpc);
    }

    private void sendAlbumConfiguration(String key) {
        try {
            byte[] json = AlbumConfiguration.request(key);
            transport.log("ALBUM exact JSON before TX " + new String(json, StandardCharsets.UTF_8));
            expectedPacketId = packetId & 0xFF;
            pending = AlbumConfiguration.IMAGE_KEY.equals(key) ? Pending.ALBUM_CONFIG : Pending.ALBUM_CONVENTION;
            sendRpc("svc_systemstorage_get " + key, service("svc_systemstorage_get", json));
        } catch (Exception error) {
            cancel("cannot build album request: " + error.getMessage());
        }
    }

    private void handleAlbumConfiguration(String key, byte[] payload) {
        try {
            transport.log("ALBUM raw " + key + " (" + payload.length + " B): "
                    + new String(payload, StandardCharsets.UTF_8));
            String parsed = AlbumConfiguration.parse(key, payload);
            AlbumConfiguration.logFields(key, parsed, transport);
            if (AlbumConfiguration.IMAGE_KEY.equals(key)) {
                albumState = AlbumState.CONVENTION_READ;
                transport.log("ALBUM session=" + albumSessionId + " state=CONVENTION_READ");
                sendAlbumConfiguration(AlbumConfiguration.CONVENTION_KEY);
            } else {
                albumState = AlbumState.COMPLETE;
                running = false;
                transport.log("ALBUM session=" + albumSessionId + " state=COMPLETE terminal result=COMPLETE");
                transport.albumFinished(true);
            }
        } catch (Exception error) {
            cancel("ALBUM parse failed for " + key + ": " + error.getMessage());
        }
    }

    private void sendDirectoryList() {
        String path = discoveryPaths.get(discoveryIndex);
        byte[] input = (path + "\0").getBytes(StandardCharsets.UTF_8);
        expectedPacketId = packetId & 0xFF;
        pending = Pending.DIRECTORY_LIST;
        sendRpc("lsdir_svc " + path, service("lsdir_svc", input));
    }

    private void advanceDiscovery() {
        discoveryIndex++;
        sendPathTypeProbe();
    }

    private void sendRpc(String label, byte[] rpc) {
        byte[] d2d = new byte[5 + rpc.length];
        d2d[0] = 1; // D2D transport protocol (ProtoType.D2D)
        d2d[1] = 0; // phone/server DID
        d2d[2] = 1; // watch daemon DID
        d2d[3] = (byte) expectedPacketId;
        d2d[4] = 0x10; // REQ, response required, no ACK required
        System.arraycopy(rpc, 0, d2d, 5, rpc.length);
        byte[] frame = frame(d2d);
        transport.log("TX allow-listed read " + label
                + " id=" + expectedPacketId + " " + frame.length + " B: " + hex(frame));
        packetId = (packetId + 1) & 0xFF;
        if (!transport.write(frame)) cancel("Android rejected TX write for " + label);
        else scheduler.postDelayed(timeout, 8_000L);
    }

    private String currentStepName() {
        if (pending == Pending.ALBUM_CONFIG) return "svc_systemstorage_get " + AlbumConfiguration.IMAGE_KEY;
        if (pending == Pending.ALBUM_CONVENTION) return "svc_systemstorage_get " + AlbumConfiguration.CONVENTION_KEY;
        if (pending == Pending.PATH_TYPE && discoveryIndex >= 0 && discoveryIndex < discoveryPaths.size()) {
            return "is_dir " + discoveryPaths.get(discoveryIndex);
        }
        if (pending == Pending.DIRECTORY_LIST && discoveryIndex >= 0 && discoveryIndex < discoveryPaths.size()) {
            return "lsdir_svc " + discoveryPaths.get(discoveryIndex);
        }
        return step >= 0 && step < STEPS.length ? STEPS[step] : "response";
    }

    private int ffiReturnSigned(byte[] payload) {
        if (payload.length < 5 || (payload[0] & 0x0F) != 4) return Integer.MIN_VALUE;
        return (payload[1] & 0xFF)
                | ((payload[2] & 0xFF) << 8)
                | ((payload[3] & 0xFF) << 16)
                | (payload[4] << 24);
    }

    private String typeLabel(int type) {
        if (type == 0) return " (not found)";
        if (type == 1) return " (directory)";
        if (type == 2) return " (file)";
        return " (unexpected)";
    }

    private void queueUserEntries(String parent, String json) {
        if (!(parent.equals("/user") || parent.startsWith("/user/"))) return;
        if (pathDepth(parent) > 10) return;
        try {
            JSONArray entries = new JSONObject(json).getJSONArray("array");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                JSONArray names = entry.names();
                if (names == null || names.length() != 1) continue;
                String name = names.getString(0);
                if (!safeRemoteName(name)) continue;
                String child = parent + "/" + name;
                String kind = entry.optString(name);
                if ("DIR".equals(kind) && discoveryPaths.size() < MAX_DISCOVERY_PATHS && !discoveryPaths.contains(child)) {
                    discoveryPaths.add(child);
                    transport.log("FILE MAP queued /user directory: " + child);
                } else if ("FIL".equals(kind) && remoteFiles.size() < MAX_FILES && !remoteFiles.contains(child)) {
                    remoteFiles.add(child);
                    transport.log("FILE MAP queued file: " + child);
                }
            }
        } catch (Exception error) {
            transport.log("FILE MAP JSON parse failed for " + parent + ": " + error.getClass().getSimpleName());
        }
    }

    private boolean safeRemoteName(String name) {
        return !name.isEmpty() && name.length() <= 128 && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('\0') < 0;
    }

    private void beginBackup() {
        transport.log("BACKUP /user inventory complete: " + remoteFiles.size() + " files; limit="
                + (MAX_TOTAL_BYTES / 1024 / 1024) + " MiB");
        try {
            backup = backupFactory.create(context);
        } catch (IOException error) {
            cancel("cannot create local backup: " + error.getMessage());
            return;
        }
        fileIndex = 0;
        if (remoteFiles.isEmpty()) finishBackup(); else sendFileOpen();
    }

    private void sendFileOpen() {
        currentFileAccepted = false;
        byte[] input = concat(arrayArg("open"), arrayArg(currentRemoteFile() + "\0"), u32Arg(0));
        expectedPacketId = packetId & 0xFF;
        pending = Pending.FILE_OPEN;
        sendRpc("open(O_RDONLY) " + currentRemoteFile(), service("ffi", input));
    }

    private void sendFileLength() {
        byte[] input = concat(arrayArg("lseek"), u32Arg(remoteFd), u32Arg(0), u32Arg(2));
        expectedPacketId = packetId & 0xFF;
        pending = Pending.FILE_LENGTH;
        sendRpc("lseek(SEEK_END) " + currentRemoteFile(), service("ffi", input));
    }

    private void sendFileRead() {
        requestedRead = (int) Math.min(READ_CHUNK, remoteFileSize - remoteOffset);
        byte[] input = concat(arrayArg("read_by_offset"), u32Arg(remoteFd), u32Arg((int) remoteOffset),
                editableArrayArg(requestedRead), u32Arg(requestedRead));
        expectedPacketId = packetId & 0xFF;
        pending = Pending.FILE_READ;
        sendRpc("read_by_offset " + currentRemoteFile() + " @" + remoteOffset + "+" + requestedRead,
                service("ffi", input));
    }

    private void handleFileRead(byte[] payload) {
        int count = ffiReturnSigned(payload);
        if (count <= 0 || count > requestedRead || payload.length < 8 + count || (payload[5] & 0xFF) != 0xC1) {
            cancel("invalid read response for " + currentRemoteFile() + " count=" + count);
            return;
        }
        try {
            backup.append(payload, 8, count);
        } catch (IOException error) {
            cancel("local backup write error: " + error.getMessage());
            return;
        }
        remoteOffset += count;
        if (remoteOffset < remoteFileSize) sendFileRead(); else sendFileClose();
    }

    private void sendFileClose() {
        expectedPacketId = packetId & 0xFF;
        pending = Pending.FILE_CLOSE;
        sendRpc("close " + currentRemoteFile(), service("ffi", concat(arrayArg("close"), u32Arg(remoteFd))));
    }

    private void nextFile() {
        fileIndex++;
        if (fileIndex >= remoteFiles.size()) finishBackup(); else sendFileOpen();
    }

    private void finishBackup() {
        try {
            File archive = backup.finishArchive();
            running = false;
            transport.log("READ-ONLY BACKUP complete: " + fileIndex + " files, " + totalDownloaded
                    + " B; no watch data changed");
            transport.backupReady(archive);
            transport.finished(true);
        } catch (IOException error) {
            cancel("cannot create backup ZIP: " + error.getMessage());
        }
    }

    private String currentRemoteFile() {
        return remoteFiles.get(fileIndex);
    }

    private int pathDepth(String path) {
        int depth = 0;
        for (int i = 0; i < path.length(); i++) if (path.charAt(i) == '/') depth++;
        return depth;
    }

    private byte[] service(String name, byte[] input) {
        byte[] serviceName = name.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[serviceName.length + 2 + input.length];
        System.arraycopy(serviceName, 0, result, 0, serviceName.length);
        result[serviceName.length] = 0;
        result[serviceName.length + 1] = 1;
        System.arraycopy(input, 0, result, serviceName.length + 2, input.length);
        return result;
    }

    private byte[] arrayArg(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[3 + bytes.length];
        result[0] = (byte) 0x81;
        result[1] = (byte) (bytes.length & 0xFF);
        result[2] = (byte) ((bytes.length >>> 8) & 0xFF);
        System.arraycopy(bytes, 0, result, 3, bytes.length);
        return result;
    }

    private byte[] editableArrayArg(int length) {
        return new byte[] {(byte) 0xC1, (byte) (length & 0xFF), (byte) ((length >>> 8) & 0xFF)};
    }

    private byte[] u32Arg(int value) {
        return new byte[] {0x04, (byte) value, (byte) (value >>> 8), (byte) (value >>> 16), (byte) (value >>> 24)};
    }

    private byte[] frame(byte[] payload) {
        int length = 5 + payload.length + 3;
        byte[] frame = new byte[length];
        frame[0] = (byte) 0xFC;
        frame[1] = (byte) ((length >>> 8) & 0xFF);
        frame[2] = (byte) (length & 0xFF);
        frame[3] = (byte) (frameId++ & 0xFF);
        frame[4] = 0;
        System.arraycopy(payload, 0, frame, 5, payload.length);
        int crc = crc16(frame, 1, 4 + payload.length);
        frame[length - 3] = (byte) ((crc >>> 8) & 0xFF);
        frame[length - 2] = (byte) (crc & 0xFF);
        frame[length - 1] = (byte) 0xCF;
        return frame;
    }

    private int crc16(byte[] value, int offset, int length) {
        int crc = 0xFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc ^= value[i] & 0xFF;
            for (int bit = 0; bit < 8; bit++) crc = (crc & 1) != 0 ? (crc >>> 1) ^ 0xA001 : crc >>> 1;
        }
        return crc & 0xFFFF;
    }

    private byte[] concat(byte[]... values) {
        int size = 0;
        for (byte[] value : values) size += value.length;
        byte[] result = new byte[size];
        int offset = 0;
        for (byte[] value : values) {
            System.arraycopy(value, 0, result, offset, value.length);
            offset += value.length;
        }
        return result;
    }

    private String printable(String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length() && result.length() < 2000; i++) {
            char ch = text.charAt(i);
            result.append(ch >= 32 && ch != 127 ? ch : '.');
        }
        return result.toString();
    }

    private String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        int displayed = Math.min(value.length, 160);
        for (int i = 0; i < displayed; i++) {
            if (i > 0) result.append(' ');
            result.append(String.format(Locale.US, "%02X", value[i] & 0xFF));
        }
        if (displayed < value.length) result.append(" …");
        return result.toString();
    }
}
