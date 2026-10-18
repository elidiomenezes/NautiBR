package br.app.nautibr;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.gdal.gdal.Band;
import org.gdal.gdal.Dataset;
import org.gdal.gdal.Driver;
import org.gdal.gdal.gdal;
import org.gdal.gdalconst.gdalconstConstants;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** KAP PNG strips -> GDAL GeoTIFF -> exact Web Mercator warp -> PNG strips. */
final class GdalChartReprojector {
 private static final double R=6378137.0;
 private static final int TILE_W=1024,TILE_H=512;
 private static void close(Dataset d){if(d!=null)d.delete();}
 static void reproject(File directory,JSONObject meta)throws Exception{
  GdalBridge.init();
  int w=meta.getInt("originalWidth"),h=meta.getInt("originalHeight");
  JSONArray sourceTiles=meta.getJSONArray("tiles");
  double[] lon=coefficients(meta.getJSONArray("mercatorLongitude"));
  double[] north=coefficients(meta.getJSONArray("mercatorNorthing"));
  boolean ellipsoidal=meta.optBoolean("ellipsoidalMercator");
  File source=File.createTempFile("kap-source-", ".tif",directory);
  File output=new File(directory,source.getName()+".warped.tif");
  Dataset input=null,warped=null;
  List<File> produced=new ArrayList<>();
  try {
   Driver driver=gdal.GetDriverByName("GTiff");
   if(driver==null)throw new IOException("GDAL GTiff driver missing");
   input=driver.Create(source.getAbsolutePath(),w,h,3,gdalconstConstants.GDT_Byte);
   if(input==null)throw new IOException("Cannot create temporary GeoTIFF");
   // Original normalized coefficients are in radians and pixel fractions.
   input.SetGeoTransform(new double[]{
    R*lon[0],R*lon[1]/w,R*lon[2]/h,
    R*north[0],R*north[1]/w,R*north[2]/h
   });
   // EPSG:3395 = WGS84 ellipsoidal Mercator; EPSG:3857 = spherical.
   input.SetProjection(ellipsoidal?
    "PROJCS[\"WGS 84 / World Mercator\",GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563]],PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]],PROJECTION[\"Mercator_1SP\"],PARAMETER[\"central_meridian\",0],PARAMETER[\"scale_factor\",1],PARAMETER[\"false_easting\",0],PARAMETER[\"false_northing\",0],UNIT[\"metre\",1]]":
    "PROJCS[\"WGS 84 / Pseudo-Mercator\",GEOGCS[\"WGS 84\",DATUM[\"WGS_1984\",SPHEROID[\"WGS 84\",6378137,298.257223563]],PRIMEM[\"Greenwich\",0],UNIT[\"degree\",0.0174532925199433]],PROJECTION[\"Mercator_1SP\"],PARAMETER[\"central_meridian\",0],PARAMETER[\"scale_factor\",1],PARAMETER[\"false_easting\",0],PARAMETER[\"false_northing\",0],UNIT[\"metre\",1]]");
   Band red=input.GetRasterBand(1),green=input.GetRasterBand(2),blue=input.GetRasterBand(3);
   int[] pixels=new int[w];
   byte[] rr=new byte[w],gg=new byte[w],bb=new byte[w];
   for(int i=0;i<sourceTiles.length();i++){
    JSONObject tile=sourceTiles.getJSONObject(i);
    File f=new File(directory,tile.getString("id"));
    Bitmap bmp=BitmapFactory.decodeFile(f.getAbsolutePath());
    if(bmp==null)throw new IOException("Cannot decode KAP strip "+f);
    int x=tile.getInt("x"),y=tile.getInt("y"),tw=tile.getInt("w"),th=tile.getInt("h");
    byte[] r=new byte[tw],g=new byte[tw],b=new byte[tw];
    try{
     for(int row=0;row<th;row++){
      bmp.getPixels(pixels,0,tw,0,row,tw,1);
      for(int col=0;col<tw;col++){
       int p=pixels[col];r[col]=(byte)(p>>16);g[col]=(byte)(p>>8);b[col]=(byte)p;
      }
      red.WriteRaster(x,y+row,tw,1,r);
      green.WriteRaster(x,y+row,tw,1,g);
      blue.WriteRaster(x,y+row,tw,1,b);
     }
    }finally{bmp.recycle();}
   }
   input.FlushCache();close(input);input=null;
   GdalBridge.warpGeoTiff(source,output);
   warped=gdal.Open(output.getAbsolutePath());
   if(warped==null)throw new IOException("Cannot reopen warped chart");
   int outW=warped.getRasterXSize(),outH=warped.getRasterYSize();
   if(outW<=0||outH<=0||outW>50000||outH>50000)throw new IOException("Invalid warped dimensions");
   double[] gt=warped.GetGeoTransform();
   if(gt==null||gt.length<6||Math.abs(gt[2])+Math.abs(gt[4])>1e-8)
    throw new IOException("Warped raster not north-up");
   String id=java.util.UUID.randomUUID().toString().replace("-","");
   JSONArray result=new JSONArray();
   Band r=warped.GetRasterBand(1),g=warped.GetRasterBand(2),b=warped.GetRasterBand(3);
   for(int y=0;y<outH;y+=TILE_H)for(int x=0;x<outW;x+=TILE_W){
    int tw=Math.min(TILE_W,outW-x),th=Math.min(TILE_H,outH-y);
    Bitmap bitmap=Bitmap.createBitmap(tw,th,Bitmap.Config.ARGB_8888);
    byte[] cr=new byte[tw],cg=new byte[tw],cb=new byte[tw];
    int[] argb=new int[tw];
    try{
     for(int row=0;row<th;row++){
      r.ReadRaster(x,y+row,tw,1,cr);g.ReadRaster(x,y+row,tw,1,cg);b.ReadRaster(x,y+row,tw,1,cb);
      for(int col=0;col<tw;col++)argb[col]=0xff000000|((cr[col]&255)<<16)|((cg[col]&255)<<8)|(cb[col]&255);
      bitmap.setPixels(argb,0,tw,0,row,tw,1);
     }
     String name=id+"_"+(x/TILE_W)+"_"+(y/TILE_H)+".png";
     File dest=new File(directory,name);
     try(FileOutputStream stream=new FileOutputStream(dest)){
      if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,stream))throw new IOException("PNG encode failed");
     }
     produced.add(dest);
     result.put(new JSONObject().put("id",name).put("x",x).put("y",y).put("w",tw).put("h",th));
    }finally{bitmap.recycle();}
   }
   JSONArray corners=new JSONArray();
   for(int[] p:new int[][]{{0,0},{outW,0},{0,outH},{outW,outH}}){
    double mx=gt[0]+p[0]*gt[1]+p[1]*gt[2],my=gt[3]+p[0]*gt[4]+p[1]*gt[5];
    double lat=Math.toDegrees(2*Math.atan(Math.exp(my/R))-Math.PI/2);
    corners.put(new JSONArray().put(lat).put(Math.toDegrees(mx/R)));
   }
   // Only switch metadata after all output tiles have been written.
   meta.put("tiles",result);meta.put("corners",corners);
   meta.put("originalWidth",outW);meta.put("originalHeight",outH);
   meta.put("previewWidth",outW);meta.put("previewHeight",outH);
   meta.put("ellipsoidalMercator",false);
   meta.remove("mercatorLongitude");meta.remove("mercatorNorthing");
   meta.put("gdalReprojected",true);
  }catch(Exception e){
   for(File f:produced)f.delete();
   throw e;
  }finally{close(warped);close(input);source.delete();output.delete();}
 }
 private static double[] coefficients(JSONArray a)throws Exception{
  return new double[]{a.getDouble(0),a.getDouble(1),a.getDouble(2)};
 }
}
