package app.gtdial.lab;

import android.os.Handler;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/** One-shot GT01 album PNG staging and apply session. It never deletes or rolls back a dial. */
final class AlbumStageProtocol {
    enum ExistingDialAction { REJECT, REUSE, REPLACE }
    interface Scheduler { void removeCallbacks(Runnable r); void postDelayed(Runnable r, long delayMs); }
    interface Transport { boolean write(byte[] frame); void log(String message); boolean backup(byte[] bytes, String sha256); default boolean saveAcquisitionEvidence(String hash,String currentBefore,String inventoryBefore,String currentAfter,String inventoryAfter){return false;} default boolean saveOwnershipReceipt(DynamicDialOwnershipStore.Receipt expectedPrior,DynamicDialOwnershipStore.Receipt next){return false;} default boolean armDynamicMutation(String operationId,DynamicDialOwnershipStore.Receipt prior,DynamicDialPackage target,String backupHash,long backupSize){return false;} default boolean saveMutationReceipt(String operationId,String exactCurrent,DynamicDialOwnershipStore.Receipt prior,DynamicDialOwnershipStore.Receipt next){return false;} default boolean saveVerifiedBackup(DynamicDialOwnershipStore.Receipt next,byte[] bytes){return false;} default boolean saveRecoveredReceipt(DynamicDialOwnershipStore.RecoveryContext recovery,String current,DynamicDialOwnershipStore.Receipt next){return false;} default void progress(String phase,long completed,long total,String error,boolean mutationStarted){} void terminal(boolean success); }

    static final int MAX_IMAGE_BYTES = 168_722;
    private static final int CHUNK = 384;
    private static final int CAPTURED_STAGING_BYTES = 414_720;
    private static final int DEFAULT_MAX_FRAME = 509;
    // Synthetic address for offline-compatible callers that supply no physical device.
    private static final String OFFLINE_COMPATIBILITY_ADDRESS = "02:00:00:00:00:01";
    private static final int OPEN_WRITE_FLAGS = 578;
    // Permit overwriting only the package which was physically proven on this watch.
    private static final int EXISTING_DYNAMIC_BYTES = 536_153;
    private static final String EXISTING_DYNAMIC_SHA256 = "928f548c744539c751f20c2d11771f21c052923beab258056d3f6ff21f219e3e";
    private static final int MISSING_RECEIPT_VIOLET_BYTES = 807_091;
    private static final String MISSING_RECEIPT_VIOLET_HISTORICAL_SHA256 = "d995e7534a6ac5dd32e6e0bb1cb3270da87b90aff6d440824c1c424bcb61fec1";
    private static final String MISSING_RECEIPT_VIOLET_COMPILED_SHA256 = "4dc1f1fea9b469bac8626fe6e86f20755dd91effb4a99074f5d4328ff993188";
    private static final DialIdentity MISSING_RECEIPT_VIOLET_IDENTITY = new DialIdentity("com.gtdial.dl.violet", "2309202608");
    private static final String[] INITIAL = {"_link_up", "_link_up2", "udbd.mtu", "udbd.ver",
            "udbd.zlib.decompress", "svc_dial_get_current", "svc_dial_info"};

    private enum Step { INITIAL, CONFIG, CONVENTION, ACCESS_BEFORE, OPEN_EXISTING, SEEK_EXISTING, LENGTH_EXISTING, READ_EXISTING, CLOSE_EXISTING, ACCESS_CACHE, OPEN_CACHE, WRITE, CLOSE_CACHE,
        ACCESS_BEFORE_RENAME, RENAME, ACCESS_FINAL, OPEN_FINAL, LENGTH_FINAL, READ_FINAL, CLOSE_FINAL,
        APPLY_40103, APPLY_DIAL, APPLY_40101, WAIT_INCOMING, POST_ALIAS, POST_CONFIG,
        DIAL_INSTALL, DIAL_INFO, DIAL_APPLY, DIAL_CURRENT, LOCAL_RECHECK_CURRENT, LOCAL_RECHECK_INFO, RECOVERY_CURRENT, RECOVERY_INFO, ACQUISITION_CURRENT, ACQUISITION_INFO, FRAGMENT_CURRENT, FRAGMENT_INFO, DONE, FAILED }

    private final Transport transport;
    private final Scheduler scheduler;
    private final byte[] image;
    private final String name;
    private final String finalPath;
    private final String cachePath;
    private final String sha256;
    private final int maxFrameBytes;
    private final boolean dynamicDialMode;
    private final String dynamicAlias;
    private final String dynamicPackageId;
    private final BundledDynamicDial.PackageVariant dynamicVariant;
    private final DynamicDialPackage dynamicTarget;
    private boolean directInstallMode,directHandshakeReady;
    private String operationId=java.util.UUID.randomUUID().toString();
    private DialInstallJournal installJournal;
    private String journalService="",journalFfi="";
    private String terminalError="";
    private String verifiedBackupHash;
    private boolean mutationArmed;
    private byte[] localInitialInventory;
    private DialIdentity localInitialCurrent;
    private String localCurrentMode,localConfirmedCurrent;
    private DynamicDialOwnershipStore.RecoveryContext pendingRecovery;
    private java.util.List<DynamicDialOwnershipStore.Receipt> localKnownReceipts=java.util.Collections.emptyList();
    private byte[] reusableBackup;
    private boolean fastBackup,fullTargetReadback;
    private final String deviceAddress;
    private final DynamicDialOwnershipStore.Receipt priorOwnership;
    private final boolean readOnlyRecovery;
    private final boolean acquisitionMode;
    private final boolean fragmentRecovery;
    static final String CAPTURED_FRAGMENT_SHA256="27c4783d71ed27d2e555b2a86f04fa25bf0e5eb14f67539b0c68cdbe903597ce";
    private String acquisitionCurrentBefore,acquisitionInventoryBefore,acquisitionHash;
    private long existingExpectedSize;
    private String[] existingExpectedHashes;
    private String ownershipEvidenceSource;
    private String ownershipExpectedIdentity;
    private String fallbackId,fallbackAlias;
    private int positionIndex;
    private int color;
    private final ByteArrayOutputStream receive = new ByteArrayOutputStream();
    private final ByteArrayOutputStream existingBytes = new ByteArrayOutputStream();
    private Step step = Step.INITIAL;
    private final MessageDigest readDigest;
    private final MessageDigest existingDigest;
    private final Runnable timeout = () -> fail("timeout waiting for " + step);
    private int initialIndex;
    private int packetId = 1;
    private int expectedId = -1;
    private int frameId = 1;
    private int fd = -1;
    private int offset;
    private int requested;
    private boolean running;
    private boolean mutationStarted;
    private boolean alreadyInstalled;
    private boolean applyStarted;
    private String priorAlias;
    private boolean albumIdentityProven;
    private String priorImageConfig;
    private int applyTaskId = (int) (System.currentTimeMillis() % 65535L) + 1;
    private final LauncherEnvelopeCodec.Reassembler incomingReassembler = new LauncherEnvelopeCodec.Reassembler();
    private long startedAtMs;
    private long requestCount;
    private long txBytes;
    private long rxBytes;
    private Step lastLoggedPhase;
    private String lastProgressPhase="";
    private long lastProgressAt,lastProgressBytes=-1;
    private long phaseStartedAtMs;
    private long phaseStartRequests;
    private long phaseStartTxBytes;
    private long phaseStartRxBytes;
    private long requestSentAtMs;
    private long phaseResponseCount;
    private long phaseResponseLatencyTotalMs;
    private long phaseResponseLatencyMinMs;
    private long phaseResponseLatencyMaxMs;

    AlbumStageProtocol(byte[] image, String basename, Transport transport) {
        this(image, basename, transport, new Scheduler() {
            final Handler handler = new Handler();
            public void removeCallbacks(Runnable r) { handler.removeCallbacks(r); }
            public void postDelayed(Runnable r, long delayMs) { handler.postDelayed(r, delayMs); }
        });
    }

    AlbumStageProtocol(byte[] image, String basename, Transport transport, int maxFrameBytes) {
        this(image, basename, transport, new Scheduler() {
            final Handler handler = new Handler();
            public void removeCallbacks(Runnable r) { handler.removeCallbacks(r); }
            public void postDelayed(Runnable r, long delayMs) { handler.postDelayed(r, delayMs); }
        }, maxFrameBytes);
    }

    AlbumStageProtocol(byte[] image, String basename, int positionIndex, int color, Transport transport, int maxFrameBytes) {
        this(image, basename, transport, maxFrameBytes);
        if (positionIndex < 1 || positionIndex > 2) throw new IllegalArgumentException("position");
        this.positionIndex = positionIndex;
        this.color = color;
    }

    AlbumStageProtocol(byte[] image, String basename, Transport transport, Scheduler scheduler) {
        this(image, basename, transport, scheduler, DEFAULT_MAX_FRAME);
    }

    AlbumStageProtocol(byte[] image, String basename, Transport transport, Scheduler scheduler, int maxFrameBytes) {
        if (image == null || image.length == 0 || image.length > MAX_IMAGE_BYTES) throw new IllegalArgumentException("image size");
        if (basename == null || !basename.matches("gtdl_[0-9]{13}\\.png")) throw new IllegalArgumentException("basename");
        this.image = image.clone();
        this.name = basename;
        this.finalPath = "/user/widgets/indexDial/" + basename;
        this.cachePath = finalPath + ".cache";
        this.transport = transport;
        this.scheduler = scheduler;
        if (maxFrameBytes < 509) throw new IllegalArgumentException("MTU frame limit");
        this.maxFrameBytes = maxFrameBytes;
        this.dynamicDialMode = false;
        this.dynamicAlias = null;
        this.dynamicPackageId = null;
        this.dynamicVariant = null;
        this.dynamicTarget = null;
        this.deviceAddress = null;
        this.priorOwnership = null;
        this.readOnlyRecovery = false;
        this.acquisitionMode = false;
        this.fragmentRecovery = false;
        this.existingExpectedSize = 0;
        this.existingExpectedHashes = null;
        this.positionIndex = 1;
        this.color = 0;
        this.sha256 = hash(this.image);
        this.readDigest = digest();
        this.existingDigest = digest();
    }

    /** Compatibility constructor used by protocol checks for the physically proven original package. */
    AlbumStageProtocol(byte[] dialPackage, Transport transport, int maxFrameBytes, boolean dynamicDialMode) {
        this(dialPackage,legacyVariant(),OFFLINE_COMPATIBILITY_ADDRESS,null,transport,new Scheduler() {
            final Handler handler = new Handler();
            public void removeCallbacks(Runnable r) { handler.removeCallbacks(r); }
            public void postDelayed(Runnable r, long delayMs) { handler.postDelayed(r, delayMs); }
        },maxFrameBytes,dynamicDialMode);
    }

    AlbumStageProtocol(byte[] dialPackage,String packageId,String alias,Transport transport,int maxFrameBytes,boolean dynamicDialMode) {
        this(dialPackage,resolveVariant(packageId,alias,dialPackage),OFFLINE_COMPATIBILITY_ADDRESS,null,transport,new Scheduler() {
            final Handler handler = new Handler();
            public void removeCallbacks(Runnable r) { handler.removeCallbacks(r); }
            public void postDelayed(Runnable r, long delayMs) { handler.postDelayed(r, delayMs); }
        },maxFrameBytes,dynamicDialMode);
    }

    AlbumStageProtocol(byte[] dialPackage,BundledDynamicDial.PackageVariant variant,String address,DynamicDialOwnershipStore.Receipt prior,Transport transport,int maxFrameBytes,boolean dynamicDialMode){
        this(dialPackage,variant,address,prior,transport,new Scheduler(){final Handler handler=new Handler();public void removeCallbacks(Runnable r){handler.removeCallbacks(r);}public void postDelayed(Runnable r,long delayMs){handler.postDelayed(r,delayMs);}},maxFrameBytes,dynamicDialMode);
    }

    AlbumStageProtocol(byte[] dialPackage, Transport transport, Scheduler scheduler, int maxFrameBytes, boolean dynamicDialMode) {
        this(dialPackage,legacyVariant(),OFFLINE_COMPATIBILITY_ADDRESS,null,transport,scheduler,maxFrameBytes,dynamicDialMode);
    }

    AlbumStageProtocol(byte[] dialPackage,String packageId,String alias,Transport transport,Scheduler scheduler,int maxFrameBytes,boolean dynamicDialMode){
        this(dialPackage,resolveVariant(packageId,alias,dialPackage),OFFLINE_COMPATIBILITY_ADDRESS,null,transport,scheduler,maxFrameBytes,dynamicDialMode);
    }

    AlbumStageProtocol(byte[] dialPackage,BundledDynamicDial.PackageVariant variant,String address,DynamicDialOwnershipStore.Receipt prior,Transport transport,Scheduler scheduler,int maxFrameBytes,boolean dynamicDialMode) {
        this(dialPackage,variant,address,prior,transport,scheduler,maxFrameBytes,dynamicDialMode,false);
    }

    static AlbumStageProtocol readOnlyDynamicRecovery(byte[] dialPackage,BundledDynamicDial.PackageVariant variant,String address,DynamicDialOwnershipStore.Receipt prior,Transport transport,Scheduler scheduler,int maxFrameBytes) {
        return new AlbumStageProtocol(dialPackage,variant,address,prior,transport,scheduler,maxFrameBytes,true,true);
    }

    static AlbumStageProtocol readOnlyStagingAcquisition(String address,DynamicDialOwnershipStore.Receipt prior,Transport transport,Scheduler scheduler,int maxFrameBytes) {
        return new AlbumStageProtocol(address,prior,transport,scheduler,maxFrameBytes);
    }

    private AlbumStageProtocol(String address,DynamicDialOwnershipStore.Receipt prior,Transport transport,Scheduler scheduler,int maxFrameBytes) {
        try {
            this.deviceAddress=DynamicDialOwnershipStore.normalizeAddress(address);
            if(prior!=null&&(!this.deviceAddress.equals(prior.deviceAddress)||!BundledDynamicDial.REMOTE_PATH.equals(prior.path)))throw new IllegalArgumentException("acquisition receipt device/path");
        } catch(java.io.IOException e) { throw new IllegalArgumentException("acquisition device",e); }
        if(maxFrameBytes<509)throw new IllegalArgumentException("MTU frame limit");
        this.priorOwnership=prior;this.transport=transport;this.scheduler=scheduler;this.maxFrameBytes=maxFrameBytes;
        this.image=new byte[0];this.name="1909202601.dial";this.finalPath=BundledDynamicDial.REMOTE_PATH;this.cachePath=null;this.sha256="";
        this.dynamicDialMode=true;this.readOnlyRecovery=false;this.acquisitionMode=true;this.fragmentRecovery=false;
        this.dynamicAlias=null;this.dynamicPackageId=null;this.dynamicVariant=null;this.dynamicTarget=null;
        this.positionIndex=1;this.color=0;this.readDigest=digest();this.existingDigest=digest();
        this.existingExpectedSize=CAPTURED_STAGING_BYTES;this.existingExpectedHashes=null;
    }

    private AlbumStageProtocol(byte[] dialPackage,BundledDynamicDial.PackageVariant variant,String address,DynamicDialOwnershipStore.Receipt prior,Transport transport,Scheduler scheduler,int maxFrameBytes,boolean dynamicDialMode,boolean readOnlyRecovery) {
        this(dialPackage,variant,address,prior,transport,scheduler,maxFrameBytes,dynamicDialMode,readOnlyRecovery,false);
    }

    static AlbumStageProtocol capturedFragmentRecovery(byte[] bytes,BundledDynamicDial.PackageVariant variant,String address,DynamicDialOwnershipStore.Receipt prior,Transport transport,int maxFrameBytes){
        throw new IllegalArgumentException("FRAGMENT_RECOVERY_UNAVAILABLE_PUBLIC_RELEASE");
    }

    private AlbumStageProtocol(byte[] dialPackage,BundledDynamicDial.PackageVariant variant,String address,DynamicDialOwnershipStore.Receipt prior,Transport transport,Scheduler scheduler,int maxFrameBytes,boolean dynamicDialMode,boolean readOnlyRecovery,boolean fragmentRecovery) {
        this(dialPackage,DynamicDialPackage.bundled(variant),address,prior,transport,scheduler,maxFrameBytes,dynamicDialMode,readOnlyRecovery,fragmentRecovery);
    }

    AlbumStageProtocol(DynamicDialPackage target,String address,DynamicDialOwnershipStore.Receipt prior,Transport transport,int maxFrameBytes) {
        this(target.localBytes(),target,address,prior,transport,new Scheduler(){final Handler handler=new Handler();public void removeCallbacks(Runnable r){handler.removeCallbacks(r);}public void postDelayed(Runnable r,long ms){handler.postDelayed(r,ms);}},maxFrameBytes,true,false,false);
    }

    AlbumStageProtocol(DynamicDialAuthorization.LocalExecution execution,String address,DynamicDialOwnershipStore.Receipt prior,Transport transport,int maxFrameBytes,DialInstallJournal journal) {
        this(execution.target.localBytes(),execution.target,address,prior,transport,new Scheduler(){final Handler handler=new Handler();public void removeCallbacks(Runnable r){handler.removeCallbacks(r);}public void postDelayed(Runnable r,long ms){handler.postDelayed(r,ms);}},maxFrameBytes,true,execution.recovery!=null,false,execution);
        if(journal==null)throw new IllegalArgumentException("local journal missing");
        operationId=execution.prepared.operationId;installJournal=journal;pendingRecovery=execution.recovery;localKnownReceipts=execution.knownReceipts;reusableBackup=execution.reusableBackup();
    }

    private AlbumStageProtocol(byte[] dialPackage,DynamicDialPackage target,String address,DynamicDialOwnershipStore.Receipt prior,Transport transport,Scheduler scheduler,int maxFrameBytes,boolean dynamicDialMode,boolean readOnlyRecovery,boolean fragmentRecovery) {
        this(dialPackage,target,address,prior,transport,scheduler,maxFrameBytes,dynamicDialMode,readOnlyRecovery,fragmentRecovery,null);
    }
    private AlbumStageProtocol(byte[] dialPackage,DynamicDialPackage target,String address,DynamicDialOwnershipStore.Receipt prior,Transport transport,Scheduler scheduler,int maxFrameBytes,boolean dynamicDialMode,boolean readOnlyRecovery,boolean fragmentRecovery,DynamicDialAuthorization.LocalExecution execution) {
        if(fragmentRecovery)throw new IllegalArgumentException("FRAGMENT_RECOVERY_UNAVAILABLE_PUBLIC_RELEASE");
        BundledDynamicDial.PackageVariant variant=target.bundled;
        boolean local=target.origin==DynamicDialPackage.Origin.LOCAL_PROFILE_BUILD;
        if(local&&prior==null)throw new IllegalArgumentException("PRIOR_RECEIPT_REQUIRED");
        if (!dynamicDialMode || dialPackage == null || target.packageSize!=dialPackage.length||!target.packageSha256.equals(hash(dialPackage))
                ||local&&(execution==null||!DynamicDialAuthorization.localCandidate(target)||!execution.matches(execution.prepared.operationId,address,target)||prior==null||fragmentRecovery||readOnlyRecovery!=(execution.recovery!=null))) {
            throw new IllegalArgumentException("dial package");
        }
        if(local&&!execution.claim())throw new IllegalArgumentException("local execution already claimed");
        try{
            this.deviceAddress=DynamicDialOwnershipStore.normalizeAddress(address);
            this.priorOwnership=prior;
            if(prior!=null&&(!this.deviceAddress.equals(prior.deviceAddress)||!BundledDynamicDial.REMOTE_PATH.equals(prior.path)||prior.schemaVersion==1&&!BundledDynamicDialRepository.isCompiledVariant(prior.internalPackageId,prior.alias,prior.packageSize,prior.packageSha256)))throw new IllegalArgumentException("ownership receipt does not match device/path/trusted tuple");
            if(readOnlyRecovery&&!local&&prior!=null&&!isAcceptedRecoveryReceipt(prior))throw new IllegalArgumentException("recovery receipt is not the captured stable or animation tuple");
        }catch(java.io.IOException e){throw new IllegalArgumentException("dynamic dial ownership context",e);}
        this.image = dialPackage.clone();
        this.name = "1909202601.dial";
        this.finalPath = "/download/1909202601.dial";
        this.cachePath = null;
        this.transport = transport;
        this.scheduler = scheduler;
        if (maxFrameBytes < 509) throw new IllegalArgumentException("MTU frame limit");
        this.maxFrameBytes = maxFrameBytes;
        this.positionIndex = 1;
        this.color = 0;
        this.sha256 = hash(this.image);
        this.readDigest = digest();
        this.existingDigest = digest();
        this.dynamicDialMode = true;
        this.dynamicAlias = target.alias;
        this.dynamicPackageId = target.internalPackageId;
        this.dynamicVariant = variant;
        this.dynamicTarget = target;
        this.readOnlyRecovery = readOnlyRecovery;
        this.acquisitionMode = false;
        this.fragmentRecovery = fragmentRecovery;
        this.existingExpectedSize=prior==null?EXISTING_DYNAMIC_BYTES:prior.packageSize;
        this.existingExpectedHashes=new String[]{prior==null?EXISTING_DYNAMIC_SHA256:prior.packageSha256};
        this.ownershipEvidenceSource=prior==null?"missing receipt: legacy fixed tuple":"durable ownership receipt";
        this.ownershipExpectedIdentity=prior==null?"com.gtdial.pipboy01/"+BundledDynamicDial.REMOTE_ALIAS:prior.internalPackageId+"/"+prior.alias;
    }

    static AlbumStageProtocol directInstall(byte[] bytes,DynamicDialPackage target,boolean handshakeReady,Transport transport,Scheduler scheduler,int maxFrameBytes){
        return new AlbumStageProtocol(bytes,target,handshakeReady,transport,scheduler,maxFrameBytes);
    }
    private AlbumStageProtocol(byte[] bytes,DynamicDialPackage target,boolean handshakeReady,Transport transport,Scheduler scheduler,int maxFrameBytes){
        if(target==null||bytes==null||bytes.length==0||bytes.length!=target.packageSize||maxFrameBytes<509)throw new IllegalArgumentException("direct package or MTU");
        // The caller supplies the selected verified package; direct mode never enters ownership/recovery paths.
        image=bytes.clone();name="1909202601.dial";finalPath="/download/1909202601.dial";cachePath=null;
        this.transport=transport;this.scheduler=scheduler;this.maxFrameBytes=maxFrameBytes;
        sha256=target.packageSha256;readDigest=digest();existingDigest=digest();positionIndex=1;color=0;
        dynamicDialMode=true;dynamicAlias=target.alias;dynamicPackageId=target.internalPackageId;dynamicVariant=target.bundled;dynamicTarget=target;
        deviceAddress=null;priorOwnership=null;readOnlyRecovery=false;acquisitionMode=false;fragmentRecovery=false;
        directInstallMode=true;directHandshakeReady=handshakeReady;
    }

    private static BundledDynamicDial.PackageVariant legacyVariant(){try{return BundledDynamicDialRepository.compiledVariant("pipboy01","phosphor");}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    private static BundledDynamicDial.PackageVariant resolveVariant(String id,String alias,byte[] bytes){if(bytes==null)throw new IllegalArgumentException("dial package");for(String face:new String[]{"pipboy3000","bitax","pixbot","pipboy01"})for(String palette:new String[]{"phosphor","amber","cyan","white","red","violet","copper","redorange","gold","ice","green","vfd"})try{BundledDynamicDial.PackageVariant v=BundledDynamicDialRepository.compiledVariant(face,palette);if(v.internalPackageId.equals(id)&&v.alias.equals(alias)&&v.packageSize==bytes.length&&v.packageSha256.equals(hash(bytes)))return v;}catch(java.io.IOException ignored){}throw new IllegalArgumentException("dial package is not a compiled variant");}

    void start() {
        if (running || step != Step.INITIAL || initialIndex != 0) return;
        running = true;
        startedAtMs = SystemClock.elapsedRealtime();
        requestCount = 0;
        txBytes = 0;
        rxBytes = 0;
        lastLoggedPhase = null;
        requestSentAtMs = 0;
        if(!journalEvent("PROTOCOL","STARTED"))return;
        if(acquisitionMode)transport.log("STAGING_ACQUISITION started path="+finalPath+" exactBytes="+CAPTURED_STAGING_BYTES+" receiptBytes="+(priorOwnership==null?"none":priorOwnership.packageSize)+" receiptSha256="+(priorOwnership==null?"none":priorOwnership.packageSha256));
        else transport.log("STAGE started target=" + finalPath + " bytes=" + image.length + " sha256=" + sha256);
        if(directInstallMode){if(directHandshakeReady)openDirectTarget();else sendInitial();return;}
        if(pendingRecovery!=null)initialIndex=5;sendInitial();
    }

    void cancel(String reason) { fail(reason); }
    boolean isRunning() { return running; }
    boolean isVerified() { return step == Step.DONE; }
    boolean alreadyInstalled() { return isVerified() && alreadyInstalled; }
    boolean isDirectInstall() { return directInstallMode; }
    String priorAlias() { return isVerified() ? priorAlias : null; }
    String stagedName() { return isVerified() ? name : null; }
    String stagedHash() { return isVerified() ? sha256 : null; }

    void onChunk(byte[] chunk) {
        if (!running || chunk == null || chunk.length == 0) return;
        receive.write(chunk, 0, chunk.length);
        rxBytes += chunk.length;
        byte[] data = receive.toByteArray();
        int pos = 0;
        while (running && data.length - pos >= 3) {
            if ((data[pos] & 255) != 0xFC) { pos++; continue; }
            int length = ((data[pos + 1] & 255) << 8) | (data[pos + 2] & 255);
            if (length < 13 || length > 65535) { fail("invalid frame length"); break; }
            if (data.length - pos < length) break;
            handleFrame(Arrays.copyOfRange(data, pos, pos + length));
            pos += length;
        }
        receive.reset();
        if (running && pos < data.length) receive.write(data, pos, data.length - pos);
    }

    private void handleFrame(byte[] frame) {
        if (!running) return;
        if ((frame[frame.length - 1] & 255) != 0xCF || crc16(frame, 1, frame.length - 4) != (((frame[frame.length - 3] & 255) << 8) | (frame[frame.length - 2] & 255))) {
            journalFrame("RX",frame,"REJECTED","CRC_INVALID");fail("invalid MCF frame"); return;
        }
        if (frame[5] != 1) { journalFrame("RX",frame,"REJECTED","PROTOCOL_INVALID");fail("unexpected D2D protocol"); return; }
        int type = (frame[9] & 255) >>> 6;
        int id = frame[8] & 255;
        if (type == 0) {
            if (step == Step.WAIT_INCOMING) { handleIncomingRequest(frame); return; }
            if(dynamicDialMode){
                if(isCapturedLauncherBackgroundRequest(frame)){
                    if(!journalFrame("RX",frame,"IGNORED",""))return;
                    transport.log("DIAL ignored independent launcher background request id="+id);
                    return;
                }
                journalFrame("RX",frame,"REJECTED","UNEXPECTED_REQUEST");fail("unexpected incoming request during dynamic dial operation");return;
            }
            transport.log("STAGE ignored incoming request id=" + id); return;
        }
        if (frame[6] != 1 || frame[7] != 0) { journalFrame("RX",frame,"REJECTED","ADDRESS_INVALID");fail("unexpected D2D addressing"); return; }
        if (type != 1 || id != expectedId) { journalFrame("RX",frame,"REJECTED","RPC_MISMATCH");fail("unexpected response id/type received="+id+"/"+type+" expected="+expectedId+"/1; competing watch client possible"); return; }
        if(!journalFrame("RX",frame,"FRAME_VALIDATED",""))return;
        scheduler.removeCallbacks(timeout);
        byte[] payload = Arrays.copyOfRange(frame, 10, frame.length - 3);
        recordResponseLatency();
        try { handleResponse(payload); } catch (Exception e) { fail("malformed " + step + ": " + e.getMessage()); }
        catch(OutOfMemoryError e){fail("LOCAL_MEMORY_FAILED");}
    }

    private static boolean isCapturedLauncherBackgroundRequest(byte[] frame){
        if(frame[6]!=1||frame[7]!=0||(frame[9]&0x30)!=0x10)return false;
        byte[] payload=Arrays.copyOfRange(frame,10,frame.length-3);
        byte[] service="msg_send_ubjson\0".getBytes(StandardCharsets.US_ASCII);
        if(payload.length<service.length+1||payload[service.length]!=1)return false;
        for(int i=0;i<service.length;i++)if(payload[i]!=service[i])return false;
        String body=new String(payload,service.length+1,payload.length-service.length-1,StandardCharsets.ISO_8859_1);
        return body.contains("launcher")&&body.contains("local.control");
    }

    private void handleResponse(byte[] payload) throws Exception {
        if(directInstallMode){handleDirectResponse(payload);return;}
        switch (step) {
            case INITIAL -> {
                if (initialIndex == 5) { priorAlias = parseAlias(payload); if(acquisitionMode)acquisitionCurrentBefore=priorAlias; transport.log("STAGE priorAlias=" + priorAlias); }
                if (initialIndex == 6) {
                    captureLocalInventory("INITIAL",payload);
                    if(acquisitionMode){validateAcquisitionInventory(payload,priorAlias);acquisitionInventoryBefore=new String(payload,StandardCharsets.UTF_8);transport.log("STAGING_ACQUISITION identity verified before read");}
                    else if (dynamicDialMode) { Preflight preflight=fragmentRecovery?validateFragmentPreflight(payload,priorAlias):pendingRecovery!=null?validatePendingPreflight(payload,priorAlias):readOnlyRecovery?validateRecoveryPreflight(payload,priorAlias,dynamicVariant,priorOwnership):isLocalTarget()?validateLocalPreflight(payload,priorAlias,dynamicTarget,priorOwnership,localKnownReceipts):validateDynamicPreflight(payload,priorAlias,dynamicVariant,priorOwnership);fallbackId=preflight.fallback.id;fallbackAlias=preflight.fallback.alias;existingExpectedSize=preflight.existingSize;existingExpectedHashes=preflight.existingHashes;ownershipEvidenceSource=preflight.evidenceSource;ownershipExpectedIdentity=preflight.expectedIdentity;transport.log("DIAL preflight expected="+ownershipExpectedIdentity+" source="+ownershipEvidenceSource+" observed="+preflight.observedIdentity); }
                    else albumIdentityProven = validateDialInfo(payload);
                    if(dynamicDialMode&&!isLocalTarget()&&!readOnlyRecovery&&!fragmentRecovery&&isExactActiveTarget(priorAlias)){
                        localInitialInventory=payload.clone();localInitialCurrent=new DialIdentity(dynamicPackageId,dynamicAlias);
                    }
                }
                initialIndex++;
                if(isLocalTarget()&&initialIndex==7){
                    localInitialInventory=payload.clone();localInitialCurrent=pendingRecovery!=null?new DialIdentity(dynamicPackageId,dynamicAlias):localCurrentIdentity(priorAlias,priorOwnership);
                    localCurrentMode=pendingRecovery!=null||selectedCurrentIdentifierMatches(priorAlias,priorOwnership.internalPackageId,priorOwnership.alias)?"OWNED_ACTIVE":"SYSTEM_BOLT01";
                    fastBackup=pendingRecovery==null&&reusableBackup!=null&&"OWNED_ACTIVE".equals(localCurrentMode)&&!isExactActiveTarget(priorAlias);
                    if(!journalCurrentPair("INITIAL_CURRENT")||!journalEvent("PREFLIGHT","VERIFIED"))return;
                }
                if (initialIndex < INITIAL.length) sendInitial();
                else if (dynamicDialMode) { step = Step.ACCESS_BEFORE; sendFfi("access", arrayArg(finalPath + "\0")); }
                else { step = Step.CONFIG; sendService("svc_systemstorage_get", AlbumConfiguration.request(AlbumConfiguration.IMAGE_KEY)); }
            }
            case CONFIG -> {
                priorImageConfig = AlbumConfiguration.parse(AlbumConfiguration.IMAGE_KEY, payload);
                transport.log("STAGE prior image config=" + priorImageConfig);
                step = Step.CONVENTION;
                sendService("svc_systemstorage_get", AlbumConfiguration.request(AlbumConfiguration.CONVENTION_KEY));
            }
            case CONVENTION -> {
                JSONObject convention = new JSONObject(AlbumConfiguration.parse(AlbumConfiguration.CONVENTION_KEY, payload));
                if (convention.optInt("maxCount", 0) < 1) throw new IllegalArgumentException("maxCount");
                JSONArray types = convention.optJSONArray("imageTypes");
                boolean supportsPng = false;
                if (types != null) for (int i = 0; i < types.length(); i++) supportsPng |= types.optInt(i, -1) == 0;
                if (!supportsPng) throw new IllegalArgumentException("imageTypes");
                step = Step.ACCESS_BEFORE; sendFfi("access", arrayArg(finalPath + "\0"));
            }
            case ACCESS_BEFORE -> { int result = ffiInt(payload); transport.log("STAGE access final before mutation result=" + result); if (dynamicDialMode) { if (result != 0) throw new IllegalArgumentException(result == -1 ? "existing staging missing" : "ambiguous staging access " + result); step = pendingRecovery!=null?Step.OPEN_FINAL:Step.OPEN_EXISTING; sendFfi("open", arrayArg(finalPath + "\0"), u32(0)); } else { if (result != -1) throw new IllegalArgumentException(result == 0 ? "target exists" : "ambiguous final access " + result); step = Step.ACCESS_CACHE; sendFfi("access", arrayArg(cachePath + "\0")); } }
            case OPEN_EXISTING -> { fd = ffiInt(payload); if (fd < 0) throw new IllegalArgumentException("existing open"); step = Step.SEEK_EXISTING; sendFfi("lseek", u32(fd), u32(0), u32(2)); }
            case SEEK_EXISTING -> { if (Integer.toUnsignedLong(ffiInt(payload)) != existingExpectedSize) throw new IllegalArgumentException("existing size"); if(fastBackup){transport.progress("BACKUP_REUSE",0,0,"",false);step=Step.CLOSE_EXISTING;sendFfi("close",u32(fd));}else{step = Step.LENGTH_EXISTING; sendFfi("lseek", u32(fd), u32(0), u32(0));} }
            case LENGTH_EXISTING -> { if (ffiInt(payload) != 0) throw new IllegalArgumentException("existing rewind"); offset = 0; existingDigest.reset(); existingBytes.reset(); step = Step.READ_EXISTING; sendExistingRead(); }
            case READ_EXISTING -> { int count = readCount(payload); if (count != requested) throw new IllegalArgumentException("short existing read " + count + "/" + requested); existingBytes.write(payload, 8, count); existingDigest.update(payload, 8, count); offset += count; if (offset < existingExpectedSize) sendExistingRead(); else { step = Step.CLOSE_EXISTING; sendFfi("close", u32(fd)); } }
            case CLOSE_EXISTING -> {
                if (ffiInt(payload) != 0) throw new IllegalArgumentException("existing close");
                if(fastBackup){
                    if(reusableBackup.length!=existingExpectedSize||!approvedExistingHash(hash(reusableBackup))||!transport.backup(reusableBackup,hash(reusableBackup)))throw new IllegalArgumentException("LOCAL_BACKUP_REUSE_FAILED");
                    verifiedBackupHash=hash(reusableBackup);if(!journalHash("LOCAL_VERIFIED_BACKUP_REUSE",verifiedBackupHash,existingExpectedSize)||!journalEvent("REMOTE_PRIOR_NOT_REHASHED","ASSUMED_UNCHANGED"))return;
                    step=Step.LOCAL_RECHECK_CURRENT;sendService("svc_dial_get_current",new byte[0]);return;
                }
                if (existingBytes.size() != existingExpectedSize) throw new IllegalArgumentException("existing size " + existingBytes.size());
                String existingHash = hex(existingDigest.digest());
                if (!acquisitionMode&&!approvedExistingHash(existingHash)) throw new IllegalArgumentException("existing hash " + existingHash);
                if (!transport.backup(existingBytes.toByteArray(), existingHash)) throw new IllegalArgumentException("existing local backup");
                verifiedBackupHash=existingHash;
                if(!journalHash("BACKUP_VERIFIED",existingHash,existingExpectedSize))return;
                if(acquisitionMode){acquisitionHash=existingHash;transport.log("STAGING_ACQUISITION local copy verified bytes="+existingExpectedSize+" sha256="+existingHash);step=Step.ACQUISITION_CURRENT;sendService("svc_dial_get_current",new byte[0]);return;}
                if(fragmentRecovery){step=Step.FRAGMENT_CURRENT;sendService("svc_dial_get_current",new byte[0]);return;}
                if(readOnlyRecovery){step=Step.RECOVERY_CURRENT;sendService("svc_dial_get_current",new byte[0]);return;}
                if(isLocalTarget()){step=Step.LOCAL_RECHECK_CURRENT;sendService("svc_dial_get_current",new byte[0]);return;}
                if(isExactActiveTarget(priorAlias)){step=Step.LOCAL_RECHECK_CURRENT;sendService("svc_dial_get_current",new byte[0]);return;}
                ExistingDialAction action=existingSizeMatchesTarget(existingHash)?ExistingDialAction.REUSE:ExistingDialAction.REPLACE;
                if(action==ExistingDialAction.REUSE){
                    transport.log("DIAL exact target reused after full read/hash/local backup; no file rewrite");
                    beginDialInstall();
                }else if(action==ExistingDialAction.REPLACE){
                    armMutation();mutationStarted=true;offset=0;step=Step.OPEN_CACHE;sendFfi("open",arrayArg(finalPath+"\0"),u32(OPEN_WRITE_FLAGS));
                }else throw new IllegalArgumentException("unapproved existing staging state");
            }
            case ACCESS_CACHE -> { int result = ffiInt(payload); transport.log("STAGE access cache before mutation result=" + result); if (result != -1) throw new IllegalArgumentException(result == 0 ? "cache exists" : "ambiguous cache access " + result); mutationStarted = true; step = Step.OPEN_CACHE; sendFfi("open", arrayArg(cachePath + "\0"), u32(OPEN_WRITE_FLAGS)); }
            case OPEN_CACHE -> { fd = ffiInt(payload); if (fd < 0) throw new IllegalArgumentException("open " + fd); offset = 0; step = Step.WRITE; sendWrite(); }
            case WRITE -> { int wrote = ffiInt(payload); if (wrote != requested) throw new IllegalArgumentException("short write " + wrote + "/" + requested); offset += wrote; if (offset < image.length) sendWrite(); else { step = Step.CLOSE_CACHE; sendFfi("close", u32(fd)); } }
            case CLOSE_CACHE -> { if (ffiInt(payload) != 0) throw new IllegalArgumentException("close"); if (dynamicDialMode) { step = Step.ACCESS_FINAL; sendFfi("access", arrayArg(finalPath + "\0")); } else { step = Step.ACCESS_BEFORE_RENAME; sendFfi("access", arrayArg(finalPath + "\0")); } }
            case ACCESS_BEFORE_RENAME -> { int result = ffiInt(payload); transport.log("STAGE access final before rename result=" + result); if (result != -1) throw new IllegalArgumentException(result == 0 ? "target appeared" : "ambiguous final access " + result); step = Step.RENAME; sendFfi("rename", arrayArg(cachePath + "\0"), arrayArg(finalPath + "\0")); }
            case RENAME -> { if (ffiInt(payload) != 0) throw new IllegalArgumentException("rename"); step = Step.ACCESS_FINAL; sendFfi("access", arrayArg(finalPath + "\0")); }
            case ACCESS_FINAL -> { if (ffiInt(payload) != 0) throw new IllegalArgumentException("final missing"); step = Step.OPEN_FINAL; sendFfi("open", arrayArg(finalPath + "\0"), u32(0)); }
            case OPEN_FINAL -> { fd = ffiInt(payload); if (fd < 0) throw new IllegalArgumentException("read open"); step = Step.LENGTH_FINAL; sendFfi("lseek", u32(fd), u32(0), u32(2)); }
            case LENGTH_FINAL -> { if (Integer.toUnsignedLong(ffiInt(payload)) != image.length) throw new IllegalArgumentException("length"); offset = 0; step = Step.READ_FINAL; sendRead(); }
            case READ_FINAL -> {
                int count = readCount(payload);
                if (count != requested) throw new IllegalArgumentException("short read " + count + "/" + requested);
                for (int i = 0; i < count; i++) if (payload[8 + i] != image[offset + i]) throw new IllegalArgumentException("byte mismatch");
                readDigest.update(payload, 8, count); offset += count;
                if (offset < image.length) sendRead(); else { step = Step.CLOSE_FINAL; sendFfi("close", u32(fd)); }
            }
            case CLOSE_FINAL -> {
                if (ffiInt(payload) != 0 || !hex(readDigest.digest()).equals(sha256)) throw new IllegalArgumentException("final verification");
                fullTargetReadback=true;if(!journalHash("READBACK_VERIFIED",sha256,image.length))return;
                if(pendingRecovery!=null){step=Step.LOCAL_RECHECK_CURRENT;sendService("svc_dial_get_current",new byte[0]);return;}
                transport.log("STAGE VERIFIED target=" + finalPath + " sha256=" + sha256 + " priorAlias=" + priorAlias);
                applyStarted = true;
                if (dynamicDialMode) { beginDialInstall(); }
                else { step = Step.APPLY_40103; sendApply40103(); }
            }
            case APPLY_40103 -> { requireDirectSuccess(payload); step = Step.APPLY_DIAL; sendFfi("svc_dial_apply", arrayArg("60001001\0")); }
            case APPLY_DIAL -> { if (ffiInt(payload) != 0) throw new IllegalArgumentException("dial apply"); step = Step.APPLY_40101; sendApply40101(); }
            case APPLY_40101 -> { requireDirectSuccess(payload); step = Step.WAIT_INCOMING; transport.log("STAGE waiting for app.local.control response taskId=" + applyTaskId); scheduler.postDelayed(timeout, 8_000); }
            case POST_ALIAS -> { String alias = parseAlias(payload); if (!albumIdentityProven || !("60001001".equals(alias) || "com.QC.album".equals(alias))) throw new IllegalArgumentException("applied alias"); step = Step.POST_CONFIG; sendService("svc_systemstorage_get", AlbumConfiguration.request(AlbumConfiguration.IMAGE_KEY)); }
            case POST_CONFIG -> { String config = AlbumConfiguration.parse(AlbumConfiguration.IMAGE_KEY, payload); verifyAppliedConfig(config); finishCurrentPhase(); running = false; step = Step.DONE; transport.log("STAGE APPLIED target=" + finalPath + " alias=60001001"); transport.terminal(true); }
            case DIAL_INSTALL -> { if (ffiInt(payload) != 0) throw new IllegalArgumentException("dial install");if(!journalEvent("INSTALL","VERIFIED"))return;step=Step.DIAL_INFO;sendService("svc_dial_info",new byte[0]); }
            case DIAL_INFO -> { captureLocalInventory("POST_INSTALL",payload);validateInstalledDynamic(payload,dynamicPackageId,dynamicAlias,fallbackId,fallbackAlias);if(isLocalTarget())validateKnownLocalInventory(payload,dynamicPackageId,dynamicAlias,localKnownReceipts,true);if(!journalEvent("INVENTORY","VERIFIED"))return;step=Step.DIAL_APPLY;sendFfi("svc_dial_apply",arrayArg(dynamicAlias+"\0")); }
            case DIAL_APPLY -> { if(ffiInt(payload)!=0)throw new IllegalArgumentException("dial apply");if(!journalEvent("APPLY","VERIFIED"))return;step=Step.DIAL_CURRENT;sendService("svc_dial_get_current",new byte[0]); }
            case DIAL_CURRENT -> {
                String current=parseAlias(payload);if(!selectedCurrentIdentifierMatches(current,dynamicPackageId,dynamicAlias))throw new IllegalArgumentException("current package identity");
                if(!journalEvent("CURRENT","VERIFIED"))return;
                DynamicDialOwnershipStore.Receipt next=isLocalTarget()?new DynamicDialOwnershipStore.Receipt(deviceAddress,BundledDynamicDial.REMOTE_PATH,dynamicTarget):new DynamicDialOwnershipStore.Receipt(deviceAddress,BundledDynamicDial.REMOTE_PATH,dynamicVariant);
                if(isLocalTarget()&&(!fullTargetReadback||!transport.saveVerifiedBackup(next,image)))throw new IllegalArgumentException("BACKUP_WITNESS_FAILED");
                if(installJournal!=null){installJournal.event("RECEIPT_CAS","ATTEMPT");installJournal.sync();}
                if(!mutationArmed||!transport.saveMutationReceipt(operationId,current,priorOwnership,next)){fail("applied current dial but durable ownership receipt could not be saved; future staging is blocked");return;}
                // After a committed CAS, ledger failure must not re-arm or replay the installation.
                if(installJournal!=null)try{installJournal.event("RECEIPT_CAS","VERIFIED");}catch(java.io.IOException|OutOfMemoryError e){transport.log("PROTOCOL_SUCCESS_LOG_FAILED");}
                finishCurrentPhase();running=false;step=Step.DONE;transport.log("DIAL APPLIED AND VERIFIED identity="+current+" receiptSaved=true elapsedMs="+(SystemClock.elapsedRealtime()-startedAtMs)+" requests="+requestCount+" txBytes="+txBytes+" rxBytes="+rxBytes+" throughputBps="+(image.length*1000L/Math.max(1L,SystemClock.elapsedRealtime()-startedAtMs)));transport.terminal(true);
            }
            case LOCAL_RECHECK_CURRENT -> {
                String current=parseAlias(payload);
                if(localInitialCurrent==null||!selectedCurrentIdentifierMatches(current,localInitialCurrent.id,localInitialCurrent.alias))throw new IllegalArgumentException("CURRENT_CHANGED");
                localConfirmedCurrent=current;if(!journalCurrentPair("RECHECK_CURRENT"))return;step=Step.LOCAL_RECHECK_INFO;sendService("svc_dial_info",new byte[0]);
            }
            case LOCAL_RECHECK_INFO -> {
                captureLocalInventory("RECHECK",payload);if(localInitialInventory==null||!Arrays.equals(localInitialInventory,payload))throw new IllegalArgumentException("local inventory changed after backup");if(localConfirmedCurrent==null)throw new IllegalArgumentException("CURRENT_CHANGED");
                if(pendingRecovery!=null){
                    validatePendingPreflight(payload,localConfirmedCurrent);if(!fullTargetReadback)throw new IllegalArgumentException("RECOVERY_READBACK_REQUIRED");
                    DynamicDialOwnershipStore.Receipt next=new DynamicDialOwnershipStore.Receipt(deviceAddress,BundledDynamicDial.REMOTE_PATH,dynamicTarget);
                    if(!transport.saveVerifiedBackup(next,image))throw new IllegalArgumentException("BACKUP_WITNESS_FAILED");
                    if(installJournal!=null){installJournal.event("RECOVERY_COMMIT","ATTEMPT");installJournal.sync();}
                    transport.progress("RECOVERY_COMMIT",0,0,"",false);
                    if(!transport.saveRecoveredReceipt(pendingRecovery,localConfirmedCurrent,next))throw new IllegalArgumentException("RECOVERY_RECEIPT_FAILED");
                    if(installJournal!=null)try{installJournal.event("RECOVERY_COMMIT","VERIFIED");}catch(java.io.IOException|OutOfMemoryError e){transport.log("PROTOCOL_SUCCESS_LOG_FAILED");}
                    finishCurrentPhase();running=false;step=Step.DONE;transport.log("LOCAL RECOVERY VERIFIED receiptSaved=true; no remote write/install/apply");transport.terminal(true);return;
                }
                if(isLocalTarget())validateLocalPreflight(payload,localConfirmedCurrent,dynamicTarget,priorOwnership,localKnownReceipts);
                else validateDynamicPreflight(payload,localConfirmedCurrent,dynamicVariant,priorOwnership);
                if(!journalEvent("RECHECK_INVENTORY","VERIFIED"))return;
                if(isExactActiveTarget(localConfirmedCurrent)){finishAlreadyInstalled();return;}
                armMutation();mutationStarted=true;offset=0;step=Step.OPEN_CACHE;sendFfi("open",arrayArg(finalPath+"\0"),u32(OPEN_WRITE_FLAGS));
            }
            case RECOVERY_CURRENT -> { if(!selectedCurrentIdentifierMatches(parseAlias(payload),dynamicPackageId,dynamicAlias))throw new IllegalArgumentException("recovery current changed"); step=Step.RECOVERY_INFO;sendService("svc_dial_info",new byte[0]); }
            case RECOVERY_INFO -> { validateRecoveryInventory(payload,dynamicVariant); DynamicDialOwnershipStore.Receipt next=new DynamicDialOwnershipStore.Receipt(deviceAddress,BundledDynamicDial.REMOTE_PATH,dynamicVariant); if(!transport.saveOwnershipReceipt(priorOwnership,next)){fail("recovery evidence verified but durable ownership receipt could not be saved");return;} finishCurrentPhase();running=false;step=Step.DONE;transport.log("DIAL RECOVERY VERIFIED receiptSaved=true identity="+dynamicPackageId+"/"+dynamicAlias+"; no remote write/install/apply");transport.terminal(true); }
            case ACQUISITION_CURRENT -> { String current=parseAlias(payload);if(!current.equals(acquisitionCurrentBefore))throw new IllegalArgumentException("acquisition current changed");step=Step.ACQUISITION_INFO;sendService("svc_dial_info",new byte[0]); }
            case ACQUISITION_INFO -> { validateAcquisitionInventory(payload,acquisitionCurrentBefore);String after=new String(payload,StandardCharsets.UTF_8);if(!transport.saveAcquisitionEvidence(acquisitionHash,acquisitionCurrentBefore,acquisitionInventoryBefore,acquisitionCurrentBefore,after))throw new IllegalArgumentException("acquisition evidence save");finishCurrentPhase();running=false;step=Step.DONE;transport.log("STAGING_ACQUISITION VERIFIED bytes="+CAPTURED_STAGING_BYTES+" sha256="+acquisitionHash+"; receipt unchanged; no remote write/install/apply");transport.terminal(true); }
            case FRAGMENT_CURRENT -> { if(!selectedCurrentIdentifierMatches(parseAlias(payload),"com.QC.bolt01","60001000"))throw new IllegalArgumentException("recovery fallback current changed");step=Step.FRAGMENT_INFO;sendService("svc_dial_info",new byte[0]); }
            case FRAGMENT_INFO -> { validateFragmentPreflight(payload,"com.QC.bolt01");armMutation();mutationStarted=true;offset=0;step=Step.OPEN_CACHE;sendFfi("open",arrayArg(finalPath+"\0"),u32(OPEN_WRITE_FLAGS)); }
            default -> throw new IllegalArgumentException("terminal state");
        }
    }

    private void openDirectTarget(){
        mutationStarted=true;offset=0;step=Step.OPEN_CACHE;sendFfi("open",arrayArg(finalPath+"\0"),u32(OPEN_WRITE_FLAGS));
    }
    private void handleDirectResponse(byte[] payload)throws Exception {
        switch(step){
            case INITIAL -> {initialIndex++;if(initialIndex<5)sendInitial();else{directHandshakeReady=true;openDirectTarget();}}
            case OPEN_CACHE -> {fd=ffiInt(payload);if(fd<0)throw new IllegalArgumentException("open "+fd);offset=0;step=Step.WRITE;sendWrite();}
            case WRITE -> {int count=ffiInt(payload);if(count!=requested)throw new IllegalArgumentException("short write "+count+"/"+requested);offset+=count;if(offset<image.length)sendWrite();else{step=Step.CLOSE_CACHE;sendFfi("close",u32(fd));}}
            case CLOSE_CACHE -> {if(ffiInt(payload)!=0)throw new IllegalArgumentException("close");step=Step.DIAL_INSTALL;sendFfi("svc_dial_install",arrayArg(finalPath+"\0"),arrayArg(dynamicAlias+"\0"));}
            case DIAL_INSTALL -> {if(ffiInt(payload)!=0)throw new IllegalArgumentException("dial install");applyStarted=true;step=Step.DIAL_APPLY;sendFfi("svc_dial_apply",arrayArg(dynamicAlias+"\0"));}
            case DIAL_APPLY -> {if(ffiInt(payload)!=0)throw new IllegalArgumentException("dial apply");step=Step.DIAL_CURRENT;sendService("svc_dial_get_current",new byte[0]);}
            case DIAL_CURRENT -> {
                if(!selectedCurrentIdentifierMatches(parseAlias(payload),dynamicPackageId,dynamicAlias))throw new IllegalArgumentException("current package identity");
                finishCurrentPhase();running=false;step=Step.DONE;
                transport.log("DIAL DIRECT COMPLETE: install/apply/current acknowledged; no backup/readback/ownership changes");transport.terminal(true);
            }
            default -> throw new IllegalArgumentException("direct terminal state");
        }
    }

    private String parseAlias(byte[] payload) throws Exception {
        String raw = new String(payload, StandardCharsets.UTF_8).trim();
        JSONTokener parser = new JSONTokener(raw);
        Object decoded = parser.nextValue();
        if (parser.nextClean() != 0) throw new IllegalArgumentException("alias JSON trailing data");
        String alias;
        if (decoded instanceof JSONObject) {
            alias = ((JSONObject) decoded).optString("alias", "").trim();
        } else if (decoded instanceof String) {
            alias = ((String) decoded).trim();
        } else {
            alias = "";
        }
        if (alias.isEmpty()) throw new IllegalArgumentException("current alias");
        return alias;
    }

    static boolean selectedCurrentIdentifierMatches(String value,String packageId,String alias){return value!=null&&(value.equals(packageId)||value.equals(alias));}

    private static boolean samePackageTuple(DynamicDialOwnershipStore.Receipt prior,String id,String alias,long size,String hash){
        return prior!=null&&prior.internalPackageId.equals(id)&&prior.alias.equals(alias)&&prior.packageSize==size&&prior.packageSha256.equals(hash);
    }
    private boolean isExactActiveTarget(String current){
        return samePackageTuple(priorOwnership,dynamicPackageId,dynamicAlias,image.length,sha256)&&selectedCurrentIdentifierMatches(current,dynamicPackageId,dynamicAlias);
    }
    private void finishAlreadyInstalled(){
        // Reached only after preflight, full staging read/hash and durable local backup.
        finishCurrentPhase();running=false;alreadyInstalled=true;step=Step.DONE;
        transport.log("DIAL ALREADY_INSTALLED: Этот циферблат уже установлен; full read/hash/local backup verified; no mutation/install/apply/receipt change");
        transport.progress("COMPLETE",0,0,"ALREADY_INSTALLED",false);transport.terminal(true);
    }

    private static boolean validateDialInfo(byte[] payload) throws Exception {
        JSONTokener parser = new JSONTokener(new String(payload, StandardCharsets.UTF_8).trim());
        Object value = parser.nextValue();
        if ((!(value instanceof JSONObject) && !(value instanceof JSONArray)) || parser.nextClean() != 0 || !containsAlbumIdentity(value)) {
            throw new IllegalArgumentException("dial info JSON");
        }
        return true;
    }

    private static boolean containsAlbumIdentity(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if ("com.QC.album".equals(object.optString("id")) && "60001001".equals(object.optString("alias"))) return true;
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) if (containsAlbumIdentity(object.get(keys.next()))) return true;
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) if (containsAlbumIdentity(array.get(i))) return true;
        }
        return false;
    }

    private static final class DialIdentity { final String id,alias;DialIdentity(String id,String alias){this.id=id;this.alias=alias;} }
    private static final class Preflight { final DialIdentity fallback; final long existingSize; final String[] existingHashes; final String evidenceSource,expectedIdentity,observedIdentity; Preflight(DialIdentity fallback,long existingSize,String[] existingHashes,String evidenceSource,String expectedIdentity,String observedIdentity){this.fallback=fallback;this.existingSize=existingSize;this.existingHashes=existingHashes;this.evidenceSource=evidenceSource;this.expectedIdentity=expectedIdentity;this.observedIdentity=observedIdentity;} }
    private static Preflight validateLocalPreflight(byte[] payload,String current,DynamicDialPackage target,DynamicDialOwnershipStore.Receipt prior,java.util.List<DynamicDialOwnershipStore.Receipt> known)throws Exception {
        localCurrentIdentity(current,prior);validateKnownLocalInventory(payload,prior.internalPackageId,prior.alias,known,true);
        JSONTokener parser=new JSONTokener(new String(payload,StandardCharsets.UTF_8));Object value=parser.nextValue();
        boolean sameIdentity=prior.internalPackageId.equals(target.internalPackageId)&&prior.alias.equals(target.alias);
        if(sameIdentity&&!samePackageTuple(prior,target.internalPackageId,target.alias,target.packageSize,target.packageSha256))throw new IllegalArgumentException("SAME_IDENTITY_DIFFERENT_PACKAGE");
        if(countIdentity(value,target.internalPackageId,target.alias)!=(sameIdentity?1:0)||containsCrossCollision(value,target.internalPackageId,target.alias))throw new IllegalArgumentException("local target identity collision");
        return new Preflight(new DialIdentity("com.QC.bolt01","60001000"),prior.packageSize,new String[]{prior.packageSha256},"validated durable prior receipt and history",prior.internalPackageId+"/"+prior.alias,joinIdentities(identities(value)));
    }
    private Preflight validatePendingPreflight(byte[] payload,String current)throws Exception {
        if(!selectedCurrentIdentifierMatches(current,dynamicPackageId,dynamicAlias))throw new IllegalArgumentException("RECOVERY_CURRENT_NOT_TARGET");
        validateKnownLocalInventory(payload,dynamicPackageId,dynamicAlias,java.util.Collections.singletonList(priorOwnership),true);
        return new Preflight(new DialIdentity("com.QC.bolt01","60001000"),image.length,new String[]{sha256},"exact pending target and prior receipt",dynamicPackageId+"/"+dynamicAlias,"KNOWN_PENDING_INVENTORY");
    }
    private static void validateKnownLocalInventory(byte[] payload,String requiredId,String requiredAlias,java.util.List<DynamicDialOwnershipStore.Receipt> known,boolean requireUser)throws Exception {
        if(payload==null||payload.length==0||payload.length>64*1024)throw new IllegalArgumentException("LOCAL_INVENTORY_LIMIT");
        JSONTokener parser=new JSONTokener(new String(payload,StandardCharsets.UTF_8));Object value=parser.nextValue();
        if(!(value instanceof JSONArray)||parser.nextClean()!=0)throw new IllegalArgumentException("local inventory JSON");
        JSONArray rows=(JSONArray)value;java.util.HashSet<String> ids=new java.util.HashSet<>(),aliases=new java.util.HashSet<>();boolean required=false,bolt=false;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null||!(row.opt("id") instanceof String)||!(row.opt("alias") instanceof String)||!(row.opt("group") instanceof String))throw new IllegalArgumentException("LOCAL_INVENTORY_INVALID");
            String id=row.getString("id"),alias=row.getString("alias"),group=row.getString("group");
            if(id.isEmpty()||alias.isEmpty()||!ids.add(id)||!aliases.add(alias)||containsCrossCollision(value,id,alias))throw new IllegalArgumentException("LOCAL_INVENTORY_COLLISION");
            if(!"system".equals(group)&&!"user".equals(group))throw new IllegalArgumentException("LOCAL_INVENTORY_UNKNOWN_GROUP");
            if("com.QC.bolt01".equals(id)||"60001000".equals(alias)){if(!"com.QC.bolt01".equals(id)||!"60001000".equals(alias)||!"system".equals(group))throw new IllegalArgumentException("local Bolt01 collision");bolt=true;}
            boolean target=requiredId.equals(id)&&requiredAlias.equals(alias);if(target){if(requireUser&&!"user".equals(group))throw new IllegalArgumentException("LOCAL_TARGET_NOT_USER");required=true;}
            if("user".equals(group)&&!target){boolean proven=false;for(DynamicDialOwnershipStore.Receipt receipt:known)if(receipt.internalPackageId.equals(id)&&receipt.alias.equals(alias)){proven=true;break;}if(!proven)throw new IllegalArgumentException("LOCAL_INVENTORY_UNKNOWN_USER");}
        }
        if(!bolt)throw new IllegalArgumentException("BOLT01_MISSING");if(!required)throw new IllegalArgumentException("LOCAL_REQUIRED_IDENTITY_MISSING");
    }
    private void captureLocalInventory(String stage,byte[] payload)throws java.io.IOException {if(installJournal!=null&&isLocalTarget())installJournal.inventory(stage,payload);}
    private static DialIdentity localCurrentIdentity(String current,DynamicDialOwnershipStore.Receipt prior){
        if(prior==null)throw new IllegalArgumentException("PRIOR_RECEIPT_REQUIRED");
        if(selectedCurrentIdentifierMatches(current,prior.internalPackageId,prior.alias))return new DialIdentity(prior.internalPackageId,prior.alias);
        if(selectedCurrentIdentifierMatches(current,"com.QC.bolt01","60001000"))return new DialIdentity("com.QC.bolt01","60001000");
        throw new IllegalArgumentException("CURRENT_NOT_OWNED");
    }
    private static Preflight validateDynamicPreflight(byte[] payload,String currentAlias,BundledDynamicDial.PackageVariant target,DynamicDialOwnershipStore.Receipt prior) throws Exception {
        JSONTokener parser = new JSONTokener(new String(payload, StandardCharsets.UTF_8).trim());
        Object value = parser.nextValue();
        if (!(value instanceof JSONObject)&&!(value instanceof JSONArray))throw new IllegalArgumentException("dial info JSON type");
        if (parser.nextClean() != 0) throw new IllegalArgumentException("dial info trailing data");
        int matching=countIdentity(value,target.internalPackageId,target.alias);
        if(matching>1)throw new IllegalArgumentException("duplicate target identity");
        if(containsCrossCollision(value,target.internalPackageId,target.alias))throw new IllegalArgumentException("target split or cross identity collision");
        DialIdentity priorIdentity=prior==null?new DialIdentity("com.gtdial.pipboy01",BundledDynamicDial.REMOTE_ALIAS):new DialIdentity(prior.internalPackageId,prior.alias);
        long existingSize=prior==null?EXISTING_DYNAMIC_BYTES:prior.packageSize;
        String[] existingHashes=new String[]{prior==null?EXISTING_DYNAMIC_SHA256:prior.packageSha256};
        String evidenceSource=prior==null?"missing receipt: legacy fixed tuple":"durable ownership receipt";
        if(prior==null&&countIdentity(value,priorIdentity.id,priorIdentity.alias)!=1){
            if(countIdentity(value,MISSING_RECEIPT_VIOLET_IDENTITY.id,MISSING_RECEIPT_VIOLET_IDENTITY.alias)!=1||containsCrossCollision(value,MISSING_RECEIPT_VIOLET_IDENTITY.id,MISSING_RECEIPT_VIOLET_IDENTITY.alias))throw new IllegalArgumentException("missing receipt: neither legacy nor unique captured violet tuple is present");
            priorIdentity=MISSING_RECEIPT_VIOLET_IDENTITY; existingSize=MISSING_RECEIPT_VIOLET_BYTES; existingHashes=new String[]{MISSING_RECEIPT_VIOLET_HISTORICAL_SHA256,MISSING_RECEIPT_VIOLET_COMPILED_SHA256}; evidenceSource="missing receipt: unique captured violet inventory tuple";
        }
        if(countIdentity(value,priorIdentity.id,priorIdentity.alias)!=1||containsCrossCollision(value,priorIdentity.id,priorIdentity.alias))throw new IllegalArgumentException("prior ownership identity absent, duplicated or colliding");
        java.util.ArrayList<DialIdentity> current=new java.util.ArrayList<>();for(DialIdentity identity:identities(value))if(identity.alias.equals(currentAlias)||identity.id.equals(currentAlias))current.add(identity);
        if(currentAlias==null||currentAlias.isEmpty()||current.size()!=1)throw new IllegalArgumentException("current dial is not unambiguous in inventory");
        boolean sameIdentity=prior!=null&&prior.internalPackageId.equals(target.internalPackageId)&&prior.alias.equals(target.alias);
        if(sameIdentity&&!samePackageTuple(prior,target.internalPackageId,target.alias,target.packageSize,target.packageSha256))throw new IllegalArgumentException("SAME_IDENTITY_DIFFERENT_PACKAGE");
        DialIdentity fallback=current.get(0);
        if(fallback.id.equals(target.internalPackageId)&&fallback.alias.equals(target.alias)&&!sameIdentity)throw new IllegalArgumentException("active target has no matching durable ownership receipt");
        if(prior!=null
                && "com.gtdial.dial3000anim.violet".equals(prior.internalPackageId)
                && "2909202626".equals(prior.alias)
                && prior.packageSize==547670L
                && "ae57c97378485a158015486832c94cd287dd1bca3663a38f2c91ac84f0497655".equals(prior.packageSha256)
                && "com.gtdial.dial3000anim2.violet".equals(target.internalPackageId)
                && "2909202636".equals(target.alias)
                && target.packageSize==547672L
                && "73ddde4c13854ff092e8c90eaa2bf0be0a1bd4ed107b407b7f4d1b5cbe01335a".equals(target.packageSha256)){
            if(!fallback.id.equals(prior.internalPackageId)||!fallback.alias.equals(prior.alias)
                    ||matching!=0||countIdentity(value,"com.QC.bolt01","60001000")!=1
                    ||containsCrossCollision(value,"com.QC.bolt01","60001000"))
                throw new IllegalArgumentException("captured animation replacement requires unique current and built-in Bolt01");
            fallback=new DialIdentity("com.QC.bolt01","60001000");
            evidenceSource="exact captured animation replacement; Bolt01 must survive install, not applied as current";
        }
        if ("com.gtdial.dial3000".equals(target.internalPackageId)
                && "71c6c18f914f8b0caee64faf9f3cad26faddd059a2308846e3431a6265713326".equals(target.packageSha256)
                && prior != null && "com.gtdial.dl3000".equals(prior.internalPackageId)
                && "2309202601".equals(prior.alias) && prior.packageSize == 335897L
                && "2154c496a8fb7176d25847418a332d8e67cdd493013753e4d009dd64558544d9".equals(prior.packageSha256)
                && "com.gtdial.dl3000".equals(fallback.id) && "2309202601".equals(fallback.alias)
                && matching == 0) {
            existingSize = 485421L;
            existingHashes = new String[]{"f2bbe57a943e71f429d1f0fe0c0686ca80df32701cd80e47b226908593a8354c"};
            evidenceSource = "exact failed shared-base v4 staging recovery";
        }
        if(prior!=null&&selectedCurrentIdentifierMatches(currentAlias,prior.internalPackageId,prior.alias)){
            if(!(value instanceof JSONArray)||countIdentity(value,"com.QC.bolt01","60001000")!=1||containsCrossCollision(value,"com.QC.bolt01","60001000"))throw new IllegalArgumentException("replacement requires unique built-in Bolt01");
            boolean systemFallback=false;
            JSONArray rows=(JSONArray)value;
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.optJSONObject(i);if(row==null)throw new IllegalArgumentException("replacement inventory item");
                if("com.QC.bolt01".equals(row.optString("id"))&&"60001000".equals(row.optString("alias")))systemFallback="system".equals(row.optString("group"));
                if("user".equals(row.optString("group"))&&(!prior.internalPackageId.equals(row.optString("id"))||!prior.alias.equals(row.optString("alias"))))throw new IllegalArgumentException("replacement contains another user dial");
            }
            if(!systemFallback)throw new IllegalArgumentException("replacement fallback is not system dial");
            // Installing replaces the user slot; only the built-in fallback must survive.
            fallback=new DialIdentity("com.QC.bolt01","60001000");
        }
        return new Preflight(fallback,existingSize,existingHashes,evidenceSource,priorIdentity.id+"/"+priorIdentity.alias,joinIdentities(identities(value)));
    }

    private static final String RECOVERY_ANIMATION_ID="com.gtdial.dial3000anim.violet";
    private static final String RECOVERY_ANIMATION_ALIAS="2909202626";
    private static final long RECOVERY_ANIMATION_SIZE=547670L;
    private static final String RECOVERY_ANIMATION_SHA256="ae57c97378485a158015486832c94cd287dd1bca3663a38f2c91ac84f0497655";
    private static final String RECOVERY_STABLE_ID="com.gtdial.dial3000v3.violet";
    private static final String RECOVERY_STABLE_ALIAS="2909202616";
    private static final long RECOVERY_STABLE_SIZE=491927L;
    private static final String RECOVERY_STABLE_SHA256="c3296a696a2e8dc6cc0bd63e41d9151ef3e113fa8ac10cd6defd591cb1bf8e45";

    private static boolean isAcceptedRecoveryReceipt(DynamicDialOwnershipStore.Receipt receipt){
        return (RECOVERY_STABLE_ID.equals(receipt.internalPackageId)&&RECOVERY_STABLE_ALIAS.equals(receipt.alias)&&receipt.packageSize==RECOVERY_STABLE_SIZE&&RECOVERY_STABLE_SHA256.equals(receipt.packageSha256))
                || (RECOVERY_ANIMATION_ID.equals(receipt.internalPackageId)&&RECOVERY_ANIMATION_ALIAS.equals(receipt.alias)&&receipt.packageSize==RECOVERY_ANIMATION_SIZE&&RECOVERY_ANIMATION_SHA256.equals(receipt.packageSha256));
    }

    private static Preflight validateRecoveryPreflight(byte[] payload,String currentAlias,BundledDynamicDial.PackageVariant target,DynamicDialOwnershipStore.Receipt prior) throws Exception {
        if(!RECOVERY_ANIMATION_ID.equals(target.internalPackageId)||!RECOVERY_ANIMATION_ALIAS.equals(target.alias)||target.packageSize!=RECOVERY_ANIMATION_SIZE||!RECOVERY_ANIMATION_SHA256.equals(target.packageSha256))throw new IllegalArgumentException("recovery target is not captured animation tuple");
        validateRecoveryInventory(payload,target);
        if(!selectedCurrentIdentifierMatches(currentAlias,target.internalPackageId,target.alias))throw new IllegalArgumentException("recovery current is not captured animation tuple");
        return new Preflight(new DialIdentity("com.QC.bolt01","60001000"),RECOVERY_ANIMATION_SIZE,new String[]{RECOVERY_ANIMATION_SHA256},prior==null?"missing receipt: captured animation evidence":"accepted same-device recovery receipt",target.internalPackageId+"/"+target.alias,joinIdentities(identities(new JSONTokener(new String(payload,StandardCharsets.UTF_8).trim()).nextValue())));
    }

    private static void validateRecoveryInventory(byte[] payload,BundledDynamicDial.PackageVariant target) throws Exception {
        JSONTokener parser=new JSONTokener(new String(payload,StandardCharsets.UTF_8).trim());Object value=parser.nextValue();
        if((!(value instanceof JSONObject)&&!(value instanceof JSONArray))||parser.nextClean()!=0)throw new IllegalArgumentException("recovery dial info JSON");
        if(countIdentity(value,target.internalPackageId,target.alias)!=1||containsCrossCollision(value,target.internalPackageId,target.alias))throw new IllegalArgumentException("recovery animation inventory missing, duplicate or colliding");
        if(countIdentity(value,"com.QC.bolt01","60001000")!=1||containsCrossCollision(value,"com.QC.bolt01","60001000"))throw new IllegalArgumentException("recovery Bolt01 inventory missing, duplicate or colliding");
    }

    private static void validateAcquisitionInventory(byte[] payload,String current) throws Exception {
        JSONTokener parser=new JSONTokener(new String(payload,StandardCharsets.UTF_8).trim());
        Object value=parser.nextValue();
        if(!(value instanceof JSONArray)||parser.nextClean()!=0)throw new IllegalArgumentException("acquisition inventory JSON");
        String id="com.gtdial.dial3000anim2.phosphor",alias="2909202631";
        if(!selectedCurrentIdentifierMatches(current,id,alias)||countIdentity(value,id,alias)!=1||containsCrossCollision(value,id,alias))throw new IllegalArgumentException("acquisition dial identity");
        if(countIdentity(value,"com.QC.bolt01","60001000")!=1||containsCrossCollision(value,"com.QC.bolt01","60001000"))throw new IllegalArgumentException("acquisition Bolt01 identity");
        JSONArray rows=(JSONArray)value;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);
            if(row==null)throw new IllegalArgumentException("acquisition inventory item");
            if("com.QC.bolt01".equals(row.optString("id"))&&!"system".equals(row.optString("group")))throw new IllegalArgumentException("acquisition fallback is not system dial");
            if("user".equals(row.optString("group"))&&(!id.equals(row.optString("id"))||!alias.equals(row.optString("alias"))))throw new IllegalArgumentException("acquisition other user dial");
        }
    }

    private static Preflight validateFragmentPreflight(byte[] payload,String current)throws Exception{
        if(!selectedCurrentIdentifierMatches(current,"com.QC.bolt01","60001000"))throw new IllegalArgumentException("select built-in Bolt01 on watch before recovery");
        validateAcquisitionInventory(payload,"com.gtdial.dial3000anim2.phosphor");
        return new Preflight(new DialIdentity("com.QC.bolt01","60001000"),CAPTURED_STAGING_BYTES,new String[]{CAPTURED_FRAGMENT_SHA256},"exact captured truncated legacy staging recovery","com.gtdial.dial3000anim2.phosphor/2909202631",joinIdentities(identities(new JSONTokener(new String(payload,StandardCharsets.UTF_8)).nextValue())));
    }

    static void validateFragmentEvidence(byte[] binary,byte[] metadata)throws Exception{
        throw new IllegalArgumentException("FRAGMENT_RECOVERY_UNAVAILABLE_PUBLIC_RELEASE");
    }

    private static String joinIdentities(java.util.List<DialIdentity> values){StringBuilder out=new StringBuilder();for(DialIdentity value:values){if(out.length()>0)out.append(',');out.append(value.id).append('/').append(value.alias);}return out.toString();}

    private static int countIdentity(Object value, String id, String alias) throws Exception {
        if (value instanceof JSONObject) { JSONObject o=(JSONObject)value; int n=id.equals(o.optString("id")) && alias.equals(o.optString("alias")) ? 1 : 0; java.util.Iterator<String> k=o.keys(); while(k.hasNext()) n+=countIdentity(o.get(k.next()),id,alias); return n; }
        if (value instanceof JSONArray) { int n=0; JSONArray a=(JSONArray)value; for(int i=0;i<a.length();i++) n+=countIdentity(a.get(i),id,alias); return n; }
        return 0;
    }
    private static boolean containsCrossCollision(Object value, String id, String alias) throws Exception {
        if (value instanceof JSONObject) { JSONObject o=(JSONObject)value; String gotId=o.optString("id"), gotAlias=o.optString("alias"); if ((id.equals(gotId) || alias.equals(gotId) || id.equals(gotAlias) || alias.equals(gotAlias)) && !(id.equals(gotId) && alias.equals(gotAlias))) return true; java.util.Iterator<String> k=o.keys(); while(k.hasNext()) if(containsCrossCollision(o.get(k.next()),id,alias)) return true; }
        else if(value instanceof JSONArray) { JSONArray a=(JSONArray)value; for(int i=0;i<a.length();i++) if(containsCrossCollision(a.get(i),id,alias)) return true; }
        return false;
    }

    static boolean verifiedLocalCurrent(String current,String inventory,DynamicDialOwnershipStore.Receipt receipt){return verifiedLocalCurrent(current,inventory,receipt,java.util.Collections.emptyList());}
    static boolean verifiedLocalCurrent(String current,String inventory,DynamicDialOwnershipStore.Receipt receipt,java.util.List<DynamicDialOwnershipStore.Receipt> known){
        try{
            if(receipt==null||receipt.schemaVersion!=2||!selectedCurrentIdentifierMatches(current,receipt.internalPackageId,receipt.alias))return false;
            validateKnownLocalInventory(inventory.getBytes(StandardCharsets.UTF_8),receipt.internalPackageId,receipt.alias,known,true);return true;
        }catch(Exception e){return false;}
    }

    private static boolean containsIdOrAlias(Object value, String id, String alias) throws Exception {
        if (value instanceof JSONObject) { JSONObject o=(JSONObject)value; if (id.equals(o.optString("id")) || alias.equals(o.optString("id")) || id.equals(o.optString("alias")) || alias.equals(o.optString("alias"))) return true; java.util.Iterator<String> k=o.keys(); while(k.hasNext()) if(containsIdOrAlias(o.get(k.next()),id,alias)) return true; }
        else if (value instanceof JSONArray) { JSONArray a=(JSONArray)value; for(int i=0;i<a.length();i++) if(containsIdOrAlias(a.get(i),id,alias)) return true; }
        return false;
    }

    private static void validateInstalledDynamic(byte[] payload,String id,String alias,String fallbackId,String fallbackAlias) throws Exception {
        JSONTokener parser = new JSONTokener(new String(payload, StandardCharsets.UTF_8).trim());
        Object value = parser.nextValue();
        if(!(value instanceof JSONObject)&&!(value instanceof JSONArray))throw new IllegalArgumentException("installed info JSON type");
        if (parser.nextClean() != 0) throw new IllegalArgumentException("installed info trailing data");
        if(countIdentity(value,id,alias)!=1||containsCrossCollision(value,id,alias))throw new IllegalArgumentException("installed identity mismatch");
        if(countIdentity(value,fallbackId,fallbackAlias)!=1||containsCrossCollision(value,fallbackId,fallbackAlias))throw new IllegalArgumentException("fallback identity missing or colliding after install");
    }

    private static java.util.List<DialIdentity> identities(Object value)throws Exception{java.util.ArrayList<DialIdentity> out=new java.util.ArrayList<>();collectIdentities(value,out);return out;}
    private static void collectIdentities(Object value,java.util.List<DialIdentity> out)throws Exception{
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;String id=o.optString("id","").trim(),alias=o.optString("alias","").trim();if(!id.isEmpty()&&!alias.isEmpty())out.add(new DialIdentity(id,alias));java.util.Iterator<String> keys=o.keys();while(keys.hasNext())collectIdentities(o.get(keys.next()),out);}
        else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)collectIdentities(a.get(i),out);}
    }

    static ExistingDialAction classifyExistingDial(long existingSize,String existingHash,long targetSize,String targetHash){
        return classifyExistingDial(existingSize,existingHash,targetSize,targetHash,EXISTING_DYNAMIC_BYTES,EXISTING_DYNAMIC_SHA256);
    }
    static ExistingDialAction classifyExistingDial(long existingSize,String existingHash,long targetSize,String targetHash,long approvedSize,String approvedHash){
        if(existingSize!=approvedSize||!approvedHash.equals(existingHash))return ExistingDialAction.REJECT;
        return existingSize==targetSize&&existingHash.equals(targetHash)?ExistingDialAction.REUSE:ExistingDialAction.REPLACE;
    }
    private boolean approvedExistingHash(String value){if(existingExpectedHashes==null)return false;for(String expected:existingExpectedHashes)if(expected.equals(value))return true;return false;}
    private boolean existingSizeMatchesTarget(String existingHash){return existingExpectedSize==image.length&&existingHash.equals(sha256);}
    private boolean isLocalTarget(){return dynamicTarget!=null&&dynamicTarget.origin==DynamicDialPackage.Origin.LOCAL_PROFILE_BUILD;}
    private void armMutation(){
        if(pendingRecovery!=null)throw new IllegalArgumentException("RECOVERY_CANNOT_ARM");
        if(mutationArmed)return;
        if(installJournal!=null)try{installJournal.event("ARM","ATTEMPT");installJournal.sync();}catch(java.io.IOException e){throw new IllegalArgumentException("LOCAL_LOG_FAILED",e);}
        if(verifiedBackupHash==null||!transport.armDynamicMutation(operationId,priorOwnership,dynamicTarget,verifiedBackupHash,existingExpectedSize))throw new IllegalArgumentException("durable dynamic mutation marker not armed");
        mutationArmed=true;
        if(installJournal!=null)try{installJournal.hashEvent("ARM_VERIFIED",verifiedBackupHash,existingExpectedSize);}catch(java.io.IOException e){throw new IllegalArgumentException("LOCAL_LOG_FAILED",e);}
    }
    private void beginDialInstall(){armMutation();applyStarted=true;step=Step.DIAL_INSTALL;sendFfi("svc_dial_install",arrayArg(finalPath+"\0"),arrayArg(dynamicAlias+"\0"));}

    private static boolean containsIdentity(Object value, String id, String alias) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            if (id.equals(o.optString("id")) && alias.equals(o.optString("alias"))) return true;
            java.util.Iterator<String> keys = o.keys(); while (keys.hasNext()) if (containsIdentity(o.get(keys.next()), id, alias)) return true;
        } else if (value instanceof JSONArray) { JSONArray a=(JSONArray)value; for(int i=0;i<a.length();i++) if(containsIdentity(a.get(i),id,alias)) return true; }
        return false;
    }

    private static boolean containsAlias(Object value, String alias) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject o=(JSONObject)value; if(alias.equals(o.optString("alias")) || alias.equals(o.optString("id"))) return true;
            java.util.Iterator<String> keys=o.keys(); while(keys.hasNext()) if(containsAlias(o.get(keys.next()),alias)) return true;
        } else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)if(containsAlias(a.get(i),alias))return true;}
        return false;
    }

    private void sendApply40103() throws Exception {
        byte[] inner = LauncherEnvelopeCodec.innerJson(40103, new JSONObject().put("state", 1), -1);
        sendService("msg_recv_ubjson", LauncherEnvelopeCodec.outgoing("launcher", inner, System.currentTimeMillis()));
    }

    private void sendApply40101() throws Exception {
        JSONObject item = new JSONObject().put("index", 1).put("name", name);
        JSONArray list = new JSONArray().put(item);
        JSONObject data = new JSONObject().put("type", 1).put("positionIndex", positionIndex)
                .put("styleIndex", 0).put("color", color).put("imageWatchFaceList", list);
        byte[] inner = LauncherEnvelopeCodec.innerJson(40101, data, applyTaskId);
        sendService("msg_recv_ubjson", LauncherEnvelopeCodec.outgoing("launcher", inner, System.currentTimeMillis()));
    }

    private static void requireDirectSuccess(byte[] payload) {
        if (payload == null || payload.length != 2 || (payload[1] & 255) != LauncherEnvelopeCodec.SUCCESS) {
            throw new IllegalArgumentException("direct launcher response");
        }
    }

    private void handleIncomingRequest(byte[] frame) {
        try {
            scheduler.removeCallbacks(timeout);
            if (frame[6] != 1 || frame[7] != 0) throw new IllegalArgumentException("request addressing");
            if ((frame[9] & 0x30) != 0x10) throw new IllegalArgumentException("request flags");
            byte[] rpc = Arrays.copyOfRange(frame, 10, frame.length - 3);
            int zero = -1;
            for (int i = 0; i < rpc.length; i++) if (rpc[i] == 0) { zero = i; break; }
            if (zero <= 0 || zero + 1 >= rpc.length || !"msg_send_ubjson".equals(new String(rpc, 0, zero, StandardCharsets.UTF_8)) || rpc[zero + 1] != 1) throw new IllegalArgumentException("incoming service");
            byte[] envelope = Arrays.copyOfRange(rpc, zero + 2, rpc.length);
            LauncherEnvelopeCodec.Incoming incoming = LauncherEnvelopeCodec.parseIncoming(envelope);
            byte[] nested = incomingReassembler.accept(incoming);
            sendIncomingResponse(frame);
            if (nested == null) { scheduler.postDelayed(timeout, 8_000); return; }
            Object decoded = UbjsonCodec.decode(nested);
            if (!(decoded instanceof Map)) throw new IllegalArgumentException("nested msg");
            Map<?, ?> message = (Map<?, ?>) decoded;
            if (!Long.valueOf(40101L).equals(message.get("intent")) || !Long.valueOf(applyTaskId).equals(message.get("taskId"))) throw new IllegalArgumentException("nested intent/task");
            Object data = message.get("data");
            if (!(data instanceof Map) || !( ((Map<?, ?>) data).get("count") instanceof Long) || ((Long) ((Map<?, ?>) data).get("count")) < 0) throw new IllegalArgumentException("nested count");
            scheduler.removeCallbacks(timeout); step = Step.POST_ALIAS; sendService("svc_dial_get_current", new byte[0]);
        } catch (Exception e) { fail("incoming app response: " + e.getMessage()); }
    }

    private void sendIncomingResponse(byte[] request) {
        byte[] d2d = new byte[6]; d2d[0] = 1; d2d[1] = 0; d2d[2] = 1; d2d[3] = request[8]; d2d[4] = (byte) ((request[9] & 0x0F) | 0x40); d2d[5] = (byte) LauncherEnvelopeCodec.SUCCESS;
        byte[] out = frame(d2d); if (out.length > maxFrameBytes || !transport.write(out)) throw new IllegalArgumentException("incoming response TX");
    }

    private void verifyAppliedConfig(String raw) throws Exception {
        JSONObject object = new JSONObject(raw); Object type = object.opt("type");
        if (!(type instanceof Number) || ((Number) type).intValue() != 1 || object.optInt("positionIndex", -1) != positionIndex || object.optInt("styleIndex", -1) != 0 || object.optInt("color", Integer.MIN_VALUE) != color) throw new IllegalArgumentException("applied image config");
        JSONArray list = object.optJSONArray("imageWatchFaceList"); if (list == null) throw new IllegalArgumentException("applied image list");
        boolean found = false; for (int i = 0; i < list.length(); i++) { JSONObject item = list.optJSONObject(i); if (item != null && item.optInt("index", -1) == 1 && name.equals(item.optString("name", ""))) found = true; }
        if (!found) throw new IllegalArgumentException("applied image entry");
    }

    private void sendInitial() {
        String current = INITIAL[initialIndex];
        if (current.equals("_link_up")){journalFfi="_link_up";sendService("ffi",concat(arrayArg("_link_up")));}
        else if (current.equals("_link_up2")) sendService("_link_up2", "{\"version\":66560}".getBytes(StandardCharsets.UTF_8));
        else if(current.startsWith("udbd.")){journalFfi="_dev_info";sendService("ffi",concat(arrayArg("_dev_info"),arrayArg(current+"\0"),editable(32)));}
        else sendService(current, new byte[0]);
    }

    private void sendWrite() { requested = Math.min(CHUNK, image.length - offset); sendFfi("write_by_offset", u32(fd), u32(offset), arrayArg(Arrays.copyOfRange(image, offset, offset + requested)), u32(requested)); }
    private void sendExistingRead() { requested = (int)Math.min(CHUNK, existingExpectedSize - offset); sendFfi("read_by_offset", u32(fd), u32(offset), editable(requested), u32(requested)); }
    private void sendRead() { requested = Math.min(CHUNK, image.length - offset); sendFfi("read_by_offset", u32(fd), u32(offset), editable(requested), u32(requested)); }
    private void sendFfi(String function, byte[]... args) { if(pendingRecovery!=null&&!Arrays.asList("access","open","lseek","read_by_offset","close").contains(function))throw new IllegalStateException("RECOVERY_COMMAND_BLOCKED");if(pendingRecovery!=null&&"open".equals(function)&&!Arrays.equals(args[1],u32(0)))throw new IllegalStateException("RECOVERY_WRITE_OPEN_BLOCKED"); if(acquisitionMode&&!Arrays.asList("access","open","lseek","read_by_offset","close").contains(function))throw new IllegalStateException("acquisition command blocked: "+function);journalFfi=function;byte[][] all=new byte[args.length+1][];all[0]=arrayArg(function);System.arraycopy(args,0,all,1,args.length);sendService("ffi",concat(all)); }
    private void sendService(String service, byte[] payload) {
        if (!running) return;
        if(pendingRecovery!=null&&!Arrays.asList("ffi","svc_dial_get_current","svc_dial_info").contains(service))throw new IllegalStateException("RECOVERY_SERVICE_BLOCKED");
        if(acquisitionMode&&!Arrays.asList("ffi","_link_up2","svc_dial_get_current","svc_dial_info").contains(service))throw new IllegalStateException("acquisition service blocked: "+service);
        publishProgress();
        journalService=service;if(!"ffi".equals(service))journalFfi="";
        if (lastLoggedPhase != step) {
            finishCurrentPhase();
            lastLoggedPhase = step;
            phaseStartedAtMs = SystemClock.elapsedRealtime();
            phaseStartRequests = requestCount;
            phaseStartTxBytes = txBytes;
            phaseStartRxBytes = rxBytes;
            phaseResponseCount = 0;
            phaseResponseLatencyTotalMs = 0;
            phaseResponseLatencyMinMs = Long.MAX_VALUE;
            phaseResponseLatencyMaxMs = 0;
            transport.log("STAGE phase=" + step + " elapsedMs=" + (phaseStartedAtMs - startedAtMs) + " bytes=" + offset + "/" + image.length + " requests=" + requestCount);
        }
        requestCount++;
        byte[] nameBytes = service.getBytes(StandardCharsets.UTF_8);
        byte[] rpc = new byte[nameBytes.length + 2 + payload.length]; System.arraycopy(nameBytes, 0, rpc, 0, nameBytes.length); rpc[nameBytes.length] = 0; rpc[nameBytes.length + 1] = 1; System.arraycopy(payload, 0, rpc, nameBytes.length + 2, payload.length);
        expectedId = packetId & 255;
        transport.log("DIAL TX service="+service+" id="+expectedId+" phase="+step+" offset="+offset+" count="+requested);
        byte[] d2d = new byte[5 + rpc.length]; d2d[0] = 1; d2d[1] = 0; d2d[2] = 1; d2d[3] = (byte) expectedId; d2d[4] = 0x10; System.arraycopy(rpc, 0, d2d, 5, rpc.length);
        byte[] out = frame(d2d); packetId = (packetId + 1) & 255;
        if (out.length > maxFrameBytes) { fail("TX frame exceeds negotiated MTU: " + out.length + ">" + maxFrameBytes); return; }
        if(!journalFrame("TX",out,"ATTEMPT",""))return;
        if(installJournal!=null&&step==Step.OPEN_CACHE)try{installJournal.sync();}catch(java.io.IOException|OutOfMemoryError e){fail("LOCAL_LOG_FAILED");return;}
        long timeoutMs = step == Step.DIAL_INSTALL ? 30_000L : step == Step.DIAL_APPLY ? 10_000L : 8_000L;
        txBytes += out.length;
        requestSentAtMs = SystemClock.elapsedRealtime();
        if (!transport.write(out)) { requestSentAtMs = 0; fail("Android rejected TX"); } else scheduler.postDelayed(timeout, timeoutMs);
    }

    private void recordResponseLatency() { if (requestSentAtMs <= 0 || lastLoggedPhase == null) return; long latency = Math.max(0L, SystemClock.elapsedRealtime() - requestSentAtMs); requestSentAtMs = 0; phaseResponseCount++; phaseResponseLatencyTotalMs += latency; phaseResponseLatencyMinMs = Math.min(phaseResponseLatencyMinMs, latency); phaseResponseLatencyMaxMs = Math.max(phaseResponseLatencyMaxMs, latency); }
    private void finishCurrentPhase() { if (lastLoggedPhase == null) return; long now = SystemClock.elapsedRealtime(); long responses = phaseResponseCount; transport.log("STAGE phaseSummary=" + lastLoggedPhase + " durationMs=" + (now - phaseStartedAtMs) + " requests=" + (requestCount - phaseStartRequests) + " txBytes=" + (txBytes - phaseStartTxBytes) + " rxBytes=" + (rxBytes - phaseStartRxBytes) + " responses=" + responses + " responseMsMin=" + (responses == 0 ? -1 : phaseResponseLatencyMinMs) + " responseMsAvg=" + (responses == 0 ? -1 : phaseResponseLatencyTotalMs / responses) + " responseMsMax=" + (responses == 0 ? -1 : phaseResponseLatencyMaxMs)); lastLoggedPhase = null; requestSentAtMs = 0; }
    private boolean journalEvent(String event,String outcome){
        if(installJournal==null)return true;
        try{installJournal.record(event,step.name(),journalService,journalFfi,expectedId,-1,-1,offset,requested,outcome,"");return true;}
        catch(java.io.IOException|OutOfMemoryError e){fail("LOCAL_LOG_FAILED");return false;}
    }
    private boolean journalHash(String event,String hash,long size){
        if(installJournal==null)return true;
        try{installJournal.hashEvent(event,hash,size);return true;}catch(java.io.IOException|OutOfMemoryError e){fail("LOCAL_LOG_FAILED");return false;}
    }
    private boolean journalCurrentPair(String event){
        if(installJournal==null)return true;
        try{installJournal.currentPair(event,localInitialCurrent.id,localInitialCurrent.alias,localCurrentMode);return true;}
        catch(java.io.IOException|OutOfMemoryError e){fail("LOCAL_LOG_FAILED");return false;}
    }
    private boolean journalFrame(String event,byte[] frame,String outcome,String error){
        if(installJournal==null)return true;
        try{installJournal.record(event,step.name(),journalService,journalFfi,frame[8]&255,(frame[9]&255)>>>6,
            ((frame[frame.length-3]&255)<<8)|(frame[frame.length-2]&255),offset,requested,outcome,error);return true;}
        catch(java.io.IOException|OutOfMemoryError e){fail("LOCAL_LOG_FAILED");return false;}
    }
    boolean isLocalOperation(){return installJournal!=null&&isLocalTarget();}
    String terminalError(){return terminalError;}
    private void fail(String reason) {
        if(!running||step==Step.DONE||step==Step.FAILED)return;scheduler.removeCallbacks(timeout);terminalError=fixedFailure(reason);
        if(directInstallMode)terminalError+=" phase="+step+" service="+journalService+" ffi="+journalFfi+" rpc="+expectedId+" offset="+offset+" count="+requested;
        if(installJournal!=null)try{installJournal.record("FAILURE",step.name(),journalService,journalFfi,expectedId,-1,-1,offset,requested,"FAILED",terminalError);}
        catch(java.io.IOException|OutOfMemoryError e){transport.log("LOCAL_LOG_FAILED while recording protocol failure");}
        transport.progress("FAILED",offset,0,terminalError,mutationStarted);
        finishCurrentPhase();running=false;step=Step.FAILED;
        transport.log((dynamicDialMode?"DIAL":"STAGE")+" FAILED: "+reason+(mutationStarted?"; uploaded file may remain":"")+(applyStarted?"; watch-face outcome may be unknown":"")+"; elapsedMs="+(SystemClock.elapsedRealtime()-startedAtMs)+" requests="+requestCount+" txBytes="+txBytes+" rxBytes="+rxBytes+"; no automatic retry/delete");transport.terminal(false);
    }
    private void publishProgress(){
        if(directInstallMode){
            String phase=switch(step){case OPEN_CACHE,WRITE,CLOSE_CACHE->"UPLOAD";case DIAL_INSTALL->"INSTALL";case DIAL_APPLY,DIAL_CURRENT->"APPLY";default->"PREPARE";};
            long total="UPLOAD".equals(phase)?image.length:0,done=total==0?0:Math.min(offset,total),now=SystemClock.elapsedRealtime();
            if(!phase.equals(lastProgressPhase)||done!=lastProgressBytes&&(done==total||now-lastProgressAt>=250)){
                lastProgressPhase=phase;lastProgressBytes=done;lastProgressAt=now;transport.progress(phase,done,total,"",mutationStarted);
            }
            return;
        }
        String phase=pendingRecovery!=null?(step==Step.LOCAL_RECHECK_CURRENT||step==Step.LOCAL_RECHECK_INFO?"RECOVERY_RECHECK":step==Step.READ_FINAL||step==Step.OPEN_FINAL||step==Step.LENGTH_FINAL||step==Step.CLOSE_FINAL?"RECOVERY_READ":"RECOVERY_CHECK"):fastBackup&&(step==Step.ACCESS_BEFORE||step==Step.OPEN_EXISTING||step==Step.SEEK_EXISTING||step==Step.CLOSE_EXISTING)?"BACKUP_REUSE":switch(step){
            case OPEN_EXISTING,SEEK_EXISTING,LENGTH_EXISTING,READ_EXISTING,CLOSE_EXISTING,ACCESS_BEFORE->"BACKUP";
            case LOCAL_RECHECK_CURRENT,LOCAL_RECHECK_INFO->"RECHECK";
            case OPEN_CACHE,WRITE,CLOSE_CACHE->"UPLOAD";
            case ACCESS_FINAL,OPEN_FINAL,LENGTH_FINAL,READ_FINAL,CLOSE_FINAL->"VERIFY";
            case DIAL_INSTALL,DIAL_INFO->"INSTALL";
            case DIAL_APPLY,DIAL_CURRENT->"APPLY";
            default->"CHECK";
        };
        long total="RECOVERY_READ".equals(phase)?image.length:"BACKUP".equals(phase)?existingExpectedSize:"UPLOAD".equals(phase)||"VERIFY".equals(phase)?image.length:0;
        long done=total==0?0:("VERIFY".equals(phase)||"RECOVERY_READ".equals(phase))&&step!=Step.READ_FINAL&&step!=Step.CLOSE_FINAL?0:
            "BACKUP".equals(phase)&&step!=Step.READ_EXISTING&&step!=Step.CLOSE_EXISTING?0:Math.min(offset,total),now=SystemClock.elapsedRealtime();
        if(!phase.equals(lastProgressPhase)||done!=lastProgressBytes&&(done==total||now-lastProgressAt>=250)){
            lastProgressPhase=phase;lastProgressBytes=done;lastProgressAt=now;
            transport.progress(phase,done,total,"",mutationStarted);
        }
    }
    private static String fixedFailure(String reason){
        if(reason.contains("SAME_IDENTITY_DIFFERENT_PACKAGE"))return "SAME_IDENTITY_DIFFERENT_PACKAGE";
        for(String code:new String[]{"CURRENT_NOT_OWNED","CURRENT_CHANGED","BOLT01_MISSING","PRIOR_RECEIPT_REQUIRED","RECOVERY_CURRENT_NOT_TARGET","RECOVERY_RECEIPT_FAILED","LOCAL_INVENTORY_UNKNOWN_USER","LOCAL_INVENTORY_UNKNOWN_GROUP","LOCAL_INVENTORY_COLLISION","LOCAL_INVENTORY_LIMIT","LOCAL_REQUIRED_IDENTITY_MISSING","BACKUP_WITNESS_FAILED","RECOVERY_READBACK_REQUIRED","LOCAL_TARGET_NOT_USER","RECOVERY_COMMAND_BLOCKED","RECOVERY_WRITE_OPEN_BLOCKED","RECOVERY_SERVICE_BLOCKED","RECOVERY_CANNOT_ARM","LOCAL_INVENTORY_INVALID","LOCAL_BACKUP_REUSE_FAILED"})if(reason.contains(code))return code;
        if(reason.contains("select built-in Bolt01"))return "BOLT01_REQUIRED";
        if(reason.contains("local install requires prior receipt"))return "PRIOR_RECEIPT_REQUIRED";
        if(reason.contains("LOCAL_LOG"))return "LOG_FAILED";if(reason.contains("MEMORY"))return "MEMORY_FAILED";
        if(reason.contains("timeout"))return "TIMEOUT";if(reason.contains("Android")||reason.contains("ANDROID_TX"))return "TRANSPORT_FAILED";
        if(reason.contains("connection")||reason.contains("quarantined"))return "DISCONNECTED";
        if(reason.contains("malformed"))return "MALFORMED_RESPONSE";if(reason.contains("receipt"))return "RECEIPT_FAILED";return "UNEXPECTED_STATE";
    }
    private static int ffiInt(byte[] p) { if (p.length != 5 || (p[0] & 255) != 0x04) throw new IllegalArgumentException("ffi scalar"); return (p[1] & 255) | ((p[2] & 255) << 8) | ((p[3] & 255) << 16) | (p[4] << 24); }
    private static int readCount(byte[] p) { if (p.length < 8 || (p[0] & 255) != 0x04 || (p[5] & 255) != 0xC1) throw new IllegalArgumentException("read marker"); int count = (p[1] & 255) | ((p[2] & 255) << 8) | ((p[3] & 255) << 16) | (p[4] << 24); int declared = (p[6] & 255) | ((p[7] & 255) << 8); if (declared != count || p.length != 8 + count) throw new IllegalArgumentException("read count"); return count; }
    private static byte[] arrayArg(String value) { return arrayArg(value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] arrayArg(byte[] value) { byte[] r = new byte[3 + value.length]; r[0] = (byte) 0x81; r[1] = (byte) value.length; r[2] = (byte) (value.length >>> 8); System.arraycopy(value, 0, r, 3, value.length); return r; }
    private static byte[] editable(int n) { return new byte[]{(byte) 0xC1, (byte) n, (byte) (n >>> 8)}; }
    private static byte[] u32(int n) { return new byte[]{4, (byte)n, (byte)(n >>> 8), (byte)(n >>> 16), (byte)(n >>> 24)}; }
    private static byte[] concat(byte[]... all) { int n = 0; for (byte[] b : all) n += b.length; byte[] r = new byte[n]; int p = 0; for (byte[] b : all) { System.arraycopy(b, 0, r, p, b.length); p += b.length; } return r; }
    private byte[] frame(byte[] payload) { int n = payload.length + 8; byte[] f = new byte[n]; f[0] = (byte)0xFC; f[1] = (byte)(n >>> 8); f[2] = (byte)n; f[3] = (byte)(frameId++); System.arraycopy(payload, 0, f, 5, payload.length); int crc = crc16(f, 1, n - 4); f[n-3]=(byte)(crc>>>8); f[n-2]=(byte)crc; f[n-1]=(byte)0xCF; return f; }
    private static int crc16(byte[] b, int off, int len) { int c=0xFFFF; for(int i=off;i<off+len;i++){c^=b[i]&255;for(int bit=0;bit<8;bit++)c=(c&1)!=0?(c>>>1)^0xA001:c>>>1;}return c&0xFFFF; }
    private static MessageDigest digest() { try { return MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static String hash(byte[] bytes) { StringBuilder s=new StringBuilder(); for(byte b:digest().digest(bytes)) s.append(String.format(Locale.US,"%02x",b&255)); return s.toString(); }
    private static String hex(byte[] bytes) { StringBuilder s=new StringBuilder(); for(byte b:bytes) s.append(String.format(Locale.US,"%02x",b&255)); return s.toString(); }
}
