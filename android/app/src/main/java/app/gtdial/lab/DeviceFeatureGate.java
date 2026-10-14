package app.gtdial.lab;

/** Root alone selects one physical candidate in source. No runtime preference or intent unlocks it. */
final class DeviceFeatureGate {
    enum Candidate { NONE, SPORTS_PAGE, ALARM_READ, ALARM_CREATE, ALARM_UPDATE, ALARM_DELETE, ALARM_RESTORE }
    static final Candidate CANDIDATE=Candidate.NONE;
    static boolean sportsAllowed(){return CANDIDATE==Candidate.SPORTS_PAGE;}
    static boolean alarmReadAllowed(){return CANDIDATE==Candidate.ALARM_READ||alarmWriteAllowed();}
    static boolean alarmWriteAllowed(){return CANDIDATE==Candidate.ALARM_CREATE||CANDIDATE==Candidate.ALARM_UPDATE||CANDIDATE==Candidate.ALARM_DELETE||CANDIDATE==Candidate.ALARM_RESTORE;}
    static boolean alarmWriteAllowed(AlarmTransferPlan.Action action){
        if(action==null)return false;
        switch(action){
            case CREATE:return CANDIDATE==Candidate.ALARM_CREATE;
            case UPDATE:return CANDIDATE==Candidate.ALARM_UPDATE;
            case DELETE:return CANDIDATE==Candidate.ALARM_DELETE;
            case RESTORE:return CANDIDATE==Candidate.ALARM_RESTORE;
            default:return false;
        }
    }
    private DeviceFeatureGate(){ }
}
