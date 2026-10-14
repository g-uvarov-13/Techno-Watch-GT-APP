package app.gtdial.lab;
import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
public class BundledDynamicDialTest {
 private static final String HASH="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
 @Test public void arbitraryMetadataCannotGrantInstallPermission(){BundledDynamicDial d=dial("dynamic_dials/x/face.dial");assertFalse(d.uploadable());assertFalse(d.physicalInstallAllowed());}
 @Test public void onlyExactCompiledDescriptorsAreExperimentallyInstallable(){String hash="6d519314696b31d2714886ec5ee8271f9ebf82c131d0670112e3f71514f0d58d";assertTrue(BundledDynamicDialRepository.isCompiledVariant("com.gtdial.dl3000","2309202601",807083L,hash));assertFalse(BundledDynamicDialRepository.isCompiledVariant("com.gtdial.dl3000","1909202601",807083L,hash));BundledDynamicDial.PackageVariant v=new BundledDynamicDial.PackageVariant("pipboy3000","phosphor","dynamic_dials/pipboy3000/phosphor/face.dial",hash,807083L,"com.gtdial.dl3000","2309202601");assertTrue(v.liveInstallAllowed());}
 @Test(expected=IllegalArgumentException.class) public void rejectsTraversal(){dial("dynamic_dials/../secret");}
 @Test(expected=IllegalArgumentException.class) public void rejectsInvalidHash(){new BundledDynamicDial("safe-id","Safe","dynamic_dials/x/face.dial","dynamic_dials/x/preview.png","bad",HASH,"com.QC.qc176",1);}
 private static BundledDynamicDial dial(String asset){return new BundledDynamicDial("safe-id","Safe",asset,"dynamic_dials/x/preview.png",HASH,HASH,"com.QC.qc176",1);}
}
