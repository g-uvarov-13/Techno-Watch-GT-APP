package app.gtdial.lab;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Only this complete validation path can construct VerifiedLocalPackage. */
final class Gt01DialValidator {
    static final class VerifiedLocalPackage {
        final String sha256,snapshotSha256,profileSha256,internalPackageId,alias,manifestName,projectId;
        final int size,revision;
        private final byte[] bytes;
        private VerifiedLocalPackage(byte[] bytes,Gt01DialCompiler.Snapshot snapshot){
            this.bytes=bytes.clone();size=bytes.length;sha256=DialImageNormalizer.hash(bytes);snapshotSha256=snapshot.sha256;
            profileSha256=Gt01DialCompiler.PROFILE_SHA;internalPackageId=snapshot.identity.id;alias=snapshot.identity.alias;
            manifestName=snapshot.identity.name;projectId=snapshot.project.id;revision=snapshot.project.revision;
        }
        byte[] bytes(){return bytes.clone();}
    }
    static VerifiedLocalPackage validate(Gt01DialCompiler compiler,Gt01DialCompiler.Snapshot snapshot,byte[] candidate)throws Exception {
        Gt01DialContainerCodec.require(candidate.length>0&&candidate.length<=Gt01DialContainerCodec.MAX_PACKAGE,"Предел пакета");
        byte[] recompiled=compiler.compile(snapshot);Gt01DialContainerCodec.require(Arrays.equals(candidate,recompiled),"Повторная сборка отличается");
        Gt01DialContainerCodec.Container actual=Gt01DialContainerCodec.parse(candidate),donor=compiler.donor();
        Gt01DialContainerCodec.require(actual.locales.size()==donor.locales.size(),"Число локалей");
        Set<String> checkedRefs=new HashSet<>();
        for(int i=0;i<donor.locales.size();i++){
            Gt01DialContainerCodec.LocaleTable a=actual.locales.get(i),d=donor.locales.get(i);
            Gt01DialContainerCodec.require(a.hash==d.hash&&a.length==d.length&&Arrays.equals(a.nameBytes,d.nameBytes)&&a.entries.size()==71,"Локаль изменена");
            for(int j=0;j<71;j++){
                Gt01DialContainerCodec.Entry e=a.entries.get(j),base=d.entries.get(j);
                Gt01DialContainerCodec.require(e.hash==base.hash&&e.fourth==base.fourth,"CR02 неизменяемые поля");
                String reference=j+"|"+base.offset+"|"+e.offset+"|"+base.length+"|"+e.length;
                if(!checkedRefs.add(reference))continue;
                byte[] blob=Gt01DialContainerCodec.blob(candidate,e),original=Gt01DialContainerCodec.blob(donor.bytes,base);
                if(j==34)Gt01DialContainerCodec.require(Arrays.equals(blob,compiler.runtime(snapshot.project)),"Runtime состав");
                else if(j==37)Gt01PlbCodec.validate(blob,original,snapshot.project);
                else if(j==44){
                    Gt01DialContainerCodec.require(Arrays.equals(blob,compiler.manifest(snapshot.identity)),"Manifest байты");
                    org.json.JSONObject manifest=Gt01DialCompiler.json(blob);
                    Gt01DialContainerCodec.require(snapshot.identity.id.equals(manifest.getString("id"))&&snapshot.identity.alias.equals(manifest.getString("alias"))
                        &&snapshot.identity.name.equals(manifest.getString("name")),"Manifest identity");
                }else if(j==60){
                    byte[] header=Arrays.copyOf(blob,46);System.arraycopy(original,36,header,36,10);
                    Gt01DialContainerCodec.require(Arrays.equals(header,Arrays.copyOf(original,46)),"PGF неизменяемый заголовок");
                    byte[] decoded=Gt01PgfCodec.decode(blob),pixels=snapshot.backgroundBytes();
                    for(int p=0;p<pixels.length;p+=4){int b=(pixels[p]&255)>>6,g=(pixels[p+1]&255)>>5,r=(pixels[p+2]&255)>>5;
                        Gt01DialContainerCodec.require((decoded[p]&255)==b*255/3&&(decoded[p+1]&255)==g*255/7
                            &&(decoded[p+2]&255)==r*255/7&&(decoded[p+3]&255)==255,"PGF пиксели");}
                    Gt01DialContainerCodec.require(Arrays.equals(blob,Gt01PgfCodec.encode(original,pixels)),"PGF canonical encoding");
                }else Gt01DialContainerCodec.require(Arrays.equals(blob,original),"Изменён запрещённый ресурс "+j);
            }
        }
        return new VerifiedLocalPackage(candidate,snapshot);
    }
    private Gt01DialValidator(){}
}
