package app.gtdial.lab;

import android.os.Handler;
import java.util.Arrays;

/** Only full manual weather uses callback-paced fragments. Ordinary TX remains unchanged. */
final class WeatherFrameWriter {
    interface Transport {
        boolean ledgerEmpty();long nextGeneration();boolean write(long generation,int chunk,byte[] bytes);
        void complete();void failed(String reason);
    }
    private final Handler handler;private final Transport transport;private final int chunkBytes;
    private byte[] frame;private long generation,drainStarted,frameStarted,chunkStarted;private int offset,index;private boolean active,waitingDrain,waitingCallback;
    private final Runnable drainTimeout=()->fail("prior Android TX callback did not drain within 3s");
    private final Runnable callbackTimeout=()->fail("Android weather TX callback timeout");
    private final Runnable frameTimeout=()->fail("weather frame exceeded 10s");
    WeatherFrameWriter(Handler handler,int mtu,Transport transport){this.handler=handler;this.transport=transport;chunkBytes=Math.min(509,mtu-5);}
    boolean active(){return active;}
    boolean write(byte[] value){
        if(active||chunkBytes<=0||value==null||value.length<13||value.length>2048||(value.length+chunkBytes-1)/chunkBytes>16)return false;
        frame=value.clone();offset=0;index=0;generation=transport.nextGeneration();active=true;waitingDrain=!transport.ledgerEmpty();drainStarted=android.os.SystemClock.elapsedRealtime();
        if(waitingDrain)handler.postDelayed(drainTimeout,3000);else begin();return true;
    }
    void ledgerDrained(){if(active&&waitingDrain&&transport.ledgerEmpty()){if(android.os.SystemClock.elapsedRealtime()-drainStarted>=3000){fail("prior TX ledger drain deadline exceeded");return;}handler.removeCallbacks(drainTimeout);waitingDrain=false;begin();}}
    private void begin(){frameStarted=android.os.SystemClock.elapsedRealtime();handler.postDelayed(frameTimeout,10000);next();}
    private void next(){if(!active)return;if(!transport.ledgerEmpty()){fail("unexpected TX owner during weather frame");return;}if(android.os.SystemClock.elapsedRealtime()-frameStarted>=10000){fail("weather frame deadline exceeded");return;}int end=Math.min(frame.length,offset+chunkBytes);waitingCallback=true;chunkStarted=android.os.SystemClock.elapsedRealtime();if(!transport.write(generation,index,Arrays.copyOfRange(frame,offset,end))){fail("Android rejected weather chunk");return;}if(active&&waitingCallback)handler.postDelayed(callbackTimeout,3000);}
    void written(long owner,int chunk,int status){
        if(!active||!waitingCallback||owner!=generation||chunk!=index){fail("unexpected weather callback ownership");return;}
        long now=android.os.SystemClock.elapsedRealtime();if(now-chunkStarted>=3000||now-frameStarted>=10000){fail("weather TX completion deadline exceeded");return;}
        handler.removeCallbacks(callbackTimeout);waitingCallback=false;if(status!=0){fail("Android weather TX status="+status);return;}
        offset=Math.min(frame.length,offset+chunkBytes);index++;if(offset==frame.length){clear();transport.complete();}else next();
    }
    void unexpected(){if(active)fail("unexpected Android TX callback ownership");}
    void close(){clear();}
    private void clear(){active=false;waitingDrain=false;waitingCallback=false;frame=null;handler.removeCallbacks(drainTimeout);handler.removeCallbacks(callbackTimeout);handler.removeCallbacks(frameTimeout);}
    private void fail(String reason){if(!active)return;clear();transport.failed(reason);}
}
