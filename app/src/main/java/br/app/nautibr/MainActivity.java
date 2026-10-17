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

public class MainActivity extends Activity {
 private static final int PICK=42;
 private static final String CATALOG="https://www.marinha.mil.br/chm/dados-do-segnav/cartas-raster";
 private WebView web;
 private File charts;
 private volatile boolean aisRunning=false;
 private Thread aisThread;
 private java.net.Socket aisSocket;
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
     String type=id.endsWith(".png")?"image/png":id.endsWith(".webp")?"image/webp":(id.endsWith(".tif")||id.endsWith(".tiff"))?"image/tiff":"image/jpeg";
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
 @Override protected void onDestroy(){stopAisConnection();super.onDestroy();}
 void callback(String fn,String value){runOnUiThread(()->web.evaluateJavascript(fn+"("+JSONObject.quote(value)+")",null));}
 void status(String s){callback("onStatus",s);}
 public class Bridge{
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
  @JavascriptInterface public void copyText(String value){runOnUiThread(()->{ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("NautiBR GeoTIFF",value));});}
  @JavascriptInterface public void importCharts(){runOnUiThread(()->{
   Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);
   i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(i,PICK);
  });}
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
     try(ZipInputStream zip=new ZipInputStream(getContentResolver().openInputStream(uri))){
      ZipEntry entry;int entries=0;
      while((entry=zip.getNextEntry())!=null){
       if(++entries>100)throw new IOException("ZIP com entradas demais");
       if(entry.isDirectory())continue;
       String leaf=new File(entry.getName().replace('\\','/')).getName();
       if(req==43 && isGeo(leaf)){saveGeo(zip,name+" / "+leaf);count++;}
       else if(isKap(leaf)){saveKap(zip,name+" / "+leaf);count++;}

       else if(req==PICK && isImage(leaf)){saveImage(zip,name+" / "+leaf);count++;}
      }
     }
    }else if(isTiff(lower)){try(InputStream in=getContentResolver().openInputStream(uri)){saveTiff(in,name);count++;}}
    else if(isKap(lower)){try(InputStream in=getContentResolver().openInputStream(uri)){saveKap(in,name);count++;}}
    else if(req==43 && isGeo(lower)){try(InputStream in=getContentResolver().openInputStream(uri)){saveGeo(in,name);count++;}}
    else if(req==PICK && isImage(lower)){try(InputStream in=getContentResolver().openInputStream(uri)){saveImage(in,name);count++;}}
    else errors.add(name+": formato não suportado");
   }catch(Exception e){errors.add(e.getMessage());}
   status(count+" imagem(ns) importada(s). "+String.join("; ",errors));

  }).start();
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
