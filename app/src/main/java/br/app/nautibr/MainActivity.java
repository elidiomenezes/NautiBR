package br.app.nautibr;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import org.json.JSONObject;
import org.json.JSONArray;
import java.net.*;
import java.io.*;
import java.util.regex.*;
public class MainActivity extends Activity {
 WebView web;
 static final String SOURCE="https://www.marinha.mil.br/chm/dados-do-segnav/cartas-raster/";
 @Override public void onCreate(Bundle b){super.onCreate(b);web=new WebView(this);setContentView(web);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);web.addJavascriptInterface(new Bridge(),"Native");web.setWebViewClient(new WebViewClient());web.loadUrl("file:///android_asset/index.html");}
 void callback(String fn,String value){runOnUiThread(()->web.evaluateJavascript(fn+"("+JSONObject.quote(value)+")",null));}
 String fetch(String url)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(12000);c.setReadTimeout(20000);c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36");c.setRequestProperty("Accept","text/html,application/xhtml+xml,*/*");try(InputStream in=c.getInputStream()){return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}finally{c.disconnect();}}
 public class Bridge {
 @JavascriptInterface public void catalog(){new Thread(()->{try{String html=fetch(SOURCE);JSONArray a=new JSONArray();Matcher m=Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>").matcher(html);while(m.find()){String row=m.group(1);Matcher link=Pattern.compile("(?is)href=[\\\"']([^\\\"']+\\.zip[^\\\"']*)[\\\"']").matcher(row);if(!link.find())continue;String href=link.group(1).replace("&amp;","&");String clean=row.replaceAll("(?is)<[^>]*>"," ").replaceAll("\\s+"," ").trim();JSONObject o=new JSONObject();o.put("name",clean);o.put("url",new URL(new URL(SOURCE),href).toString());a.put(o);}callback("onCatalog",a.toString());}catch(Exception e){callback("onStatus",e.toString());}}).start();}
 @JavascriptInterface public void download(String url){new Thread(()->{try{URL u=new URL(url);if(!"https".equals(u.getProtocol())||!"www.marinha.mil.br".equals(u.getHost()))throw new Exception("Origem nao autorizada");String name=u.getPath().substring(u.getPath().lastIndexOf('/')+1);if(!name.matches("[a-zA-Z0-9_-]+\\.zip"))throw new Exception("Nome invalido");File dir=new File(getFilesDir(),"charts");dir.mkdirs();HttpURLConnection c=(HttpURLConnection)u.openConnection();c.setConnectTimeout(12000);c.setReadTimeout(60000);try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(new File(dir,name))){byte[] buf=new byte[16384];int n;long total=0;while((n=in.read(buf))!=-1){total+=n;if(total>100000000)throw new IOException("Arquivo grande");out.write(buf,0,n);}}finally{c.disconnect();}callback("onStatus",name+" baixado");}catch(Exception e){callback("onStatus",e.toString());}}).start();}
 @JavascriptInterface public void marine(double lat,double lon){new Thread(()->{try{callback("onMarine",fetch("https://marine-api.open-meteo.com/v1/marine?latitude="+lat+"&longitude="+lon+"&current=wave_height,wave_direction,ocean_current_velocity,ocean_current_direction,sea_level_height_msl"));}catch(Exception e){callback("onStatus",e.toString());}}).start();}
 @JavascriptInterface public void saveKey(String key){getPreferences(0).edit().putString("ais",key).apply();}
 @JavascriptInterface public String getKey(){return getPreferences(0).getString("ais","");}
 }
}