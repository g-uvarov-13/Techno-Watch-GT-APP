package app.gtdial.lab;

/** Origins remain distinct: a local build never becomes a compiled bundled tuple. */
final class DynamicDialPackage {
    enum Origin { BUNDLED_PINNED, LOCAL_PROFILE_BUILD }
    final Origin origin;
    final String packageSha256,internalPackageId,alias,profileSha256,snapshotSha256,artifactSha256;
    final long packageSize;
    final String projectId,runtimeProfile;
    final BundledDynamicDial.PackageVariant bundled;
    private final Gt01DialValidator.VerifiedLocalPackage local;
    private DynamicDialPackage(BundledDynamicDial.PackageVariant bundled,Gt01DialValidator.VerifiedLocalPackage local){
        this.bundled=bundled;this.local=local;
        origin=bundled!=null?Origin.BUNDLED_PINNED:Origin.LOCAL_PROFILE_BUILD;
        packageSha256=bundled!=null?bundled.packageSha256:local.sha256;
        internalPackageId=bundled!=null?bundled.internalPackageId:local.internalPackageId;
        alias=bundled!=null?bundled.alias:local.alias;packageSize=bundled!=null?bundled.packageSize:local.size;
        profileSha256=local==null?null:local.profileSha256;snapshotSha256=local==null?null:local.snapshotSha256;
        artifactSha256=local==null?null:local.sha256;
        projectId=local==null?null:local.projectId;runtimeProfile=local==null?null:DialRuntimeProfile.ID;
    }
    static DynamicDialPackage bundled(BundledDynamicDial.PackageVariant variant){
        if(variant==null||!BundledDynamicDialRepository.isCompiledVariant(variant.internalPackageId,variant.alias,variant.packageSize,variant.packageSha256))throw new IllegalArgumentException("Встроенный пакет не подтверждён");
        return new DynamicDialPackage(variant,null);
    }
    static DynamicDialPackage local(CompiledDialArtifactStore.Artifact artifact){
        if(artifact==null)throw new IllegalArgumentException("Нет проверенного артефакта");return new DynamicDialPackage(null,artifact.verified);
    }
    byte[] localBytes(){if(local==null)throw new IllegalStateException("Встроенный пакет загружается из assets");return local.bytes();}
}
