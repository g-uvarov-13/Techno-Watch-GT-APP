package app.gtdial.lab;

import java.util.Arrays;

/** One-shot, allow-listed dynamic dial install state machine. It never retries or cleans up an unknown state. */
final class DynamicDialInstallProtocol {
    enum State { IDLE, PREFLIGHT, UPLOAD, READBACK, INSTALL, INVENTORY, APPLY, VERIFY, DONE, FAILED }
    interface Transport { void request(String operation, byte[][] args); void log(String message); void terminal(boolean success); }
    private final byte[] image; private final String path, alias; private final Transport transport;
    private State state=State.IDLE; private boolean terminal;
    DynamicDialInstallProtocol(byte[] image, Transport transport) {
        if(image==null||image.length==0||image.length>32*1024*1024) throw new IllegalArgumentException("invalid dial package");
        this.image=image.clone(); this.path=BundledDynamicDial.REMOTE_PATH; this.alias=BundledDynamicDial.REMOTE_ALIAS; this.transport=transport;
    }
    State state(){return state;}
    boolean isRunning(){return !terminal && state!=State.IDLE;}
    void start(){ if(state!=State.IDLE) throw new IllegalStateException("already started"); state=State.PREFLIGHT; transport.log("DIAL preflight current/inventory/backup/staging absent"); transport.request("preflight", new byte[][]{new byte[0]}); }
    void preflightComplete(boolean currentRead, boolean inventoryRead, boolean backupAbsent, boolean stagingAbsent){
        if(state!=State.PREFLIGHT) return; if(!currentRead||!inventoryRead||!backupAbsent||!stagingAbsent){fail("preflight failed");return;} state=State.UPLOAD; transport.request("upload", new byte[][]{image.clone()});
    }
    void uploadComplete(boolean exact){if(state!=State.UPLOAD)return; if(!exact){fail("upload failed");return;} state=State.READBACK; transport.request("readback", new byte[][]{new byte[0]});}
    void readbackComplete(byte[] bytes){if(state!=State.READBACK)return; if(!Arrays.equals(image,bytes)){fail("readback mismatch");return;} state=State.INSTALL; DynamicDialInstallRequest r=DynamicDialInstallRequest.install(path,alias); transport.request(r.service,r.args);}
    void installComplete(int result){if(state!=State.INSTALL)return; if(result!=0){fail("install result="+result);return;} state=State.INVENTORY; transport.request("inventory", new byte[][]{new byte[0]});}
    void inventoryComplete(boolean exact){if(state!=State.INVENTORY)return; if(!exact){fail("identity mismatch");return;} state=State.APPLY; DynamicDialInstallRequest r=DynamicDialInstallRequest.apply(alias); transport.request(r.service,r.args);}
    void applyComplete(int result){if(state!=State.APPLY)return; if(result!=0){fail("apply result="+result);return;} state=State.VERIFY; transport.request("verify-current", new byte[][]{new byte[0]});}
    void verifyComplete(boolean exact){if(state!=State.VERIFY)return; if(!exact){fail("current identity mismatch");return;} state=State.DONE; terminal=true; transport.log("DIAL install applied and verified alias="+alias); transport.terminal(true);}
    void cancel(String reason){if(!terminal)fail(reason==null?"cancelled":reason);}
    private void fail(String reason){if(terminal)return; terminal=true; state=State.FAILED; transport.log("DIAL install terminal failure: "+reason+"; no retry/delete/apply follow-up"); transport.terminal(false);}
}
