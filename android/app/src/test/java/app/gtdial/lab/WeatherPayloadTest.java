package app.gtdial.lab;
import static org.junit.Assert.*;import org.junit.Test;
public class WeatherPayloadTest {
 @Test public void payloadNeverPermitsBleUntilWatchTemperatureSchemaIsKnown(){WeatherPayload p=new WeatherPayload(new WeatherSnapshot(1,2,17.5,63,1,"Open-Meteo"));assertFalse(p.readyForBle());assertTrue(p.blockedReason().contains("unverified"));assertEquals("rain",p.category);}
 @Test public void refreshUsesThirtyAndNinetyMinuteBoundaries(){WeatherSnapshot s=new WeatherSnapshot(0,0,0,0,0,"x");assertTrue(WeatherRefreshController.isFresh(s,30*60*1000L));assertFalse(WeatherRefreshController.needsRefresh(s,30*60*1000L));assertTrue(WeatherRefreshController.needsRefresh(s,90*60*1000L));}
 @Test public void mapsWmoWithoutClaimingWatchSemantics(){assertEquals("clear",WeatherCodeMapper.officialCategory(0));assertEquals("thunder",WeatherCodeMapper.officialCategory(99));}
 @Test public void rejectsMalformedOrSpoofedJson(){try{OpenMeteoWeatherProvider.parse("{\"temperature_2m\":99}".getBytes("UTF-8"),0,0,"x",1);fail();}catch(Exception expected){}}
 @Test public void boundedReaderRejectsOversize() throws Exception {byte[] bytes=new byte[OpenMeteoWeatherProvider.MAX_BYTES+1];try{OpenMeteoWeatherProvider.readBounded(new java.io.ByteArrayInputStream(bytes));fail();}catch(java.io.IOException expected){}}
}
