package br.app.nautibr;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.util.zip.*;
import java.util.*;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
 private static final int PICK=42;
 private static final String CATALOG="https://www.marinha.mil.br/chm/dados-do-segnav/cartas-raster";
 private WebView web;
 private File charts;
 private volatile boolean aisRunning=false;
 private Thread aisThread;
 private java.net.Socket aisSocket;
 private final OkHttpClient aisHttp=new OkHttpClient.Builder().pingInterval(25,TimeUnit.SECONDS).build();
 private volatile WebSocket aisWebSocket;
 private volatile int aisGeneration=0;
 @Override public void onCreate(Bundle state){
  super.onCreate(state);
  charts=new File(getFilesDir(),"charts");charts.mkdirs();
  web=new WebView(this);setContentView(web);
  web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
  web.addJavascriptInterface(new Bridge(),"Native");
  web.setWebViewClient(new WebViewClient(){
   @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest req){
    Uri uri=req.getUrl();
    if(!"nautibr.local".equals(uri.getHost()))return super.shouldInterceptRequest(view,req);
    if(uri.getPath()!=null && uri.getPath().startsWith("/vendor/"))try{String filename=uri.getPath().substring(1);String mime=filename.endsWith(".css")?"text/css":"application/javascript";return new WebResourceResponse(mime,"UTF-8",getAssets().open(filename));}catch(Exception e){return null;}
    if("/index.html".equals(uri.getPath()))try{return new WebResourceResponse("text/html","UTF-8",getAssets().open("index.html"));}catch(Exception e){return null;}
    if(!"/chart".equals(uri.getPath()))return null;
    try{
     String id=uri.getQueryParameter("id");
     if(id==null||!id.matches("[0-9a-f]{32}(?:_[0-9]+_[0-9]+)?\\.(png|jpg|jpeg|webp)"))return null;
     File file=new File(charts,id);
     String type=id.endsWith(".png")?"image/png":id.endsWith(".webp")?"image/webp":"image/jpeg";
     return new WebResourceResponse(type,null,new FileInputStream(file));
    }catch(Exception e){return null;}
   }
  });
  web.loadUrl("https://nautibr.local/index.html");
 }
 synchronized void stopAisConnection(){
  aisRunning=false;
  try{if(aisSocket!=null)aisSocket.close();}catch(Exception ignored){}
  aisSocket=null;
 }
 synchronized void stopAisStream(){
  aisGeneration++;
  WebSocket socket=aisWebSocket;aisWebSocket=null;
  if(socket!=null)socket.close(1000,"Disconnected");
 }
 @Override protected void onDestroy(){stopAisConnection();stopAisStream();aisHttp.dispatcher().executorService().shutdown();super.onDestroy();}
 void callback(String fn,String value){runOnUiThread(()->web.evaluateJavascript(fn+"("+JSONObject.quote(value)+")",null));}
 void status(String s){callback("onStatus",s);}
 public class Bridge{
  @JavascriptInterface public void stopAISStream(){stopAisStream();}
  @JavascriptInterface public void connectAISStream(String key,double lat,double lon,double radius){
   if(key==null||key.trim().isEmpty()||key.length()>512||!Double.isFinite(lat)||Math.abs(lat)>90||!Double.isFinite(lon)||Math.abs(lon)>180||!Double.isFinite(radius)||radius<1||radius>300){
    callback("onAisStatus","AISStream: parâmetros inválidos");return;
   }
   stopAisStream();
   final int generation=aisGeneration;
   final double dLat=radius/111.32,dLon=radius/(111.32*Math.max(.01,Math.cos(Math.toRadians(lat))));
   final String apiKey=key.trim();
   try{
    JSONArray southWest=new JSONArray().put(Math.max(-90,lat-dLat)).put(Math.max(-180,lon-dLon));
    JSONArray northEast=new JSONArray().put(Math.min(90,lat+dLat)).put(Math.min(180,lon+dLon));
    JSONArray box=new JSONArray().put(southWest).put(northEast);
    JSONObject subscription=new JSONObject()
     .put("APIKey",apiKey)
     .put("BoundingBoxes",new JSONArray().put(box))
     .put("FilterMessageTypes",new JSONArray().put("PositionReport").put("StandardClassBPositionReport").put("ExtendedClassBPositionReport"));
    Request request=new Request.Builder().url("wss://stream.aisstream.io/v0/stream").build();
    callback("onAisStatus","AISStream: conectando por WebSocket nativo");
    aisWebSocket=aisHttp.newWebSocket(request,new WebSocketListener(){
     @Override public void onOpen(WebSocket socket,Response response){
      if(generation!=aisGeneration){socket.close(1000,"Superseded");return;}
      callback("onAisStatus","AISStream: conexão WSS estabelecida (HTTP "+response.code()+")");
      if(socket.send(subscription.toString()))callback("onAisStatus","AISStream: assinatura enviada; aguardando resposta");
      else callback("onAisStatus","AISStream: falha ao enviar assinatura");
     }
     @Override public void onMessage(WebSocket socket,String message){
      if(generation==aisGeneration && message.length()<1_000_000)callback("onAisStreamMessage",message);
     }
     @Override public void onMessage(WebSocket socket,ByteString bytes){
      if(generation==aisGeneration && bytes.size()<1_000_000)callback("onAisStreamMessage",bytes.utf8());
     }
     @Override public void onClosing(WebSocket socket,int code,String reason){
      if(generation==aisGeneration)callback("onAisStatus","AISStream: servidor encerrando ("+code+"): "+reason);
      socket.close(1000,null);
     }
     @Override public void onClosed(WebSocket socket,int code,String reason){
      if(generation==aisGeneration){aisWebSocket=null;callback("onAisStatus","AISStream: encerrado ("+code+"): "+reason);}
     }
     @Override public void onFailure(WebSocket socket,Throwable error,Response response){
      if(generation==aisGeneration){
       aisWebSocket=null;
       String reason=error.getClass().getSimpleName()+": "+error.getMessage();
       callback("onAisStatus","AISStream: falha WSS"+(response==null?"":" HTTP "+response.code())+" — "+reason);
      }
     }
    });
   }catch(Exception error){callback("onAisStatus","AISStream: "+error.getMessage());}
  }
  @JavascriptInterface public void stopAIS(){stopAisConnection();}
  @JavascriptInterface public void connectAIS(String host,int port){
   stopAisConnection();
   if(host==null||host.length()>255||!host.matches("[a-zA-Z0-9.:-]+")||port<1||port>65535){callback("onAisStatus","Endereço ou porta inválidos");return;}
   aisRunning=true;
   aisThread=new Thread(()->{
    try{
     java.net.Socket socket=new java.net.Socket();
     aisSocket=socket;
     socket.connect(new java.net.InetSocketAddress(host,port),8000);
     socket.setSoTimeout(30000);
     callback("onAisStatus","Receptor AIS conectado: "+host+":"+port);
     try(BufferedReader br=new BufferedReader(new InputStreamReader(socket.getInputStream(),java.nio.charset.StandardCharsets.US_ASCII))){
      String line;
      while(aisRunning&&(line=br.readLine())!=null){
       if(line.length()>2048)continue;
       if(line.startsWith("!AIVDM,")||line.startsWith("!AIVDO,")||line.startsWith("!BSVDM,"))
        callback("onAisSentence",line);
      }
     }
    }catch(Exception e){if(aisRunning)callback("onAisStatus","AIS TCP: "+e.getMessage());}
    finally{if(aisRunning)callback("onAisStatus","Conexão AIS encerrada");aisRunning=false;}
   },"ais-tcp");
   aisThread.start();
  }
  @JavascriptInterface public void pollAIS(String url){
   if(url==null||url.length()>2048||!url.startsWith("https://")){callback("onAisStatus","Use uma URL HTTPS de dados AIS");return;}
   new Thread(()->{
    java.net.HttpURLConnection conn=null;
    try{
     conn=(java.net.HttpURLConnection)new java.net.URL(url).openConnection();
     conn.setConnectTimeout(10000);conn.setReadTimeout(15000);
     conn.setRequestProperty("Accept","application/json, text/plain");
     conn.setInstanceFollowRedirects(false);
     int code=conn.getResponseCode();
     if(code!=200)throw new IOException("HTTP "+code);
     if(conn.getContentLengthLong()>2_000_000)throw new IOException("Resposta AIS maior que 2 MB");
     try(InputStream in=conn.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
      byte[] buf=new byte[8192];int n;
      while((n=in.read(buf))!=-1){if(out.size()+n>2_000_000)throw new IOException("Resposta AIS maior que 2 MB");out.write(buf,0,n);}
      callback("onAisInternet",out.toString("UTF-8"));
     }
    }catch(Exception e){callback("onAisStatus","AIS internet: "+e.getMessage());}
    finally{if(conn!=null)conn.disconnect();}
   },"ais-https").start();
  }
  @JavascriptInterface public void copyText(String value){runOnUiThread(()->{ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("NautiBR",value));});}
  @JavascriptInterface public void importCharts(){runOnUiThread(()->{
   Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);
   i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(i,PICK);
  });}
  @JavascriptInterface public String bsbFiles(){
   JSONArray a=new JSONArray();File[] files=charts.listFiles();
   if(files!=null)for(File file:files)if(file.getName().endsWith(".bsb.json"))try{
    a.put(new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),"UTF-8")));
   }catch(Exception ignored){}
   return a.toString();
  }
  @JavascriptInterface public String kapFiles(){
   JSONArray a=new JSONArray();File[] files=charts.listFiles();
   if(files!=null)for(File file:files)if(file.getName().endsWith(".kap.json"))try{JSONObject o=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),"UTF-8"));a.put(o);}catch(Exception ignored){}
   return a.toString();
  }
  @JavascriptInterface public void importGeo(){
   runOnUiThread(()->{
    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);
    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(i,43);
   });
  }
  @JavascriptInterface public String geoFiles(){
   JSONArray a=new JSONArray();
   File dir=new File(getFilesDir(),"geo");dir.mkdirs();
   File[] files=dir.listFiles();if(files!=null)for(File f:files)if(f.getName().endsWith(".geojson")){
    JSONObject o=new JSONObject();
    try{o.put("id",f.getName());o.put("name",getPreferences(0).getString(f.getName(),f.getName()));a.put(o);}catch(Exception ignored){}
   }
   return a.toString();
  }
  @JavascriptInterface public String readGeo(String id){
   if(id==null||!id.matches("[0-9a-f]{32}\\.geojson"))return "{}";
   try{
    File f=new File(new File(getFilesDir(),"geo"),id);
    if(f.length()>5_000_000)return "{}";
    return new String(java.nio.file.Files.readAllBytes(f.toPath()),java.nio.charset.StandardCharsets.UTF_8);
   }catch(Exception e){return "{}";}
  }
  @JavascriptInterface public void openNavy(){runOnUiThread(()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(CATALOG))));}
 }
 @Override protected void onActivityResult(int req,int result,Intent data){
  super.onActivityResult(req,result,data);
  if((req!=PICK&&req!=43)||result!=RESULT_OK||data==null)return;
  ArrayList<Uri> uris=new ArrayList<>();
  if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());
  else if(data.getData()!=null)uris.add(data.getData());
  new Thread(()->{
   int count=0;ArrayList<String> errors=new ArrayList<>();
   for(Uri uri:uris)try{
    String name="carta";try(android.database.Cursor c=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){
     if(c!=null&&c.moveToFirst())name=c.getString(0);
    }catch(Exception ignored){}
    String lower=name.toLowerCase(Locale.ROOT);
    if(lower.endsWith(".zip")){
     count+=importChartPackage(uri,name,req);
    }else if((lower.endsWith(".tif")||lower.endsWith(".tiff"))){errors.add(name+": GeoTIFF não suportado");}
    else if(isKap(lower)){try(InputStream in=getContentResolver().openInputStream(uri)){saveKap(in,name);count++;}}
    else if(isBsb(lower)){try(InputStream in=getContentResolver().openInputStream(uri)){saveBsb(in,name);count++;}}
    else if(req==43 && isGeo(lower)){try(InputStream in=getContentResolver().openInputStream(uri)){saveGeo(in,name);count++;}}
    else if(req==PICK && isImage(lower)){try(InputStream in=getContentResolver().openInputStream(uri)){saveImage(in,name);count++;}}
    else errors.add(name+": formato não suportado");
   }catch(Exception e){errors.add(e.getMessage());}
   status(count+" imagem(ns) importada(s). "+String.join("; ",errors));

  }).start();
 }
 int importChartPackage(Uri uri,String packageName,int req)throws Exception{
  File staged=File.createTempFile("nautibr-package-", ".zip",getCacheDir());
  File tempDir=new File(getCacheDir(),"nautibr-"+UUID.randomUUID().toString());
  tempDir.mkdirs();
  try{
   java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
   try(InputStream input=getContentResolver().openInputStream(uri);
       OutputStream output=new FileOutputStream(staged)){
    byte[] buf=new byte[32768];int n;long total=0;
    while((n=input.read(buf))!=-1){
     total+=n;if(total>350_000_000L)throw new IOException("ZIP maior que 350 MB");
     digest.update(buf,0,n);output.write(buf,0,n);
    }
   }
   StringBuilder hash=new StringBuilder();
   for(byte b:digest.digest())hash.append(String.format(Locale.US,"%02x",b&255));
   String packageId=hash.toString();
   File manifest=new File(charts,packageId+".package.json");
   if(manifest.exists())return 0;
   LinkedHashMap<String,File> kaps=new LinkedHashMap<>();
   LinkedHashMap<String,File> bsbs=new LinkedHashMap<>();
   LinkedHashMap<String,File> geos=new LinkedHashMap<>();
   int entries=0;long unpacked=0;
   try(ZipInputStream zip=new ZipInputStream(new FileInputStream(staged))){
    ZipEntry entry;
    while((entry=zip.getNextEntry())!=null){
     if(++entries>500)throw new IOException("ZIP com mais de 500 entradas");
     if(entry.isDirectory())continue;
     String leaf=new File(entry.getName().replace('\\','/')).getName();
     String key=leaf.toLowerCase(Locale.ROOT);
     if(!isKap(key)&&!isBsb(key)&&!(req==43&&isGeo(key)))continue;
     if(kaps.containsKey(key)||bsbs.containsKey(key)||geos.containsKey(key))
      throw new IOException("Nome de arquivo duplicado no ZIP: "+leaf);
     File file=new File(tempDir,UUID.randomUUID().toString());
     try(OutputStream output=new FileOutputStream(file)){
      byte[] buf=new byte[32768];int n;long fileSize=0;
      while((n=zip.read(buf))!=-1){
       unpacked+=n;fileSize+=n;
       if(unpacked>800_000_000L||fileSize>350_000_000L)throw new IOException("ZIP descompactado excede limite");
       output.write(buf,0,n);
      }
     }
     if(isKap(key))kaps.put(key,file);
     else if(isBsb(key))bsbs.put(key,file);
     else geos.put(key,file);
    }
   }
   // Catalogs are parsed first, independent of entry order in the ZIP.
   JSONArray catalogs=new JSONArray(),chartsInPackage=new JSONArray();
   LinkedHashMap<String,JSONArray> referencedBy=new LinkedHashMap<>();
   int count=0;
   for(Map.Entry<String,File> e:bsbs.entrySet()){
    String content=new String(java.nio.file.Files.readAllBytes(e.getValue().toPath()),"ISO-8859-1");
    JSONArray refs=kapNamesInBsb(content);
    for(int i=0;i<refs.length();i++){
     String key=new File(refs.getString(i).replace('\\','/')).getName().toLowerCase(Locale.ROOT);
     if(!referencedBy.containsKey(key))referencedBy.put(key,new JSONArray());
     referencedBy.get(key).put(e.getKey());
    }
    try(InputStream in=new FileInputStream(e.getValue())){saveBsb(in,packageName+" / "+e.getKey(),packageId,refs);}
    catalogs.put(new JSONObject().put("file",e.getKey()).put("references",refs));count++;
   }
   for(Map.Entry<String,File> e:kaps.entrySet()){
    try(InputStream in=new FileInputStream(e.getValue())){saveKap(in,packageName+" / "+e.getKey());}
    chartsInPackage.put(new JSONObject().put("file",e.getKey())
      .put("catalogs",referencedBy.containsKey(e.getKey())?referencedBy.get(e.getKey()):new JSONArray()));
    count++;
   }
   for(Map.Entry<String,File> e:geos.entrySet()){
    try(InputStream in=new FileInputStream(e.getValue())){saveGeo(in,packageName+" / "+e.getKey());}
    count++;
   }
   JSONObject record=new JSONObject().put("name",packageName).put("id",packageId)
     .put("catalogs",catalogs).put("charts",chartsInPackage);
   try(OutputStream out=new FileOutputStream(manifest)){out.write(record.toString().getBytes("UTF-8"));}
   return count;
  }finally{
   staged.delete();
   File[] files=tempDir.listFiles();if(files!=null)for(File file:files)file.delete();
   tempDir.delete();
  }
 }
 JSONArray kapNamesInBsb(String content){
  JSONArray names=new JSONArray();HashSet<String> seen=new HashSet<>();
  java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)([A-Za-z0-9_./\\-]+\\.kap)").matcher(content);
  while(m.find()){
   String file=new File(m.group(1).replace('\\','/')).getName();
   if(seen.add(file.toLowerCase(Locale.ROOT)))names.put(file);
  }
  return names;
 }
 boolean isBsb(String name){return name.toLowerCase(Locale.ROOT).endsWith(".bsb");}
 void saveBsb(InputStream in,String name)throws Exception{saveBsb(in,name,"",null);}
 void saveBsb(InputStream in,String name,String packageId,JSONArray knownRefs)throws Exception{
  ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
  while((n=in.read(buffer))!=-1){
   if(out.size()+n>1_000_000)throw new IOException("Catálogo BSB maior que 1 MB");
   out.write(buffer,0,n);
  }
  String content=out.toString("ISO-8859-1").replace("\\r","");
  if(!content.contains("BSB/")&&!content.contains("KNP/")&&!content.contains("CHK/"))
   throw new IOException("Cabeçalho BSB inválido: "+name);
  JSONArray references=knownRefs!=null?knownRefs:kapNamesInBsb(content);
  JSONObject o=new JSONObject().put("name",name).put("format","BSB catalog").put("kapReferences",references).put("packageId",packageId);
  String id=UUID.randomUUID().toString().replace("-","")+".bsb.json";
  try(OutputStream file=new FileOutputStream(new File(charts,id))){file.write(o.toString().getBytes("UTF-8"));}
 }
 boolean isKap(String name){return name.toLowerCase(Locale.ROOT).endsWith(".kap");}
 void saveKap(InputStream in,String name)throws Exception{
  BufferedInputStream input=new BufferedInputStream(in,32768);
  ByteArrayOutputStream header=new ByteArrayOutputStream();
  int c,prev=-1;
  while((c=input.read())!=-1){
   if(header.size()>1_000_000)throw new IOException("Cabeçalho KAP muito grande");
   if(prev==26&&c==0)break;
   header.write(c);prev=c;
  }
  if(c<0)throw new IOException("KAP sem separador");
  String text=header.toString("ISO-8859-1").replace("\\r","");
  String[] lines=text.split("\\n");
  int width=0,height=0;int[] palette=new int[128];Arrays.fill(palette,0xffffffff);
  ArrayList<double[]> refs=new ArrayList<>();String kind="";
  for(String line:lines){
   if(line.startsWith("BSB/")){kind="BSB";}else if(line.startsWith("REF/")){kind="REF";}
   else if(line.startsWith("RGB/")){kind="RGB";}
   if(line.startsWith("BSB/")||line.startsWith("RA=")){
    java.util.regex.Matcher m=java.util.regex.Pattern.compile("RA=(\\d+),(\\d+)").matcher(line);
    if(m.find()){width=Integer.parseInt(m.group(1));height=Integer.parseInt(m.group(2));}
   }
   if(line.startsWith("RGB/")){String[] a=line.substring(4).split(",");if(a.length>=4)try{
    int index=Integer.parseInt(a[0].trim());if(index>=0&&index<128)palette[index]=0xff000000|(Integer.parseInt(a[1].trim())<<16)|(Integer.parseInt(a[2].trim())<<8)|Integer.parseInt(a[3].trim());
   }catch(Exception ignored){}}
   if(line.startsWith("REF/")){String[] a=line.substring(4).split(",");if(a.length>=5)try{refs.add(new double[]{Double.parseDouble(a[1]),Double.parseDouble(a[2]),Double.parseDouble(a[3]),Double.parseDouble(a[4])});}catch(Exception ignored){}}
  }
  if(width<1||height<1||width>50000||height>50000)throw new IOException("Dimensões KAP não suportadas: "+width+"x"+height);
  int depth=input.read();if(depth<1||depth>7)throw new IOException("Profundidade KAP inválida: "+depth);
  // Keep native resolution by encoding independent 1024 x 512 PNG tiles.
  // Never allocate a full-size bitmap, even for large Navy charts.
  final int tileW=1024,tileH=512;
  String id=UUID.randomUUID().toString().replace("-","");
  JSONArray tiles=new JSONArray();
  int[] row=new int[width];
  int tileCols=(width+tileW-1)/tileW;
  Bitmap[] strip=new Bitmap[tileCols];
  for(int tx=0;tx<tileCols;tx++)strip[tx]=Bitmap.createBitmap(Math.min(tileW,width-tx*tileW),tileH,Bitmap.Config.ARGB_8888);
  try{
   for(int y=0;y<height;y++){
    int line=0,b;
    do{b=input.read();if(b<0)throw new EOFException("KAP truncado");line=(line<<7)|(b&127);}while((b&128)!=0);
    int x=0;
    while(true){
     b=input.read();if(b<0)throw new EOFException("KAP truncado na linha "+y);
     if(b==0)break;
     int index=(b&127)>>(7-depth),length=b&((1<<(7-depth))-1);
     while((b&128)!=0){b=input.read();if(b<0)throw new EOFException();length=(length<<7)|(b&127);}
     length++;
     if(x+length>width)throw new IOException("KAP RLE excede largura");
     Arrays.fill(row,x,x+length,palette[Math.min(index,127)]);x+=length;
    }
    if(x!=width)throw new IOException("Linha KAP incompleta: "+y+" ("+x+"/"+width+")");
    for(int tx=0;tx<tileCols;tx++){
     int left=tx*tileW,w=Math.min(tileW,width-left);
     strip[tx].setPixels(row,left,width,0,y%tileH,w,1);
    }
    if(y%tileH==tileH-1||y==height-1){
     int top=(y/tileH)*tileH,h=Math.min(tileH,height-top);
     for(int tx=0;tx<tileCols;tx++){
      int left=tx*tileW,w=Math.min(tileW,width-left);
      Bitmap crop=h==tileH?strip[tx]:Bitmap.createBitmap(strip[tx],0,0,w,h);
      String filename=id+"_"+tx+"_"+(top/tileH)+".png";
      try(OutputStream out=new FileOutputStream(new File(charts,filename))){
       if(!crop.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Falha ao gravar bloco KAP");
      }
      if(crop!=strip[tx])crop.recycle();
      tiles.put(new JSONObject().put("id",filename).put("x",left).put("y",top).put("w",w).put("h",h));
     }
    }
   }
  }finally{for(Bitmap bitmap:strip)bitmap.recycle();}
  if(refs.size()<4)throw new IOException("KAP exige ao menos 4 pontos REF para validação");
  // Fit a full 2-D affine transform from pixel coordinates to spherical Mercator.
  // Reject non-affine, non-Mercator and otherwise inconsistent charts.
  double[][] normal=new double[3][3];double[] east=new double[3],north=new double[3];
  for(double[] r:refs){
   if(Math.abs(r[2])>=85)throw new IOException("Latitude REF fora de Mercator");
   double[] v={1,r[0]/width,r[1]/height};
   double mx=Math.toRadians(r[3]);
   double my=Math.log(Math.tan(Math.PI/4+Math.toRadians(r[2])/2));
   for(int i=0;i<3;i++){east[i]+=v[i]*mx;north[i]+=v[i]*my;for(int j=0;j<3;j++)normal[i][j]+=v[i]*v[j];}
  }
  double[] lon=solve3(normal,east),merc=solve3(normal,north);
  double pixelError=0,maxPixelError=0;
  double determinant=lon[1]*merc[2]-lon[2]*merc[1];
  if(Math.abs(determinant)<1e-14)throw new IOException("Transformação REF degenerada");
  for(double[] r:refs){
   double[] v={1,r[0]/width,r[1]/height};
   double dx=lon[0]+lon[1]*v[1]+lon[2]*v[2]-Math.toRadians(r[3]);
   double dy=merc[0]+merc[1]*v[1]+merc[2]*v[2]-Math.log(Math.tan(Math.PI/4+Math.toRadians(r[2])/2));
   double px=(merc[2]*dx-lon[2]*dy)/determinant*width;
   double py=(-merc[1]*dx+lon[1]*dy)/determinant*height;
   double err=Math.hypot(px,py);pixelError+=err*err;maxPixelError=Math.max(maxPixelError,err);
  }
  double rms=Math.sqrt(pixelError/refs.size());
  if(maxPixelError>3 || rms>1.5)throw new IOException("Georreferenciamento rejeitado: RMS "+String.format(Locale.US,"%.2f",rms)+" px, máximo "+String.format(Locale.US,"%.2f",maxPixelError)+" px");
  JSONArray corners=new JSONArray();
  for(int[] p:new int[][]{{0,0},{width,0},{0,height},{width,height}}){
   double x=(double)p[0]/width,y=(double)p[1]/height;
   double longitude=Math.toDegrees(lon[0]+lon[1]*x+lon[2]*y);
   double latitude=Math.toDegrees(2*Math.atan(Math.exp(merc[0]+merc[1]*x+merc[2]*y))-Math.PI/2);
   if(!Double.isFinite(latitude)||!Double.isFinite(longitude)||Math.abs(latitude)>85||Math.abs(longitude)>180)throw new IOException("Coordenadas inválidas");
   corners.put(new JSONArray().put(latitude).put(longitude));
  }
  JSONObject meta=new JSONObject();meta.put("id",id+".png");meta.put("name",name);meta.put("corners",corners);meta.put("tiles",tiles);
  meta.put("originalWidth",width);meta.put("originalHeight",height);meta.put("previewWidth",width);meta.put("previewHeight",height);
  meta.put("rmsPixels",rms);meta.put("maxErrorPixels",maxPixelError);meta.put("validatedAffine",true);
  try(OutputStream out=new FileOutputStream(new File(charts,id+".kap.json"))){out.write(meta.toString().getBytes("UTF-8"));}
 }
 double[] solve3(double[][] matrix,double[] rhs)throws IOException{
  double[][] a=new double[3][4];
  for(int i=0;i<3;i++){System.arraycopy(matrix[i],0,a[i],0,3);a[i][3]=rhs[i];}
  for(int i=0;i<3;i++){
   int best=i;for(int j=i+1;j<3;j++)if(Math.abs(a[j][i])>Math.abs(a[best][i]))best=j;
   if(Math.abs(a[best][i])<1e-12)throw new IOException("Pontos REF colineares");
   double[] tmp=a[i];a[i]=a[best];a[best]=tmp;
   double pivot=a[i][i];for(int k=i;k<4;k++)a[i][k]/=pivot;
   for(int j=0;j<3;j++)if(j!=i){double factor=a[j][i];for(int k=i;k<4;k++)a[j][k]-=factor*a[i][k];}
  }
  return new double[]{a[0][3],a[1][3],a[2][3]};
 }
 boolean isGeo(String name){return name.toLowerCase(Locale.ROOT).matches(".*\\.(geojson|json)");}
 void saveGeo(InputStream in,String name)throws Exception{
  ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;
  while((n=in.read(buf))!=-1){if(out.size()+n>5_000_000)throw new IOException("GeoJSON maior que 5 MB");out.write(buf,0,n);}
  String value=out.toString("UTF-8");
  JSONObject json=new JSONObject(value);
  String type=json.optString("type");
  if(!type.equals("FeatureCollection")&&!type.equals("Feature")&&!type.equals("GeometryCollection")&&!type.equals("Point")&&!type.equals("LineString")&&!type.equals("Polygon")&&!type.equals("MultiPoint")&&!type.equals("MultiLineString")&&!type.equals("MultiPolygon"))throw new IOException("GeoJSON inválido: "+name);
  File dir=new File(getFilesDir(),"geo");dir.mkdirs();
  String id=UUID.randomUUID().toString().replace("-","")+".geojson";
  try(OutputStream file=new FileOutputStream(new File(dir,id))){file.write(out.toByteArray());}
  getPreferences(0).edit().putString(id,name).apply();
 }
 boolean isImage(String name){return name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|webp)");}
 void saveImage(InputStream in,String name)throws Exception{
  String ext=name.toLowerCase(Locale.ROOT).endsWith(".png")?".png":name.toLowerCase(Locale.ROOT).endsWith(".webp")?".webp":".jpg";
  String id=UUID.randomUUID().toString().replace("-","")+ext;
  File dest=new File(charts,id);long total=0;
  try(OutputStream out=new FileOutputStream(dest)){
   byte[] buf=new byte[16384];int n;
   while((n=in.read(buf))!=-1){total+=n;if(total>60_000_000)throw new IOException("Imagem maior que 60 MB");out.write(buf,0,n);}
  }catch(Exception e){dest.delete();throw e;}
  BitmapFactory.Options opt=new BitmapFactory.Options();opt.inJustDecodeBounds=true;BitmapFactory.decodeFile(dest.getPath(),opt);
  if(opt.outWidth<=0||opt.outHeight<=0){dest.delete();throw new IOException("Imagem inválida: "+name);}
  getPreferences(0).edit().putString(id,name).apply();
 }
}
