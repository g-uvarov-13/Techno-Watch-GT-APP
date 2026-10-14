package app.gtdial.lab;
final class NotificationRequest {
    final String packageName,title,text; final long epochSeconds;
    NotificationRequest(String packageName,String title,String text,long epochSeconds){this.packageName=packageName;this.title=title;this.text=text;this.epochSeconds=epochSeconds;}
}
