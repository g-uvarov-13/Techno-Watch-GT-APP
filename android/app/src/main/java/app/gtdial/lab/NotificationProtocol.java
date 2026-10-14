package app.gtdial.lab;
import java.nio.charset.StandardCharsets;
final class NotificationProtocol {
    static byte[] request(NotificationRequest r){
        if(r==null||r.epochSeconds<0||r.epochSeconds>0xffffffffL)throw new IllegalArgumentException("notification timestamp");
        return ffi("svc_notification_push",arr(r.title),arr(""),arr(sender(r.packageName)),arr(r.text),arr(""),arr("TextMsg"),arr("NoPrompt"),u32(1),u32((int)r.epochSeconds));
    }
    static byte[] ffi(String fn,byte[]... args){int n=0;for(byte[] a:args)n+=a.length;byte[] name=rawArray(fn);byte[] out=new byte[name.length+n];System.arraycopy(name,0,out,0,name.length);int p=name.length;for(byte[] a:args){System.arraycopy(a,0,out,p,a.length);p+=a.length;}return out;}
    static byte[] arr(String s){if(s==null||s.indexOf('\0')>=0)throw new IllegalArgumentException("notification text");return rawArray(s+"\0");}
    private static byte[] rawArray(String s){byte[] x=s.getBytes(StandardCharsets.UTF_8);if(x.length>65535)throw new IllegalArgumentException("notification argument size");byte[] o=new byte[x.length+3];o[0]=(byte)0x81;o[1]=(byte)x.length;o[2]=(byte)(x.length>>>8);System.arraycopy(x,0,o,3,x.length);return o;}
    static byte[] u32(int v){return new byte[]{4,(byte)v,(byte)(v>>>8),(byte)(v>>>16),(byte)(v>>>24)};}
    static String sender(String p){switch(p){case "com.skype.raider":return "skype";case "com.vkontakte.android":return "vk";case "org.telegram.messenger":return "telegram";case "com.viber.voip":return "viber";case "com.whatsapp":return "whatsapp";case "jp.naver.line.android":return "line";case "com.huawei.welink":return "welink";case "com.tencent.mm":return "wechat";case "com.android.mms":return "duanxin";case "com.instagram.android":return "instagram";case "com.google.android.gm":return "gmail";case "org.thoughtcrime.securesms":return "signal";case "com.android.calendar":return "calendar";case "com.twitter.android":return "twitter";case "com.tencent.mobileqg":return "qq";case "com.netflix.mediaclient":return "netflix";case "com.zhiliaoapp.musically":return "tiktok";case "com.facebook.katana":return "facebook";case "com.facebook.orca":return "messenger";case "com.linkedin.android":return "linkedin";case "com.kakao.talk":return "kakaotalk";case "com.alibaba.android.rimet":return "dingtalk";case "com.sina.weibo":return "weibo";case "com.android.phone":return "missedcall";case "com.eg.android.AlipayGphone":return "alipay";case "com.snapchat.android":return "snapchat";default:return "default";}}
}
