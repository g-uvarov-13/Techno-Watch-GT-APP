package app.gtdial.lab;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class DynamicDialOwnershipStoreTest {
    private static final String ADDRESS="02:00:00:00:00:01";
    private static final String PATH=BundledDynamicDial.REMOTE_PATH;

    @Test public void receiptsAdvanceFromVerifiedVariantAToBAndAreDeviceBound() throws Exception {
        File root=Files.createTempDirectory("dial-ownership").toFile();
        try {
            DynamicDialOwnershipStore store=new DynamicDialOwnershipStore(root);
            BundledDynamicDial.PackageVariant a=BundledDynamicDialRepository.compiledVariant("pipboy3000","phosphor");
            BundledDynamicDial.PackageVariant b=BundledDynamicDialRepository.compiledVariant("pipboy3000","amber");
            DynamicDialOwnershipStore.Receipt receiptA=new DynamicDialOwnershipStore.Receipt(ADDRESS,PATH,a,1000L);
            DynamicDialOwnershipStore.Receipt receiptB=new DynamicDialOwnershipStore.Receipt(ADDRESS,PATH,b,2000L);
            assertTrue(store.saveAfterVerifiedCurrent(null,receiptA));
            DynamicDialOwnershipStore.Receipt loadedA=store.load(ADDRESS.toLowerCase(),PATH);
            assertNotNull(loadedA);assertTrue(loadedA.sameOwnership(receiptA));assertEquals(1000L,loadedA.verifiedAtEpochMs);
            assertNull(store.load("11:22:33:44:55:66",PATH));
            assertFalse(store.saveAfterVerifiedCurrent(null,receiptB));
            assertTrue(store.saveAfterVerifiedCurrent(loadedA,receiptB));
            DynamicDialOwnershipStore.Receipt loadedB=store.load(ADDRESS,PATH);
            assertNotNull(loadedB);assertTrue(loadedB.sameOwnership(receiptB));assertEquals(2000L,loadedB.verifiedAtEpochMs);
        } finally { deleteTree(root); }
    }

    @Test public void failedReceiptDirectoryCreationLeavesNoOwnershipApproval() throws Exception {
        File root=Files.createTempDirectory("dial-ownership-failure").toFile();
        try {
            File filesDir=new File(root,"not-a-directory");assertTrue(Files.write(filesDir.toPath(),new byte[]{1})!=null);
            DynamicDialOwnershipStore store=new DynamicDialOwnershipStore(filesDir);
            BundledDynamicDial.PackageVariant a=BundledDynamicDialRepository.compiledVariant("bitax","amber");
            DynamicDialOwnershipStore.Receipt receipt=new DynamicDialOwnershipStore.Receipt(ADDRESS,PATH,a,3000L);
            try { store.saveAfterVerifiedCurrent(null,receipt);fail("write must fail when private directory cannot be created"); }
            catch(java.io.IOException expected) { assertNull(store.load(ADDRESS,PATH)); }
        } finally { deleteTree(root); }
    }

    @Test public void priorVioletReceiptCanAdvanceToCorrectedPackage() throws Exception {
        File root=Files.createTempDirectory("dial-v3-receipt").toFile();
        try {
            DynamicDialOwnershipStore store=new DynamicDialOwnershipStore(root);
            BundledDynamicDial.PackageVariant old=BundledDynamicDialRepository.historicalVariant("pipboy3000","violet","com.gtdial.dl.violet","2309202608",807091L,"d995e7534a6ac5dd32e6e0bb1cb3270da87b90aff6d440824c1c424bcb61fec1");
            BundledDynamicDial.PackageVariant corrected=BundledDynamicDialRepository.compiledVariant("pipboy3000","violet");
            assertNotEquals(old.packageSha256,corrected.packageSha256);
            DynamicDialOwnershipStore.Receipt prior=new DynamicDialOwnershipStore.Receipt(ADDRESS,PATH,old,1000L);
            assertTrue(store.saveAfterVerifiedCurrent(null,prior));
            DynamicDialOwnershipStore.Receipt loaded=store.load(ADDRESS,PATH);
            assertEquals(old.packageSha256,loaded.packageSha256);
            assertTrue(store.saveAfterVerifiedCurrent(loaded,new DynamicDialOwnershipStore.Receipt(ADDRESS,PATH,corrected,2000L)));
            assertEquals(corrected.packageSha256,store.load(ADDRESS,PATH).packageSha256);
        } finally { deleteTree(root); }
    }

    private static void deleteTree(File file){if(file==null||!file.exists())return;if(file.isDirectory()){File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);}if(!file.delete())throw new AssertionError("test cleanup failed: "+file);}
}
