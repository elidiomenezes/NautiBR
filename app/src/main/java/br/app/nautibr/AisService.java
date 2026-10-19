package br.app.nautibr;

import android.app.*;
import android.content.*;
import android.os.*;
import android.content.pm.ServiceInfo;
import org.json.*;
import okhttp3.*;
import okio.ByteString;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class AisService extends Service {
 public static final String ACTION_STREAM="br.app.nautibr.AIS_STREAM",ACTION_TCP="br.app.nautibr.AIS_TCP",ACTION_STOP="br.app.nautibr.AIS_STOP";
 private static final String CHANNEL="ais_monitor",PREF="ais_background";
 private static final int NOTIFICATION=1147;
 private static volatile AisService current;
 private static final String DIAG="ais_diagnostics";
 public static String buildLabel(Context context){try{android.content.pm.PackageInfo p=context.getPackageManager().getPackageInfo(context.getPackageName(),0);return p.versionName+" ("+(Build.VERSION.SDK_INT>=28?p.getLongVersionCode():p.versionCode)+")";}catch(Exception e){return "unknown";}}
 public static void diagnostic(Context context,String message){
  try{android.content.SharedPreferences p=context.getSharedPreferences(DIAG,MODE_PRIVATE);
   String old=p.getString("log","");String line=new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",java.util.Locale.US).format(new java.util.Date())+" [NautiBR "+buildLabel(context)+"] "+message.replaceAll("(?i)(apikey|key)[=:][^\\s,}]+","$1=[REDACTED]");
   String next=old+"\n"+line;p.edit().putString("log",next.substring(Math.max(0,next.length()-16000))).commit();
  }catch(Throwable ignored){}
 }
 public static void installCrashHandler(Context context){
  final Thread.UncaughtExceptionHandler previous=Thread.getDefaultUncaughtExceptionHandler();
  if(previous instanceof AisCrashHandler)return;
  Thread.setDefaultUncaughtExceptionHandler(new AisCrashHandler(context.getApplicationContext(),previous));
 }
 private static class AisCrashHandler implements Thread.UncaughtExceptionHandler{
  private final Context context;private final Thread.UncaughtExceptionHandler previous;
  AisCrashHandler(Context c,Thread.UncaughtExceptionHandler p){context=c;previous=p;}
  @Override public void uncaughtException(Thread t,Throwable e){
   StringWriter w=new StringWriter();e.printStackTrace(new PrintWriter(w));diagnostic(context,"CRASH "+t.getName()+" "+w.toString().substring(0,Math.min(6500,w.toString().length())));
   if(previous!=null)previous.uncaughtException(t,e);
  }
 }
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final OkHttpClient client=new OkHttpClient.Builder().pingInterval(25,TimeUnit.SECONDS).build();
 private WebSocket ws;
 private Socket tcp;
 private Thread tcpThread;
 private Runnable retry;
 private int generation=0;
 private boolean active=false;
 private String mode="",key="",host="";
 private int port;
 private double lat,lon,radius;
 private String extraAreas="[]";
 private AisTrackDb trackDb;
 private final LinkedHashMap<String,String> recent=new LinkedHashMap<>();
 public static void emit(String fn,String data){
  AisService svc=current;
  if(svc!=null)svc.deliver(fn,data);
 }
 private void deliver(String fn,String data){
  Intent i=new Intent("br.app.nautibr.AIS_EVENT");i.setPackage(getPackageName());i.putExtra("fn",fn);i.putExtra("data",data);sendBroadcast(i);
  if("onAisStreamMessage".equals(fn)||"onAisSentence".equals(fn)){
   if(trackDb!=null && "onAisStreamMessage".equals(fn))trackDb.ingestStream(data);
   synchronized(recent){
    String id=Long.toString(System.currentTimeMillis())+"_"+recent.size();
    recent.put(id,data);
    while(recent.size()>250)recent.remove(recent.keySet().iterator().next());
    JSONArray arr=new JSONArray();
    for(String value:recent.values())arr.put(value);
    getSharedPreferences(PREF,MODE_PRIVATE).edit().putString("recent",arr.toString()).apply();
   }
  }
 }
 @Override public void onCreate(){
  super.onCreate();installCrashHandler(this);diagnostic(this,"AIS service onCreate Android "+Build.VERSION.SDK_INT);current=this;trackDb=new AisTrackDb(this);
  if(Build.VERSION.SDK_INT>=26){
   NotificationChannel channel=new NotificationChannel(CHANNEL,"Monitor AIS",NotificationManager.IMPORTANCE_LOW);
   getSystemService(NotificationManager.class).createNotificationChannel(channel);
  }
 }
 private Notification notification(String status){
  Intent open=new Intent(this,MainActivity.class);
  PendingIntent pending=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  Intent stop=new Intent(this,AisService.class).setAction(ACTION_STOP);
  PendingIntent stopPending=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  return new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
   .setContentTitle("NautiBR · AIS ativo").setContentText(status)
   .setContentIntent(pending).setOngoing(true)
   .addAction(android.R.drawable.ic_menu_close_clear_cancel,"Desconectar",stopPending).build();
 }
 private void foreground(String text){
  diagnostic(this,"foreground request: "+text);
  if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,notification(text),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
  else startForeground(NOTIFICATION,notification(text));
  diagnostic(this,"foreground CONFIRMED: "+text);
 }
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  if(intent==null){restore();return active?START_STICKY:START_NOT_STICKY;}
  String action=intent.getAction();diagnostic(this,"AIS onStartCommand action="+action);
  if(ACTION_STOP.equals(action)){disconnect();stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);return START_NOT_STICKY;}
  try{
  if(!ACTION_STREAM.equals(action)&&!ACTION_TCP.equals(action)){stopSelfResult(startId);return START_NOT_STICKY;}
  // Promote before any synchronous storage or network work. Avoid stopSelf races on reconnect.
  foreground("AIS · iniciando");
  disconnect();
  if(ACTION_STREAM.equals(action)){
   key=intent.getStringExtra("key");lat=intent.getDoubleExtra("lat",0);lon=intent.getDoubleExtra("lon",0);radius=intent.getDoubleExtra("radius",20);extraAreas=intent.getStringExtra("extraAreas");if(extraAreas==null)extraAreas="[]";
   if(key==null||key.isEmpty()||!Double.isFinite(lat)||!Double.isFinite(lon)||!Double.isFinite(radius)||radius<1||radius>300){stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);return START_NOT_STICKY;}
   mode="stream";active=true;save();connectStream(generation,0);
  }else if(ACTION_TCP.equals(action)){
   host=intent.getStringExtra("host");port=intent.getIntExtra("port",0);
   if(host==null||!host.matches("[a-zA-Z0-9.:-]+")||port<1||port>65535){stopForeground(STOP_FOREGROUND_REMOVE);stopSelfResult(startId);return START_NOT_STICKY;}
   mode="tcp";active=true;save();connectTcp(generation);
  }
  return START_STICKY;
  }catch(Throwable error){
   android.util.Log.e("NautiBR-AIS","AIS service start failed",error);diagnostic(this,"AIS service start failed: "+android.util.Log.getStackTraceString(error));
   deliver("onAisStatus","AIS: erro ao iniciar serviço: "+error.getClass().getSimpleName()+": "+error.getMessage());
   disconnect();stopSelfResult(startId);return START_NOT_STICKY;
  }
 }
 private void save(){
  getSharedPreferences(PREF,MODE_PRIVATE).edit().putBoolean("active",active).putString("mode",mode)
   .putString("key",key).putString("extraAreas",extraAreas).putString("host",host).putInt("port",port)
   .putLong("lat",Double.doubleToRawLongBits(lat)).putLong("lon",Double.doubleToRawLongBits(lon))
   .putLong("radius",Double.doubleToRawLongBits(radius)).apply();
 }
 private void restore(){
  android.content.SharedPreferences p=getSharedPreferences(PREF,MODE_PRIVATE);
  if(!p.getBoolean("active",false)){stopSelf();return;}
  Intent i=new Intent(this,AisService.class);
  if("stream".equals(p.getString("mode",""))){
   i.setAction(ACTION_STREAM).putExtra("key",p.getString("key",""))
    .putExtra("extraAreas",p.getString("extraAreas","[]"))
    .putExtra("lat",Double.longBitsToDouble(p.getLong("lat",0)))
    .putExtra("lon",Double.longBitsToDouble(p.getLong("lon",0)))
    .putExtra("radius",Double.longBitsToDouble(p.getLong("radius",0)));
  }else i.setAction(ACTION_TCP).putExtra("host",p.getString("host","")).putExtra("port",p.getInt("port",0));
  onStartCommand(i,0,0);
 }
 private synchronized void disconnect(){
  active=false;generation++;
  if(retry!=null)handler.removeCallbacks(retry);retry=null;
  if(ws!=null){ws.cancel();ws=null;}
  if(tcp!=null)try{tcp.close();}catch(IOException ignored){}
  tcp=null;if(tcpThread!=null)tcpThread.interrupt();
  getSharedPreferences(PREF,MODE_PRIVATE).edit().putBoolean("active",false).apply();
 }
 private void schedule(int gen,int attempt,Runnable connect){
  if(!active||gen!=generation)return;
  if(retry!=null)handler.removeCallbacks(retry);
  long delay=Math.min(60000L,2000L*(1L<<Math.min(attempt,5)))+new Random().nextInt(1000);
  deliver("onAisStatus","AIS: reconexão em "+(delay/1000)+" s");
  retry=()->{if(active&&gen==generation)connect.run();};
  handler.postDelayed(retry,delay);
 }
 private void connectStream(int gen,int attempt){
  if(!active||gen!=generation)return;
  try{
   double dlat=radius/111.32,dlon=radius/(111.32*Math.max(.01,Math.cos(Math.toRadians(lat))));
   JSONArray box=new JSONArray().put(new JSONArray().put(Math.max(-90,lat-dlat)).put(Math.max(-180,lon-dlon)))
    .put(new JSONArray().put(Math.min(90,lat+dlat)).put(Math.min(180,lon+dlon)));
   JSONArray boxes=new JSONArray().put(box);
   JSONArray extras=new JSONArray(extraAreas);
   if(extras.length()>8)throw new IOException("Maximum 8 extra AIS regions");
   for(int j=0;j<extras.length();j++){
    JSONArray region=extras.getJSONArray(j);
    double la=region.getDouble(0),lo=region.getDouble(1),ra=region.getDouble(2);
    if(!Double.isFinite(la)||!Double.isFinite(lo)||!Double.isFinite(ra)||Math.abs(la)>90||Math.abs(lo)>180||ra<1||ra>300)
     throw new IOException("Invalid extra AIS region");
    double dy=ra/111.32,dx=ra/(111.32*Math.max(.01,Math.cos(Math.toRadians(la))));
    boxes.put(new JSONArray().put(new JSONArray().put(Math.max(-90,la-dy)).put(Math.max(-180,lo-dx)))
      .put(new JSONArray().put(Math.min(90,la+dy)).put(Math.min(180,lo+dx))));
   }
   String sub=new JSONObject().put("APIKey",key).put("BoundingBoxes",boxes)
    .put("FilterMessageTypes",new JSONArray().put("PositionReport").put("StandardClassBPositionReport").put("ExtendedClassBPositionReport").put("ShipStaticData").put("StaticDataReport")).toString();
   ws=client.newWebSocket(new Request.Builder().url("wss://stream.aisstream.io/v0/stream").build(),new WebSocketListener(){
    boolean opened=false;
    @Override public void onOpen(WebSocket socket,Response response){
     if(gen!=generation){socket.cancel();return;}
     opened=true;try{foreground("AISStream · conectado");}catch(RuntimeException e){android.util.Log.e("NautiBR-AIS","Foreground update failed",e);}
     deliver("onAisStatus","AISStream: conectado; assinatura enviada");
     if(!socket.send(sub)){socket.cancel();schedule(gen,0,()->connectStream(gen,0));}
    }
    @Override public void onMessage(WebSocket socket,String message){if(gen==generation&&message.length()<1000000)deliver("onAisStreamMessage",message);}
    @Override public void onMessage(WebSocket socket,ByteString bytes){if(gen==generation&&bytes.size()<1000000)deliver("onAisStreamMessage",bytes.utf8());}
    @Override public void onClosing(WebSocket socket,int code,String reason){socket.close(1000,null);}
    @Override public void onClosed(WebSocket socket,int code,String reason){
     if(gen!=generation)return;
     deliver("onAisStatus","AISStream: conexão encerrada ("+code+")");
     if(code==1008||code==4001){deliver("onAisStatus","AISStream: encerramento permanente ("+code+")");disconnect();stopSelf();}
     else schedule(gen,opened?0:attempt+1,()->connectStream(gen,opened?0:attempt+1));
    }
    @Override public void onFailure(WebSocket socket,Throwable err,Response response){
     if(gen!=generation)return;
     diagnostic(AisService.this,"WebSocket failure: "+err.getClass().getSimpleName()+": "+err.getMessage()+" HTTP="+(response==null?"none":response.code()));deliver("onAisStatus","AISStream: "+err.getClass().getSimpleName()+": "+err.getMessage());
     if(response!=null&&response.code()>=400&&response.code()<500&&response.code()!=429){deliver("onAisStatus","AISStream: erro HTTP permanente "+response.code());disconnect();stopSelf();}
     else schedule(gen,opened?0:attempt+1,()->connectStream(gen,opened?0:attempt+1));
    }
   });
  }catch(Exception e){diagnostic(this,"connectStream: "+android.util.Log.getStackTraceString(e));deliver("onAisStatus","AISStream: "+e.getMessage());schedule(gen,attempt+1,()->connectStream(gen,attempt+1));}
 }
 private void connectTcp(int gen){
  tcpThread=new Thread(()->{
   int attempt=0;
   while(active&&gen==generation){
    try{
     Socket socket=new Socket();tcp=socket;socket.connect(new InetSocketAddress(host,port),8000);socket.setSoTimeout(30000);
     attempt=0;try{foreground("AIS TCP · conectado");}catch(RuntimeException e){android.util.Log.e("NautiBR-AIS","Foreground update failed",e);}
     deliver("onAisStatus","AIS TCP: conectado");
     try(BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII))){
      String line;while(active&&gen==generation&&(line=reader.readLine())!=null){
       if(line.length()<=2048&&(line.startsWith("!AIVDM,")||line.startsWith("!AIVDO,")||line.startsWith("!BSVDM,")))deliver("onAisSentence",line);
      }
     }
    }catch(Exception e){if(active&&gen==generation)deliver("onAisStatus","AIS TCP: "+e.getMessage());}
    finally{if(tcp!=null)try{tcp.close();}catch(Exception ignored){}tcp=null;}
    if(!active||gen!=generation)break;
    long delay=Math.min(60000L,2000L*(1L<<Math.min(attempt++,5)))+new Random().nextInt(1000);
    deliver("onAisStatus","AIS TCP: reconexão em "+(delay/1000)+" s");
    try{Thread.sleep(delay);}catch(InterruptedException e){break;}
   }
  },"nautibr-ais-tcp");tcpThread.start();
 }
 @Override public void onDestroy(){disconnect();current=null;if(trackDb!=null)trackDb.close();client.dispatcher().executorService().shutdown();super.onDestroy();}
 @Override public IBinder onBind(Intent intent){return null;}
}
