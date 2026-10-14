package app.gtdial.lab;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
final class DynamicDialInstallRequest {
 enum Kind { INSTALL, APPLY }
 final Kind kind; final String service; final int destination, timeoutSeconds; final byte[][] args;
 private DynamicDialInstallRequest(Kind k,String s,int t,byte[][] a){kind=k;service=s;destination=1;timeoutSeconds=t;args=a;}
 static DynamicDialInstallRequest install(String path,String alias){return new DynamicDialInstallRequest(Kind.INSTALL,"svc_dial_install",30,new byte[][]{nul(path),nul(alias)});}
 static DynamicDialInstallRequest apply(String alias){return new DynamicDialInstallRequest(Kind.APPLY,"svc_dial_apply",10,new byte[][]{nul(alias)});}
 private static byte[] nul(String s){if(s==null||s.indexOf('\0')>=0)throw new IllegalArgumentException("invalid dial argument");return (s+"\0").getBytes(StandardCharsets.UTF_8);}
}
