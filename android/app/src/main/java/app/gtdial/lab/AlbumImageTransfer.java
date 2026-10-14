package app.gtdial.lab;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.regex.Pattern;

/** Offline-only fail-stop planner for the future album image stage. Not wired to BLE/UI. */
final class AlbumImageTransfer {
    static final int MAX_BYTES = 168722, CHUNK = 4096, O_RDWR_CREAT_TRUNC = 578;
    enum State { IDLE, INITIAL_READS, CONFIG_READ, CONVENTION_READ, PRECHECK, OPEN_CACHE, WRITING, CLOSE_CACHE, VERIFY_TARGET, RENAME, VERIFY_FINAL, READBACK, VERIFIED, FAILED }
    interface Transport { void request(Request request); void log(String message); void terminal(boolean success); }
    static final class Request { final String operation,path; final int fd,offset,length,flags; final byte[] bytes; Request(String o,String p,int f,int off,int len,int flags,byte[] b){operation=o;path=p;fd=f;offset=off;length=len;this.flags=flags;bytes=b;} }
    private final byte[] image; private final String name, finalPath, cachePath, expectedHash; private final Transport transport; private State state=State.IDLE; private int fd=-1,offset; private MessageDigest digest; private boolean done;
    AlbumImageTransfer(byte[] image,String name,String expectedHash,Transport transport){if(image==null||image.length==0||image.length>MAX_BYTES)throw bad("image size");if(!Pattern.matches("gtdl_[0-9]{13}\\.png",name==null?"":name))throw bad("basename");if(expectedHash==null||expectedHash.matches("[0-9a-fA-F]{64}")==false)throw bad("sha256");this.image=image.clone();this.name=name;this.expectedHash=sha(image);if(!this.expectedHash.equalsIgnoreCase(expectedHash))throw bad("sha256 mismatch");this.finalPath="/user/widgets/indexDial/"+name;this.cachePath=finalPath+".cache";this.transport=transport;}
    State state(){return state;} String finalPath(){return finalPath;} String cachePath(){return cachePath;} byte[] image(){return image.clone();}
    void start(){if(state!=State.IDLE)throw bad("started");state=State.INITIAL_READS; transport.log("STAGE state=INITIAL_READS target="+finalPath+" bytes="+image.length+" sha256="+expectedHash); request("_link_up","",0,0,0,0,null);}
    void initialComplete(){if(state!=State.INITIAL_READS){fail("unexpected initial completion");return;}try{state=State.CONFIG_READ;request("svc_systemstorage_get","",0,0,0,0,AlbumConfiguration.request(AlbumConfiguration.IMAGE_KEY));}catch(Exception e){fail("config request");}}
    void configComplete(byte[] raw){if(state!=State.CONFIG_READ){fail("unexpected config");return;}try{AlbumConfiguration.parse(AlbumConfiguration.IMAGE_KEY,raw);state=State.CONVENTION_READ;request("svc_systemstorage_get","",0,0,0,0,AlbumConfiguration.request(AlbumConfiguration.CONVENTION_KEY));}catch(Exception e){fail("config "+e.getMessage());}}
    void conventionComplete(boolean type0Allowed,int maxCount){if(state!=State.CONVENTION_READ||!type0Allowed||maxCount<1){fail("convention rejected");return;}state=State.PRECHECK;request("access",finalPath,0,0,0,0,null);}
    void accessResult(int result){if(state!=State.PRECHECK){fail("unexpected access");return;}if(result==0){fail("final already exists");return;}state=State.OPEN_CACHE;request("open",cachePath,0,0,0,O_RDWR_CREAT_TRUNC,null);}
    void finalAbsent(){accessResult(1);}
    void cacheOpened(int value){if(state!=State.OPEN_CACHE||value<0){fail("cache open");return;}fd=value;offset=0;state=State.WRITING;writeNext();}
    void writeResult(int written){if(state!=State.WRITING||written!=Math.min(CHUNK,image.length-offset)){fail("short write");return;}offset+=written;if(offset<image.length)writeNext();else{state=State.CLOSE_CACHE;request("close","",fd,0,0,0,null);}}
    void cacheClosed(){if(state!=State.CLOSE_CACHE)fail("cache close");else{state=State.VERIFY_TARGET;request("access",finalPath,0,0,0,0,null);}}
    void targetStillAbsent(){if(state!=State.VERIFY_TARGET)fail("unexpected access");else{state=State.RENAME;byte[] old=(cachePath+"\0").getBytes(java.nio.charset.StandardCharsets.UTF_8);byte[] newer=(finalPath+"\0").getBytes(java.nio.charset.StandardCharsets.UTF_8);byte[] both=new byte[old.length+newer.length];System.arraycopy(old,0,both,0,old.length);System.arraycopy(newer,0,both,old.length,newer.length);request("rename",cachePath,0,0,0,0,both);}}
    void renamed(int result){if(state!=State.RENAME||result!=0){fail("rename");return;}state=State.VERIFY_FINAL;request("access",finalPath,0,0,0,0,null);}
    void finalPresent(){if(state!=State.VERIFY_FINAL)fail("unexpected access");else{state=State.READBACK;digest=sha();offset=0;request("open",finalPath,0,0,0,0,null);}}
    private static String sha(byte[] b){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(b);return hex(d);}catch(Exception e){throw bad("sha");}}
    void readbackOpened(int value){if(state!=State.READBACK||value<0){fail("read open");return;}fd=value;request("lseek","",fd,0,0,0,null);}
    void readbackLength(long length){if(state!=State.READBACK||length!=image.length){fail("length");return;}readNext();}
    void readbackChunk(byte[] bytes){if(state!=State.READBACK||bytes==null||bytes.length!=Math.min(CHUNK,image.length-offset)||!Arrays.equals(bytes,Arrays.copyOfRange(image,offset,offset+bytes.length))){fail("readback mismatch");return;}digest.update(bytes);offset+=bytes.length;if(offset<image.length)readNext();else request("close","",fd,0,0,0,null);}
    void readbackClosed(){if(state!=State.READBACK)fail("read close");else if(!hex(digest.digest()).equals(expectedHash))fail("sha256 mismatch");else{state=State.VERIFIED;done=true;transport.log("STAGE state=VERIFIED target="+finalPath+" bytes="+image.length+" sha256="+expectedHash);transport.terminal(true);}}
    void cancel(String reason){if(!done)fail(reason);} private void fail(String reason){if(done)return;done=true;state=State.FAILED;transport.log("STAGE state=FAILED "+reason);transport.terminal(false);}
    private void writeNext(){int n=Math.min(CHUNK,image.length-offset);request("write_by_offset","",fd,offset,n,0,Arrays.copyOfRange(image,offset,offset+n));} private void readNext(){request("read_by_offset","",fd,offset,Math.min(CHUNK,image.length-offset),0,null);} private void request(String o,String p,int f,int off,int len,int flags,byte[] b){transport.request(new Request(o,p,f,off,len,flags,b));} private MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw bad("sha");}} private static String hex(byte[] b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format("%02x",x&255));return s.toString();} private static IllegalArgumentException bad(String s){return new IllegalArgumentException("album stage: "+s);}
}
