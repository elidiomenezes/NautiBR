package br.app.nautibr;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
 private WebView web;
 private static final String CATALOG="https://www.marinha.mil.br/chm/dados-do-segnav/cartas-raster";
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);
  web=new WebView(this);setContentView(web);
  web.getSettings().setJavaScriptEnabled(true);
  web.getSettings().setDomStorageEnabled(true);
  web.addJavascriptInterface(new Bridge(),"Native");
  web.setWebViewClient(new WebViewClient());
  web.loadUrl("file:///android_asset/index.html");
 }
 void callback(String fn,String value){runOnUiThread(()->web.evaluateJavascript(fn+"("+JSONObject.quote(value)+")",null));}
 public class Bridge {
  @JavascriptInterface public void openCatalog(String number,String title) {
   runOnUiThread(()->{
    Uri.Builder b=Uri.parse(CATALOG).buildUpon();
    if(number!=null&&!number.trim().isEmpty())b.appendQueryParameter("field_numero_raster_value",number.trim());
    if(title!=null&&!title.trim().isEmpty())b.appendQueryParameter("title",title.trim());
    startActivity(new Intent(Intent.ACTION_VIEW,b.build()).addCategory(Intent.CATEGORY_BROWSABLE));
   });
  }
  @JavascriptInterface public void marine(double lat,double lon) {
   new Thread(()->{try{
    URL url=new URL("https://marine-api.open-meteo.com/v1/marine?latitude="+lat+"&longitude="+lon+"&current=wave_height,wave_direction,ocean_current_velocity,ocean_current_direction,sea_level_height_msl");
    HttpURLConnection c=(HttpURLConnection)url.openConnection();c.setConnectTimeout(12000);c.setReadTimeout(20000);
    try(InputStream in=c.getInputStream()){callback("onMarine",new String(in.readAllBytes(),StandardCharsets.UTF_8));}finally{c.disconnect();}
   }catch(Exception e){callback("onStatus",e.toString());}}).start();
  }
  @JavascriptInterface public void saveKey(String key){getPreferences(0).edit().putString("ais",key).apply();}
  @JavascriptInterface public String getKey(){return getPreferences(0).getString("ais","");}
 }
}
