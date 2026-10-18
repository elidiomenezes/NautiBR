package br.app.nautibr;

import java.io.File;
import java.io.IOException;
import org.gdal.gdal.Dataset;
import org.gdal.gdal.WarpOptions;
import org.gdal.gdal.gdal;
import java.util.Vector;

/**
 * GDAL native bridge. The Android AAR bundles JNI libraries for arm64-v8a and x86_64.
 * Only call from a worker thread. The current AAR does NOT ship the BSB/KAP driver:
 * input must first be converted to a georeferenced GDAL-readable dataset.
 */
final class GdalBridge {
 private static boolean initialized;
 static synchronized void init() throws IOException {
  if(initialized)return;
  try {
   gdal.AllRegister();
   if(gdal.GetDriverByName("GTiff")==null)throw new IOException("GDAL GeoTIFF driver unavailable");
   initialized=true;
  }catch(LinkageError e){throw new IOException("GDAL native libraries unavailable",e);}
 }
 static String version() throws IOException {init();return gdal.VersionInfo("--version");}

 /** Warp a georeferenced GeoTIFF to Web Mercator without discarding resolution. */
 static void warpGeoTiff(File source,File destination) throws IOException {
  init();
  if(!source.isFile())throw new IOException("Input not found: "+source);
  if(destination.exists())throw new IOException("Output already exists: "+destination);
  Dataset src=null,dst=null;
  try {
   src=gdal.Open(source.getAbsolutePath());
   if(src==null)throw new IOException("GDAL cannot open input: "+gdal.GetLastErrorMsg());
   Vector<String> args=new Vector<>();
   args.add("-t_srs");args.add("EPSG:3857");
   args.add("-of");args.add("GTiff");
   args.add("-r");args.add("near");
   args.add("-et");args.add("0");
   args.add("-co");args.add("TILED=YES");
   args.add("-co");args.add("COMPRESS=DEFLATE");
   WarpOptions opts=new WarpOptions(args);
   dst=gdal.Warp(destination.getAbsolutePath(),new Dataset[]{src},opts);
   if(dst==null)throw new IOException("GDAL warp failed: "+gdal.GetLastErrorMsg());
   dst.FlushCache();
  }catch(RuntimeException e){destination.delete();throw new IOException("GDAL warp failed",e);}
  finally {if(dst!=null)dst.delete();if(src!=null)src.delete();}
 }
}
