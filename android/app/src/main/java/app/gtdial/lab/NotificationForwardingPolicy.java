package app.gtdial.lab;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.ArrayDeque;
final class NotificationForwardingPolicy {
    private static final int CAPACITY=20; private static final long TTL_MS=60_000L, DEDUP_MS=30_000L;
    private final ArrayDeque<Entry> queue=new ArrayDeque<>();
    private final java.util.LinkedHashMap<String,Long> recentlySeen=new java.util.LinkedHashMap<>();
    private static final class Entry { final NotificationRequest request; final long receivedAt; Entry(NotificationRequest r,long n){request=r;receivedAt=n;} }
    static boolean accepts(boolean enabled,Set<String> allowed,String own,boolean ongoing,boolean summary,NotificationRequest r){
        return enabled&&r!=null&&r.packageName!=null&&allowed!=null&&!r.packageName.equals(own)&&!ongoing&&!summary&&allowed.contains(r.packageName)&&r.title!=null&&r.text!=null&&!r.title.contains("\0")&&!r.text.contains("\0")&&!(r.title.isBlank()&&r.text.isBlank());
    }
    static String bounded(String s,int limit){byte[] b=bytes(s,limit);return b==null?null:new String(b,StandardCharsets.UTF_8);}
    private static byte[] bytes(String s,int limit){if(s==null)s="";byte[] b=s.getBytes(StandardCharsets.UTF_8);if(b.length<=limit)return b;int n=limit;while(n>0&&(b[n]&0xC0)==0x80)n--;byte[] out=new byte[n];System.arraycopy(b,0,out,0,n);return out;}
    synchronized boolean offer(NotificationRequest request,long now){expire(now);if(request==null||request.packageName==null||request.title==null||request.text==null||(request.title.isEmpty()&&request.text.isEmpty()))return false;String key=request.packageName+"\0"+request.title+"\0"+request.text;Long previous=recentlySeen.get(key);if(previous!=null&&now-previous<DEDUP_MS)return false;if(recentlySeen.size()>=64)recentlySeen.remove(recentlySeen.keySet().iterator().next());recentlySeen.put(key,now);if(queue.size()==CAPACITY)queue.removeFirst();queue.addLast(new Entry(request,now));return true;}
    synchronized NotificationRequest poll(long now){expire(now);Entry e=queue.pollFirst();return e==null?null:e.request;}
    synchronized void clear(){queue.clear();recentlySeen.clear();}
    private void expire(long now){while(!queue.isEmpty()&&now-queue.peekFirst().receivedAt>TTL_MS)queue.removeFirst();recentlySeen.entrySet().removeIf(e->now-e.getValue()>DEDUP_MS);}
}
