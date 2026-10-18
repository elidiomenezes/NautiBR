package br.app.nautibr;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.graphics.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class ChartImportService extends Service {
 static final String ACTION="br.app.nautibr.IMPORT";
 static final String PREF="chart_import";
 private File charts;
 private synchronized void diag(String message){try{File f=new File(getFilesDir(),"chart-import.log");try(FileWriter w=new FileWriter(f,true)){w.write(new java.text.SimpleDateFormat("HH:mm:ss.SSS",Locale.US).format(new Date())+" "+message+"\n");}}catch(Exception ignored){}}

 private volatile int done=0,total=0;
 private volatile String current="";
 private volatile int percent=0;
 private volatile boolean running=false;
 private final java.util.concurrent.atomic.AtomicInteger completed=new java.util.concurrent.atomic.AtomicInteger();
 private final java.util.concurrent.atomic.AtomicInteger imported=new java.util.concurrent.atomic.AtomicInteger();
 private final java.util.concurrent.atomic.AtomicInteger revision=new java.util.concurrent.atomic.AtomicInteger();
 private final java.util.concurrent.ConcurrentHashMap<String,Integer> progressByFile=new java.util.concurrent.ConcurrentHashMap<>();
 @Override public void onCreate(){super.onCreate();charts=new File(getFilesDir(),"charts");charts.mkdirs();
  NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
  nm.createNotificationChannel(new NotificationChannel("chart_import","Importação de cartas",NotificationManager.IMPORTANCE_LOW));
 }
 @Override public android.os.IBinder onBind(Intent intent){return null;}
 Notification notification(String message,int pct){
  return new Notification.Builder(this,"chart_import").setSmallIcon(android.R.drawable.stat_sys_download)
   .setContentTitle("NautiBR · Importação de cartas").setContentText(message)
   .setProgress(100,Math.max(0,Math.min(100,pct)),false).setOngoing(true).build();
 }
 synchronized void progress(String file,int pct){
  progressByFile.put(file,Math.max(0,Math.min(100,pct)));
  int sum=0;for(int p:progressByFile.values())sum+=p;
  int overall=total==0?0:Math.min(99,sum/total);
  String label=completed.get()+"/"+total+" concluídos · "+file;
  getSharedPreferences(PREF,MODE_PRIVATE).edit().putBoolean("running",true).putString("file",file)
   .putInt("percent",overall).putInt("done",completed.get()).putInt("total",total)
   .putInt("revision",revision.get()).putString("message",label).apply();
  ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(6102,notification(label,overall));
 }
 @Override public int onStartCommand(Intent intent,int flags,int startId){
  if(intent==null||!ACTION.equals(intent.getAction()))return START_NOT_STICKY;
  if(running)return START_NOT_STICKY;
  ArrayList<Uri> uris=intent.getParcelableArrayListExtra("uris");
  if(uris==null||uris.isEmpty())return START_NOT_STICKY;
  try{new FileOutputStream(new File(getFilesDir(),"chart-import.log")).close();}catch(Exception ignored){}
  diag("START files="+uris.size()+" build="+getPackageName());
  running=true;total=uris.size();completed.set(0);imported.set(0);revision.set(0);progressByFile.clear();
  startForeground(6102,notification("Iniciando",0));
  final int request=intent.getIntExtra("request",42);
  new Thread(()->{
   java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(Math.min(3,total));
   java.util.List<java.util.concurrent.Future<?>> tasks=new java.util.ArrayList<>();
   java.util.List<String> errors=java.util.Collections.synchronizedList(new java.util.ArrayList<>());
   for(Uri uri:uris)tasks.add(pool.submit(()->{
    String name="carta";
    try{
     try(android.database.Cursor c=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){
      if(c!=null&&c.moveToFirst())name=c.getString(0);
     }catch(Exception ignored){}
     diag("FILE start "+name);progress(name,0);
     String lower=name.toLowerCase(Locale.ROOT);
     int count=0;
     if(lower.endsWith(".zip"))count=importChartPackage(uri,name,request);
     else if(lower.endsWith(".tif")||lower.endsWith(".tiff"))errors.add(name+": GeoTIFF não suportado");
     else if(isKap(lower)){try(InputStream in=getContentResolver().openInputStream(uri)){saveKap(in,name);count++;}}
     else if(isBsb(lower)){try(InputStream in=getContentResolver().openInputStream(uri)){saveBsb(in,name);count++;}}
     else if(request==43&&isGeo(lower)){try(InputStream in=getContentResolver().openInputStream(uri)){saveGeo(in,name);count++;}}
     else if(request==42&&isImage(lower)){try(InputStream in=getContentResolver().openInputStream(uri)){saveImage(in,name);count++;}}
     else errors.add(name+": formato não suportado");
     imported.addAndGet(count);diag("FILE done "+name+" imported="+count);
    }catch(Exception e){errors.add(name+": "+e.getMessage());diag("ERROR "+name+" "+android.util.Log.getStackTraceString(e));}
    finally{
     revision.incrementAndGet();
     completed.incrementAndGet();
     progress(name,100);
    }
   }));
   for(java.util.concurrent.Future<?> task:tasks)try{task.get();}catch(Exception e){errors.add(e.toString());}
   pool.shutdown();
   diag("END imported="+imported.get()+" errors="+errors.size());
   String result=imported.get()+" arquivo(s) importado(s). "+String.join("; ",errors);
   getSharedPreferences(PREF,MODE_PRIVATE).edit().putBoolean("running",false).putInt("done",completed.get())
    .putInt("total",total).putInt("percent",100).putInt("revision",revision.get()).putString("message",result).apply();
   NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
   nm.notify(6102,new Notification.Builder(this,"chart_import").setSmallIcon(android.R.drawable.stat_sys_download_done)
    .setContentTitle("NautiBR · Importação concluída").setContentText(result).setAutoCancel(true).build());
   stopForeground(STOP_FOREGROUND_DETACH);running=false;stopSelf(startId);
  },"NautiBR-chart-import").start();
  return START_NOT_STICKY;
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
     digest.update(buf,0,n);output.write(buf,0,n);progress(packageName,Math.min(35,(int)(total/10_000_000L)));
    }
   }
   StringBuilder hash=new StringBuilder();
   for(byte b:digest.digest())hash.append(String.format(Locale.US,"%02x",b&255));
   String packageId=hash.toString();
   File manifest=new File(charts,packageId+".package.json");
   if(manifest.exists()){diag("SKIP duplicate ZIP sha256="+packageId);return 0;}
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
       output.write(buf,0,n);progress(packageName,40);
      }
     }
     if(isKap(key))kaps.put(key,file);
     else if(isBsb(key))bsbs.put(key,file);
     else geos.put(key,file);
    }
   }
   diag("ZIP entries="+entries+" kaps="+kaps.size()+" bsbs="+bsbs.size());
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
    progress(packageName,85);
    chartsInPackage.put(new JSONObject().put("file",e.getKey())
      .put("catalogs",referencedBy.containsKey(e.getKey())?referencedBy.get(e.getKey()):new JSONArray()));
    count++;
   }
   for(Map.Entry<String,File> e:geos.entrySet()){
    try(InputStream in=new FileInputStream(e.getValue())){saveGeo(in,packageName+" / "+e.getKey());}
    count++;
   }
   progress(packageName,95);
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
    if(y%64==0)progress(name,(int)(100.0*y/height));
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
   for(int i=0;i<3;i++){east[i]+=v[i]*mx;for(int j=0;j<3;j++)normal[i][j]+=v[i]*v[j];}
  }
  double[] lon=solve3(normal,east),merc=null;
  boolean ellipsoidal=false;
  double bestError=Double.POSITIVE_INFINITY;
  for(int mode=0;mode<2;mode++){
   double[] northCandidate=new double[3];
   for(double[] r:refs){
    double[] v={1,r[0]/width,r[1]/height};
    double my=mercatorY(r[2],mode==1);
    for(int i=0;i<3;i++)northCandidate[i]+=v[i]*my;
   }
   double[] candidate=solve3(normal,northCandidate);
   double sum=0;
   for(double[] r:refs){
    double[] v={1,r[0]/width,r[1]/height};
    double d=candidate[0]+candidate[1]*v[1]+candidate[2]*v[2]-mercatorY(r[2],mode==1);
    sum+=d*d;
   }
   if(sum<bestError){bestError=sum;merc=candidate;ellipsoidal=mode==1;}
  }
  double pixelError=0,maxPixelError=0;
  double determinant=lon[1]*merc[2]-lon[2]*merc[1];
  if(Math.abs(determinant)<1e-14)throw new IOException("Transformação REF degenerada");
  for(double[] r:refs){
   double[] v={1,r[0]/width,r[1]/height};
   double dx=lon[0]+lon[1]*v[1]+lon[2]*v[2]-Math.toRadians(r[3]);
   double dy=merc[0]+merc[1]*v[1]+merc[2]*v[2]-mercatorY(r[2],ellipsoidal);
   double px=(merc[2]*dx-lon[2]*dy)/determinant*width;
   double py=(-merc[1]*dx+lon[1]*dy)/determinant*height;
   double err=Math.hypot(px,py);pixelError+=err*err;maxPixelError=Math.max(maxPixelError,err);
  }
  double rms=Math.sqrt(pixelError/refs.size());
  boolean reducedGeoreferenceAccuracy=maxPixelError>3 || rms>1.5;
  JSONArray corners=new JSONArray();
  for(int[] p:new int[][]{{0,0},{width,0},{0,height},{width,height}}){
   double x=(double)p[0]/width,y=(double)p[1]/height;
   double longitude=Math.toDegrees(lon[0]+lon[1]*x+lon[2]*y);
   double latitude=inverseMercatorY(merc[0]+merc[1]*x+merc[2]*y,ellipsoidal);
   if(!Double.isFinite(latitude)||!Double.isFinite(longitude)||Math.abs(latitude)>85||Math.abs(longitude)>180)throw new IOException("Coordenadas inválidas");
   corners.put(new JSONArray().put(latitude).put(longitude));
  }
  diag("KAP metadata name="+name+" size="+width+"x"+height+" tiles="+tiles.length()+" rms="+rms+" maxError="+maxPixelError+" ellipsoidal="+ellipsoidal+" corners="+corners.toString());
  for(int ti=0;ti<tiles.length();ti++){JSONObject t=tiles.getJSONObject(ti);String tileId=t.optString("id");File tileFile=new File(charts,tileId);if(ti<5||!tileFile.isFile())diag("TILE "+ti+" name="+tileId+" exists="+tileFile.isFile()+" bytes="+(tileFile.isFile()?tileFile.length():0));}
  JSONObject meta=new JSONObject();meta.put("id",id+".png");meta.put("name",name);meta.put("corners",corners);meta.put("tiles",tiles);
  meta.put("originalWidth",width);meta.put("originalHeight",height);meta.put("previewWidth",width);meta.put("previewHeight",height);
  meta.put("rmsPixels",rms);meta.put("maxErrorPixels",maxPixelError);meta.put("validatedAffine",true);meta.put("ellipsoidalMercator",ellipsoidal);meta.put("mercatorLongitude",new JSONArray(lon));meta.put("mercatorNorthing",new JSONArray(merc));meta.put("reducedGeoreferenceAccuracy",reducedGeoreferenceAccuracy);
  // GDAL reprojection is mandatory. Propagate any failure to the importer.
  // Never save unwarped tiles as though the chart was imported successfully.
  diag("GDAL warp starting "+name);
  GdalChartReprojector.reproject(charts,meta);
  diag("GDAL warp succeeded "+name+" tiles="+meta.getJSONArray("tiles").length());
  for(int i=0;i<tiles.length();i++)new File(charts,tiles.getJSONObject(i).getString("id")).delete();
  try(OutputStream out=new FileOutputStream(new File(charts,id+".kap.json"))){out.write(meta.toString().getBytes("UTF-8"));}
 }
 static double mercatorY(double latitude,boolean ellipsoidal){
  double phi=Math.toRadians(latitude),y=Math.log(Math.tan(Math.PI/4+phi/2));
  if(ellipsoidal){double e=Math.sqrt(0.0066943799901413165),z=e*Math.sin(phi);y-=e/2*Math.log((1+z)/(1-z));}
  return y;
 }
 static double inverseMercatorY(double y,boolean ellipsoidal){
  double phi=2*Math.atan(Math.exp(y))-Math.PI/2;
  if(ellipsoidal)for(int i=0;i<12;i++){
   double e=Math.sqrt(0.0066943799901413165),z=e*Math.sin(phi);
   phi=2*Math.atan(Math.exp(y)*Math.pow((1+z)/(1-z),e/2))-Math.PI/2;
  }
  return Math.toDegrees(phi);
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
  getSharedPreferences("MainActivity",0).edit().putString(id,name).apply();
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
  getSharedPreferences("MainActivity",0).edit().putString(id,name).apply();
 }
}
