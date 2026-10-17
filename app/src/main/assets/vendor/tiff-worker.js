/* GeoTIFF decoder runs outside Android WebView's UI thread. */
importScripts('/vendor/geotiff.js');
let cancelled=false;
self.onmessage=async function(event){
 if(event.data.type==='cancel'){cancelled=true;return;}
 if(event.data.type!=='decode')return;
 cancelled=false;
 try{
  const tiff=await GeoTIFF.fromArrayBuffer(event.data.buffer);
  const image=await tiff.getImage();
  const width=image.getWidth(),height=image.getHeight();
  const keys=image.getGeoKeys(),fd=image.fileDirectory;
  self.postMessage({type:'metadata',width,height,keys,
   scale:fd.ModelPixelScale||null,tie:fd.ModelTiepoint||null,
   transformation:fd.ModelTransformation||null});
  const tileSize=512,cols=Math.ceil(width/tileSize),rows=Math.ceil(height/tileSize),total=cols*rows;
  if(total>2500)throw Error('Carta com blocos demais ('+total+'). É necessário suporte a níveis de detalhe.');
  let done=0;
  for(let ty=0;ty<rows;ty++)for(let tx=0;tx<cols;tx++){
   if(cancelled){self.postMessage({type:'cancelled'});return;}
   const x=tx*tileSize,y=ty*tileSize,w=Math.min(tileSize,width-x),h=Math.min(tileSize,height-y);
   const rgb=await image.readRGB({window:[x,y,x+w,y+h],interleave:true});
   const rgba=new Uint8ClampedArray(w*h*4);
   for(let i=0,j=0;i<rgb.length&&j<rgba.length;i+=3,j+=4){
    rgba[j]=rgb[i];rgba[j+1]=rgb[i+1];rgba[j+2]=rgb[i+2];rgba[j+3]=255;
   }
   self.postMessage({type:'tile',x,y,w,h,rgba:rgba.buffer,done:++done,total},[rgba.buffer]);
  }
  self.postMessage({type:'complete',total});
 }catch(e){self.postMessage({type:'error',message:String(e&&e.message||e)})}
};