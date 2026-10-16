package app.gtdial.lab;

/** Pure rules for the persisted, bounded session restart contract. */
final class SessionRecoveryPolicy {
    static final String STOPPED="STOPPED", IDLE="IDLE", OPERATION="OPERATION", RECONNECT_WAIT="RECONNECT_WAIT";
    private SessionRecoveryPolicy() { }
    static boolean mayRecover(boolean userEnabled,String verifiedAddress,String marker,boolean recoveryConsumed,boolean hasPermissions) {
        return userEnabled&&verifiedAddress!=null&&!verifiedAddress.isBlank()&&(IDLE.equals(marker)||RECONNECT_WAIT.equals(marker))&&hasPermissions;
    }
    static boolean mayRecover(boolean userEnabled,String address,String marker,String kind,boolean hasPermissions){
        return userEnabled&&address!=null&&!address.isBlank()&&hasPermissions
                &&(IDLE.equals(marker)||RECONNECT_WAIT.equals(marker)||OPERATION.equals(marker)&&isOrdinary(kind));
    }
    static boolean isOrdinary(String kind) {
        return "ACTIVITY".equals(kind)||"CURRENT_DIAL".equals(kind)
                ||"SPO2_PAGE".equals(kind)||"HEART_LAST".equals(kind)||"SLEEP_PAGE".equals(kind)||"WEATHER_GET".equals(kind);
    }
    static String beforeProtocolOperation(String ignored) { return OPERATION; }
    static String afterProtocolOperation(boolean success) { return success?IDLE:STOPPED; }
    static String manualReconnect() { return IDLE; }
    static String manualDisconnect() { return STOPPED; }
}
