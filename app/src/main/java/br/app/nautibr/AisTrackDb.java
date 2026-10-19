package br.app.nautibr;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.ContentValues;
import org.json.JSONObject;

/** Persistent AIS observations; one logical track per MMSI, multiple source observations. */
final class AisTrackDb extends SQLiteOpenHelper {
 AisTrackDb(Context c){super(c,"ais_tracks.db",null,1);}
 @Override public void onCreate(SQLiteDatabase db){
  db.execSQL("CREATE TABLE observations(id INTEGER PRIMARY KEY,mmsi INTEGER NOT NULL,source TEXT NOT NULL,received_ms INTEGER NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,sog REAL,cog REAL)");
  db.execSQL("CREATE INDEX idx_observations_mmsi_time ON observations(mmsi,received_ms)");
  db.execSQL("CREATE TABLE tracks(mmsi INTEGER PRIMARY KEY,source TEXT NOT NULL,received_ms INTEGER NOT NULL,lat REAL NOT NULL,lon REAL NOT NULL,sog REAL,cog REAL)");
 }
 @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){}
 synchronized void ingestStream(String raw){
  try{
   JSONObject d=new JSONObject(raw),message=d.optJSONObject("Message");
   if(message==null)return;
   JSONObject p=message.optJSONObject("PositionReport");
   if(p==null)p=message.optJSONObject("StandardClassBPositionReport");
   if(p==null)p=message.optJSONObject("ExtendedClassBPositionReport");
   if(p==null)return;
   JSONObject meta=d.optJSONObject("MetaData");
   long mmsi=meta!=null?meta.optLong("MMSI",0):0;
   if(mmsi<=0)mmsi=p.optLong("UserID",0);
   if(mmsi<=0)return;
   double lat=p.optDouble("Latitude",Double.NaN),lon=p.optDouble("Longitude",Double.NaN);
   if(!Double.isFinite(lat)||!Double.isFinite(lon)||Math.abs(lat)>90||Math.abs(lon)>180)return;
   long now=System.currentTimeMillis();
   ContentValues v=new ContentValues();
   v.put("mmsi",mmsi);v.put("source","aisstream");v.put("received_ms",now);
   v.put("lat",lat);v.put("lon",lon);
   if(p.has("Sog"))v.put("sog",p.optDouble("Sog"));
   if(p.has("Cog"))v.put("cog",p.optDouble("Cog"));
   SQLiteDatabase db=getWritableDatabase();
   db.beginTransaction();
   try{
    db.insertOrThrow("observations",null,v);
    db.insertWithOnConflict("tracks",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    db.setTransactionSuccessful();
   }finally{db.endTransaction();}
  }catch(Exception ignored){}
 }
}
