package app.gtdial.lab;

final class WeatherCodeMapper {
    static int qcDescribe(int wmo){switch(wmo){case 0:case 1:return 0;case 2:return 1;case 3:return 2;case 45:case 48:return 181;case 51:case 53:case 55:case 56:case 57:case 61:case 63:case 65:case 66:case 67:case 80:case 81:case 82:return 3;case 71:case 73:case 75:case 77:case 85:case 86:return 141;case 95:case 96:case 97:case 99:return 62;default:throw new IllegalArgumentException("WMO code is not approved: "+wmo);}}
    static String officialCategory(int wmo){if(wmo==0)return "clear";if(wmo<=3)return "cloudy";if(wmo<=48)return "fog";if(wmo<=67)return "rain";if(wmo<=77)return "snow";if(wmo<=82)return "showers";return "thunder";}
    static String describe(int wmo){return "WMO " + wmo + " (" + officialCategory(wmo) + ")";}
}
