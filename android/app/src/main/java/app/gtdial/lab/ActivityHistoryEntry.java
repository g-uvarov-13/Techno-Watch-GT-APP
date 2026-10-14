package app.gtdial.lab;

/** One observed cumulative activity response; raw values have no inferred units. */
final class ActivityHistoryEntry {
    final long id, receivedAt;
    final String deviceAddress, phoneZoneId, phoneDay, source;
    final int steps, calorieRaw, distanceRaw;
    ActivityHistoryEntry(long id,String deviceAddress,long receivedAt,String phoneZoneId,String phoneDay,
            int steps,int calorieRaw,int distanceRaw,String source){
        this.id=id;this.deviceAddress=deviceAddress;this.receivedAt=receivedAt;
        this.phoneZoneId=phoneZoneId;this.phoneDay=phoneDay;this.steps=steps;
        this.calorieRaw=calorieRaw;this.distanceRaw=distanceRaw;this.source=source;
    }
}
