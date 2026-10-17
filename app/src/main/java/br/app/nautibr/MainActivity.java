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

public class MainActivity extends Activity {
 private static final int PICK=42;
 private static final String CATALOG="https://www.marinha.mil.br/chm/dados-do-segnav/cartas-raster";
 private WebView web;
 private File charts;
 @Override public void onCreate(Bundle state){
  super.onCreate(state);
  charts=new File(getFilesDir(),"charts");charts.mkdirs();
  web=new WebView(this);setContentView(web);
  web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
  web.addJavascriptInterface(new Bridge(),"Native");
  web.setWebViewClient(new WebViewClient(){
   @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest req){
    Uri uri=req.getUrl();
    if(!"nautibr.local".equals(uri.getHost()) || !"/chart".equals(uri.getPath()))return super.shouldInterceptRequest(view,req);
    try{
     String id=uri.getQueryParameter("id");
     if(id==null||!id.matches("[0-9a-f]{32}\\.(png|jpg|jpeg|webp)"))return null;
     File file=new File(charts,id);
     String type=id.endsWith(".png")?"image/png":id.endsWith(".webp")?"image/webp":"image/jpeg";
     return new WebResourceResponse(type,null,new FileInputStream(file));
    }catch(Exception e){return null;}
   }
  });
  web.loadUrl("file:///android_asset/index.html");
 }
 void callback(String fn,String value){runOnUiThread(()->web.evaluateJavascript(fn+"("+JSONObject.quote(value)+")",null));}
 void status(String s){callback("onStatus",s);}
 public class Bridge{
  @JavascriptInterface public void importCharts(){runOnUiThread(()->{
   Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);
   i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);startActivityForResult(i,PICK);
  });}
  @JavascriptInterface public void listCharts(){new Thread(()->{try{
   JSONArray a=new JSONArray();
   File[] files=charts.listFiles();if(files!=null)for(File file:files)if(file.isFile()){
    JSONObject o=new JSONObject();o.put("id",file.getName());o.put("name",getPreferences(0).getString(file.getName(),file.getName()));a.put(o);
   }callback("onCharts",a.toString());
  }catch(Exception e){status(e.toString());}}).start();}
  @JavascriptInterface public void openNavy(){runOnUiThread(()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(CATALOG))));}
 }
 @Override protected void onActivityResult(int req,int result,Intent data){
  super.onActivityResult(req,result,data);
  if(req!=PICK||result!=RESULT_OK||data==null)return;
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
       if(isImage(leaf)){saveImage(zip,name+" / "+leaf);count++;}
      }
     }
    }else if(isImage(lower)){try(InputStream in=getContentResolver().openInputStream(uri)){saveImage(in,name);count++;}}
    else errors.add(name+": formato não suportado");
   }catch(Exception e){errors.add(e.getMessage());}
   status(count+" imagem(ns) importada(s). "+String.join("; ",errors));
   new Bridge().listCharts();
  }).start();
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
