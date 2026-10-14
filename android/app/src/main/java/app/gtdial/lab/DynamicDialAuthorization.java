package app.gtdial.lab;

import android.os.SystemClock;
import java.io.IOException;
import java.util.Arrays;

/** Bundled physical grants and exact, ephemeral LOCAL consent remain distinct. */
final class DynamicDialAuthorization {
    static final long LOCAL_CONSENT_MS=120_000L;
    static final class PreparedLocal {
        final String mode,originalOperationId;
        private final DynamicDialOwnershipStore.RecoveryContext recovery;
        final String operationId,projectName,internalPackageId,alias,packageSha256,profileSha256,snapshotSha256,artifactSha256,runtimeProfile,deviceAddress;
        final long packageSize;
        private final long preparedElapsed;
        private final byte[] bytes;
        private boolean confirmed,cancelled;
        private PreparedLocal(CompiledDialArtifactStore.Artifact artifact,String address,String operationId,DynamicDialOwnershipStore.RecoveryContext recovery)throws Exception {
            DynamicDialPackage pkg=DynamicDialPackage.local(artifact);
            if(!localCandidate(pkg))throw new IOException("LOCAL_PROFILE_INVALID");
            this.recovery=recovery;mode=recovery==null?"INSTALL":"VERIFY_PENDING";originalOperationId=recovery==null?"":recovery.originalOperationId;
            this.operationId=operationId;deviceAddress=DynamicDialOwnershipStore.normalizeAddress(address);
            projectName=artifact.snapshot.project.name;internalPackageId=pkg.internalPackageId;alias=pkg.alias;
            packageSize=pkg.packageSize;packageSha256=pkg.packageSha256;profileSha256=pkg.profileSha256;
            snapshotSha256=pkg.snapshotSha256;artifactSha256=pkg.artifactSha256;runtimeProfile=pkg.runtimeProfile;
            bytes=pkg.localBytes();preparedElapsed=SystemClock.elapsedRealtime();
        }
    }
    static final class LocalConsent {
        private final PreparedLocal prepared;
        private boolean consumed,executionIssued;
        private LocalConsent(PreparedLocal prepared){this.prepared=prepared;}
    }
    static final class LocalExecution {
        final PreparedLocal prepared;
        final DynamicDialPackage target;
        final DynamicDialOwnershipStore.Receipt prior;
        final java.util.List<DynamicDialOwnershipStore.Receipt> knownReceipts;
        final DynamicDialOwnershipStore.RecoveryContext recovery;
        private final byte[] reusableBackup;
        private boolean claimed;
        private LocalExecution(PreparedLocal prepared,DynamicDialPackage target,DynamicDialOwnershipStore.Receipt prior,java.util.List<DynamicDialOwnershipStore.Receipt> known,DynamicDialOwnershipStore.RecoveryContext recovery,byte[] reusable){this.prepared=prepared;this.target=target;this.prior=prior;knownReceipts=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(known));this.recovery=recovery;reusableBackup=reusable==null?null:reusable.clone();}
        byte[] reusableBackup(){return reusableBackup==null?null:reusableBackup.clone();}
        boolean matches(String operationId,String address,DynamicDialPackage descriptor){return prepared.operationId.equals(operationId)&&prepared.deviceAddress.equals(address)&&target==descriptor;}
        synchronized boolean claim(){synchronized(prepared){if(claimed||prepared.cancelled||SystemClock.elapsedRealtime()-prepared.preparedElapsed>LOCAL_CONSENT_MS)return false;claimed=true;return true;}}
        synchronized boolean boundForMutation(String id,String address,String path){return claimed&&recovery==null&&"INSTALL".equals(prepared.mode)&&matches(id,address,target)&&BundledDynamicDial.REMOTE_PATH.equals(path);}
    }
    static PreparedLocal prepareLocal(CompiledDialArtifactStore.Artifact artifact,String address,String operationId)throws Exception {
        if(operationId==null||!java.util.UUID.fromString(operationId).toString().equals(operationId))throw new IOException("LOCAL_OPERATION_INVALID");
        return new PreparedLocal(artifact,address,operationId,null);
    }
    static PreparedLocal prepareRecovery(CompiledDialArtifactStore.Artifact artifact,String address,String operationId,DynamicDialOwnershipStore.RecoveryContext recovery)throws Exception {
        DialInstallJournal.validateOperation(operationId);
        if(recovery==null||!recovery.address.equals(DynamicDialOwnershipStore.normalizeAddress(address))||!recovery.target.artifactSha256.equals(DynamicDialPackage.local(artifact).artifactSha256))throw new IOException("RECOVERY_PREPARATION_CHANGED");
        return new PreparedLocal(artifact,address,operationId,recovery);
    }
    static LocalConsent confirmLocal(PreparedLocal prepared)throws Exception {
        if(prepared==null)throw new IOException("LOCAL_PREPARATION_MISSING");
        synchronized(prepared){
            if(prepared.cancelled||prepared.confirmed||SystemClock.elapsedRealtime()-prepared.preparedElapsed>LOCAL_CONSENT_MS)throw new IOException("LOCAL_CONSENT_EXPIRED_OR_USED");
            prepared.confirmed=true;return new LocalConsent(prepared);
        }
    }
    static void cancelLocal(PreparedLocal prepared){if(prepared!=null)synchronized(prepared){prepared.cancelled=true;}}
    static long remainingLocalMillis(PreparedLocal prepared){return Math.max(0,LOCAL_CONSENT_MS-(SystemClock.elapsedRealtime()-prepared.preparedElapsed));}
    static void consumeLocal(LocalConsent consent,PreparedLocal expected)throws IOException {
        if(consent==null||expected==null||consent.prepared!=expected)throw new IOException("LOCAL_CONSENT_MISMATCH");
        synchronized(consent){synchronized(expected){
            if(consent.consumed||expected.cancelled||!expected.confirmed||SystemClock.elapsedRealtime()-expected.preparedElapsed>LOCAL_CONSENT_MS)throw new IOException("LOCAL_CONSENT_EXPIRED_OR_USED");
            consent.consumed=true;
        }}
    }
    static LocalExecution validateConsumedLocal(LocalConsent consent,CompiledDialArtifactStore.Artifact artifact,String address,DynamicDialOwnershipStore ownership,DynamicDialBackupStore backups)throws Exception {
        if(consent==null)throw new IOException("LOCAL_CONSENT_MISSING");
        synchronized(consent){synchronized(consent.prepared){
            PreparedLocal p=consent.prepared;DynamicDialPackage pkg=DynamicDialPackage.local(artifact);
            if(!consent.consumed||consent.executionIssued||p.cancelled||SystemClock.elapsedRealtime()-p.preparedElapsed>LOCAL_CONSENT_MS
                ||!localCandidate(pkg)||!p.deviceAddress.equals(DynamicDialOwnershipStore.normalizeAddress(address))
                ||!p.projectName.equals(artifact.snapshot.project.name)||!p.internalPackageId.equals(pkg.internalPackageId)||!p.alias.equals(pkg.alias)
                ||p.packageSize!=pkg.packageSize||!p.packageSha256.equals(pkg.packageSha256)||!p.profileSha256.equals(pkg.profileSha256)
                ||!p.snapshotSha256.equals(pkg.snapshotSha256)||!p.artifactSha256.equals(pkg.artifactSha256)||!p.runtimeProfile.equals(pkg.runtimeProfile)
                ||!Arrays.equals(p.bytes,pkg.localBytes()))throw new IOException("LOCAL_ARTIFACT_OR_CONSENT_CHANGED");
            DynamicDialOwnershipStore.RecoveryContext recovery=null;DynamicDialOwnershipStore.Receipt prior;java.util.List<DynamicDialOwnershipStore.Receipt> known;byte[] reusable=null;
            if(p.recovery!=null){recovery=ownership.recoveryContext(address);if(!p.recovery.same(recovery)||!pkg.artifactSha256.equals(recovery.target.artifactSha256))throw new IOException("RECOVERY_PENDING_CHANGED");prior=recovery.prior;known=java.util.Collections.singletonList(prior);}
            else{prior=ownership.load(address,BundledDynamicDial.REMOTE_PATH);if(prior==null)throw new IOException("PRIOR_RECEIPT_REQUIRED");known=ownership.knownReceipts(address,BundledDynamicDial.REMOTE_PATH);reusable=backups.reusable(ownership,prior);}
            consent.executionIssued=true;return new LocalExecution(p,pkg,prior,known,recovery,reusable);
        }}
    }
    static DynamicDialPackage verifiedLocal(CompiledDialArtifactStore store,String sha)throws Exception{return DynamicDialPackage.local(store.load(sha));}
    static boolean executionArtifactMatches(LocalExecution execution,CompiledDialArtifactStore.Artifact artifact){
        if(execution==null||artifact==null)return false;DynamicDialPackage fresh=DynamicDialPackage.local(artifact),expected=execution.target;
        return localCandidate(fresh)&&expected.internalPackageId.equals(fresh.internalPackageId)&&expected.alias.equals(fresh.alias)
            &&expected.packageSize==fresh.packageSize&&expected.packageSha256.equals(fresh.packageSha256)&&expected.profileSha256.equals(fresh.profileSha256)
            &&expected.snapshotSha256.equals(fresh.snapshotSha256)&&expected.artifactSha256.equals(fresh.artifactSha256)
            &&expected.runtimeProfile.equals(fresh.runtimeProfile)&&expected.projectId.equals(fresh.projectId)
            &&execution.prepared.projectName.equals(artifact.snapshot.project.name)&&Arrays.equals(execution.prepared.bytes,fresh.localBytes());
    }
    /** Eligibility is not a physical grant: execution also requires consumed consent and fresh validation. */
    static boolean localCandidate(DynamicDialPackage pkg){
        return pkg!=null&&pkg.origin==DynamicDialPackage.Origin.LOCAL_PROFILE_BUILD&&pkg.bundled==null
            &&pkg.packageSize>0&&pkg.packageSize<=Gt01DialContainerCodec.MAX_PACKAGE&&Gt01DialCompiler.PROFILE_SHA.equals(pkg.profileSha256)
            &&DialRuntimeProfile.ID.equals(pkg.runtimeProfile)&&pkg.packageSha256.equals(pkg.artifactSha256)
            &&pkg.artifactSha256.matches("[0-9a-f]{64}")&&pkg.snapshotSha256.matches("[0-9a-f]{64}")
            &&pkg.internalPackageId.matches("com[.]gtdial[.]ed[.][0-9]{8}")&&pkg.alias.matches("80[0-9]{8}");
    }
    static boolean physicallyAllowed(DynamicDialPackage pkg){return pkg!=null&&pkg.origin==DynamicDialPackage.Origin.BUNDLED_PINNED&&pkg.bundled.liveInstallAllowed();}
    static DynamicDialPackage requirePhysical(CompiledDialArtifactStore store,DynamicDialPackage pkg)throws Exception {
        if(!physicallyAllowed(pkg))throw new IOException("LOCAL_REQUIRES_EXACT_CONSENT");return pkg;
    }
    private DynamicDialAuthorization(){}
}
