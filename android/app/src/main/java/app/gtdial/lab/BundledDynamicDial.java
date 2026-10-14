package app.gtdial.lab;

import java.util.Locale;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Validated metadata for one bundled watch-face card. */
public final class BundledDynamicDial {
    private static final Pattern SAFE_ID=Pattern.compile("[a-z0-9-]+");
    private static final Pattern SHA256=Pattern.compile("[0-9a-fA-F]{64}");
    public static final String REMOTE_ALIAS="1909202601";
    public static final String REMOTE_PATH="/download/1909202601.dial";
    public static final class PackageVariant {
        public final String faceId,paletteId,packageAsset,packageSha256,internalPackageId,alias;
        public final long packageSize;
        PackageVariant(String faceId,String paletteId,String packageAsset,String packageSha256,long packageSize,String internalPackageId,String alias){
            if(!safeId(faceId)||!safeId(paletteId)||!safeAsset(packageAsset)||!validHash(packageSha256)||packageSize<=0||packageSize>32L*1024*1024||internalPackageId==null||!internalPackageId.matches("[a-zA-Z0-9_.]+")||alias==null||!alias.matches("[0-9]{10}"))throw new IllegalArgumentException("Invalid bundled package variant");
            this.faceId=faceId;this.paletteId=paletteId;this.packageAsset=packageAsset;this.packageSha256=lower(packageSha256);this.packageSize=packageSize;this.internalPackageId=internalPackageId;this.alias=alias;
        }
        public boolean liveInstallAllowed(){return BundledDynamicDialRepository.isCompiledVariant(internalPackageId,alias,packageSize,packageSha256);}
    }
    static final class VerifiedPackage {
        final PackageVariant variant;
        final byte[] bytes;
        VerifiedPackage(PackageVariant variant,byte[] bytes){this.variant=variant;this.bytes=bytes.clone();}
    }
    public static final class Palette {
        public final String id,name,background,primary,secondary,accent,muted;
        Palette(String id,String name,String background,String primary,String secondary,String accent,String muted){
            if(!safeId(id)||name==null||name.isBlank()||!validColor(background)||!validColor(primary)||!validColor(secondary)||!validColor(accent)||!validColor(muted))throw new IllegalArgumentException("Invalid palette");
            this.id=id;this.name=name;this.background=background;this.primary=primary;this.secondary=secondary;this.accent=accent;this.muted=muted;
        }
    }
    public final String id,name,packageAsset,previewAsset,packageSha256,basePackageSha256,internalPackageId;
    public final String renderSpecAsset,defaultPaletteId,installablePaletteId;
    public final String[] displayFields;
    public final Palette[] palettes;
    private final Map<String,PackageVariant> packageVariants;
    public final boolean previewOnly;
    public final long packageSize;
    /** Compatibility constructor for the verified legacy metadata and focused JVM checks. */
    public BundledDynamicDial(String id,String name,String packageAsset,String previewAsset,String packageSha256,String basePackageSha256,String internalPackageId,long packageSize){
        if(!safeId(id)||name==null||name.isBlank()||!safeAsset(packageAsset)||!safeAsset(previewAsset)||!validHash(packageSha256)||!validHash(basePackageSha256)||internalPackageId==null||internalPackageId.isBlank()||packageSize<=0||packageSize>32L*1024*1024)throw new IllegalArgumentException("Invalid bundled dynamic dial metadata");
        this.id=id;this.name=name;this.packageAsset=packageAsset;this.previewAsset=previewAsset;this.packageSha256=lower(packageSha256);this.basePackageSha256=lower(basePackageSha256);this.internalPackageId=internalPackageId;this.packageSize=packageSize;
        this.renderSpecAsset=null;this.displayFields=new String[0];this.palettes=new Palette[0];this.defaultPaletteId=null;this.installablePaletteId=null;this.previewOnly=false;this.packageVariants=Collections.emptyMap();
    }
    BundledDynamicDial(String id,String name,String previewAsset,String renderSpecAsset,String[] displayFields,Palette[] palettes,String defaultPaletteId,String installablePaletteId,String packageAsset,String packageSha256,String basePackageSha256,String internalPackageId,long packageSize,boolean previewOnly,Map<String,PackageVariant> variants){
        if(!safeId(id)||name==null||name.isBlank()||!safeAsset(previewAsset)||!safeAsset(renderSpecAsset))throw new IllegalArgumentException("Invalid dial metadata");
        if(palettes==null||palettes.length<1||palettes.length>6||defaultPaletteId==null||find(palettes,defaultPaletteId)==null)throw new IllegalArgumentException("Invalid dial palettes");
        boolean hasSinglePackage=safeAsset(packageAsset)&&validHash(packageSha256)&&validHash(basePackageSha256)&&internalPackageId!=null&&!internalPackageId.isBlank()&&packageSize>0&&packageSize<=32L*1024*1024;
        if(!previewOnly&&!hasSinglePackage&&(variants==null||variants.isEmpty()))throw new IllegalArgumentException("Invalid package metadata");
        if(installablePaletteId!=null&&find(palettes,installablePaletteId)==null)throw new IllegalArgumentException("Invalid installable palette");
        this.id=id;this.name=name;this.previewAsset=previewAsset;this.renderSpecAsset=renderSpecAsset;this.displayFields=displayFields==null?new String[0]:displayFields.clone();this.palettes=palettes.clone();this.defaultPaletteId=defaultPaletteId;this.installablePaletteId=installablePaletteId;this.previewOnly=previewOnly;
        this.packageAsset=packageAsset;this.packageSha256=lower(packageSha256);this.basePackageSha256=lower(basePackageSha256);this.internalPackageId=internalPackageId;this.packageSize=packageSize;
        this.packageVariants=Collections.unmodifiableMap(new LinkedHashMap<>(variants==null?Collections.emptyMap():variants));
    }
    private static String lower(String value){return value==null?null:value.toLowerCase(Locale.ROOT);}
    private static boolean safeId(String value){return value!=null&&SAFE_ID.matcher(value).matches();}
    private static boolean validHash(String value){return value!=null&&SHA256.matcher(value).matches();}
    private static boolean safeAsset(String value){return value!=null&&value.startsWith("dynamic_dials/")&&!value.contains("..")&&!value.contains("\\");}
    private static boolean validColor(String value){return value!=null&&value.matches("#[0-9a-fA-F]{8}");}
    private static Palette find(Palette[] values,String id){for(Palette p:values)if(p.id.equals(id))return p;return null;}
    public Palette palette(String paletteId){Palette p=find(palettes,paletteId);return p==null?find(palettes,defaultPaletteId):p;}
    public boolean uploadable(){return physicalInstallAllowed();}
    public PackageVariant packageVariant(String paletteId){return packageVariants.get(paletteId);}
    public PackageVariant packageVariantByAlias(String alias){for(PackageVariant v:packageVariants.values())if(v.alias.equals(alias))return v;return null;}
    public boolean physicalInstallAllowed(){return !previewOnly&&physicalInstallAllowed(defaultPaletteId);}
    public boolean physicalInstallAllowed(String paletteId){PackageVariant variant=packageVariant(paletteId);return !previewOnly&&variant!=null&&variant.liveInstallAllowed();}
}
