package com.ashraf.novelstudio

import org.json.JSONObject

object WebtoonJs {
    private val PRELUDE = """
(function(){
  function ensureRoot(){
    var r=document.getElementById('__ns_wt_overlay_root');
    if(!r){
      r=document.createElement('div');
      r.id='__ns_wt_overlay_root';
      r.style.cssText='position:absolute;left:0;top:0;width:0;height:0;z-index:2147483000;pointer-events:none;';
      (document.body||document.documentElement).appendChild(r);
    }
    if(!window.__nsWtReposition){
      window.__nsWtReposition=function(){
        var root=document.getElementById('__ns_wt_overlay_root');
        if(!root)return;
        var nodes=root.querySelectorAll('[data-wt-image]');
        var cache={};
        for(var i=0;i<nodes.length;i++){
          var n=nodes[i], id=n.getAttribute('data-wt-image');
          var img=cache[id];
          if(!img){
            try{img=document.querySelector('img[data-ns-wt-id="'+id+'"]');}catch(e){img=null;}
            cache[id]=img;
          }
          if(!img)continue;
          var r=img.getBoundingClientRect();
          var nw=Number(n.getAttribute('data-nw'))||1;
          var nh=Number(n.getAttribute('data-nh'))||1;
          var x=Number(n.getAttribute('data-x'))||0;
          var y=Number(n.getAttribute('data-y'))||0;
          var w=Number(n.getAttribute('data-w'))||0;
          var h=Number(n.getAttribute('data-h'))||0;
          n.style.left=(r.left+window.scrollX+(x/nw)*r.width)+'px';
          n.style.top=(r.top+window.scrollY+(y/nh)*r.height)+'px';
          n.style.width=((w/nw)*r.width)+'px';
          n.style.height=((h/nh)*r.height)+'px';
        }
      };
      window.addEventListener('scroll',function(){requestAnimationFrame(window.__nsWtReposition)},true);
      window.addEventListener('resize',function(){requestAnimationFrame(window.__nsWtReposition)},false);
      if(window.visualViewport){
        window.visualViewport.addEventListener('resize',function(){requestAnimationFrame(window.__nsWtReposition)});
        window.visualViewport.addEventListener('scroll',function(){requestAnimationFrame(window.__nsWtReposition)});
      }
    }
    return r;
  }
  window.__nsWtEnsureRoot=ensureRoot;
})()
""".trimIndent()

    fun install(): String = PRELUDE + ";window.__nsWtEnsureRoot();'ok'"

    fun scan(): String = """
(function(){
  function hash(s){
    var h=2166136261;
    for(var i=0;i<s.length;i++){h^=s.charCodeAt(i);h+=(h<<1)+(h<<4)+(h<<7)+(h<<8)+(h<<24);}
    return (h>>>0).toString(16);
  }
  function attr(img,n){return (img.getAttribute(n)||'').trim();}
  function usable(u){
    if(!u)return false;
    var x=u.toLowerCase();
    return !(x.indexOf('data:image/gif')===0 || x.indexOf('data:image/svg')===0 || x.indexOf('transparent')>=0);
  }
  function bestSrc(img){
    var lazy=[
      attr(img,'data-src'),attr(img,'data-original'),attr(img,'data-lazy-src'),
      attr(img,'data-url'),attr(img,'data-image'),attr(img,'data-original-src')
    ];
    for(var i=0;i<lazy.length;i++)if(usable(lazy[i]))return lazy[i];
    var cur=img.currentSrc||img.src||'';
    if(usable(cur))return cur;
    var ss=attr(img,'srcset');
    if(ss){
      var parts=ss.split(',');
      for(var j=parts.length-1;j>=0;j--){
        var u=(parts[j].trim().split(/\\s+/)[0]||'');
        if(usable(u))return u;
      }
    }
    return '';
  }
  var sy=window.scrollY||0, vh=window.innerHeight||800, topV=sy, botV=sy+vh;
  var imgs=[].slice.call(document.images), out=[];
  for(var i=0;i<imgs.length;i++){
    var img=imgs[i], r=img.getBoundingClientRect();
    if(!r.width||!r.height)continue;
    var src=bestSrc(img);
    if(!src)continue;
    var nw=img.naturalWidth||0, nh=img.naturalHeight||0;
    var cssW=Math.round(r.width), cssH=Math.round(r.height);
    var estW=nw||cssW, estH=nh||cssH;
    if((estW<160&&estH<160)||(r.width<70&&r.height<70))continue;
    if(r.width*r.height<5000)continue;
    var id='wt_'+hash(src+'|'+estW+'x'+estH+'|'+i);
    img.setAttribute('data-ns-wt-id',id);
    var docTop=r.top+sy, docLeft=r.left+(window.scrollX||0);
    var visible=(r.bottom>0&&r.top<vh);
    var dist=docTop>botV?docTop-botV:(docTop+r.height<topV?topV-(docTop+r.height):0);
    out.push({id:id,src:src,top:docTop,left:docLeft,width:r.width,height:r.height,
      nw:nw,nh:nh,visible:visible,distance:dist});
  }
  out.sort(function(a,b){return a.top-b.top||a.left-b.left});
  var anchor=-1;
  for(var k=0;k<out.length;k++){if(out[k].visible||out[k].top>=topV){anchor=k;break;}}
  if(anchor<0)anchor=Math.max(0,out.length-1);
  var from=Math.max(0,anchor-2),to=Math.min(out.length,anchor+9);
  return JSON.stringify(out.slice(from,to));
})()
""".trimIndent()

    fun requestImageData(id: String): String {
        val safe = JSONObject.quote(id)
        return """
(function(id){
  try{
    var img=document.querySelector('img[data-ns-wt-id='+id+']');
    if(!img)return 'missing';
    var src=img.currentSrc||img.src||'';
    if(src.indexOf('data:image/')===0){
      if(window.NSWT&&NSWT.imageDataReady)NSWT.imageDataReady(id,src);
      return 'sent';
    }
    if(src.indexOf('blob:')===0){
      fetch(src).then(function(r){return r.blob();}).then(function(blob){
        var fr=new FileReader();
        fr.onload=function(){if(window.NSWT&&NSWT.imageDataReady)NSWT.imageDataReady(id,fr.result||'');};
        fr.readAsDataURL(blob);
      }).catch(function(e){if(window.NSWT&&NSWT.imageDataReady)NSWT.imageDataReady(id,'');});
      return 'pending';
    }
    return src;
  }catch(e){return 'error';}
})( $safe )
""".replace("$" + "safe", safe)
    }

    fun render(imageId: String, overlaysJson: String): String = """
(function(id,items){
  var root=window.__nsWtEnsureRoot?window.__nsWtEnsureRoot():document.getElementById('__ns_wt_overlay_root');
  if(!root)return 'noroot';
  var old=root.querySelectorAll('[data-wt-group="'+id+'"]');
  for(var i=0;i<old.length;i++)old[i].remove();

  var imgs=[];
  try{imgs=[].slice.call(document.querySelectorAll('img[data-ns-wt-id="'+id+'"]'));}catch(e){}
  if(!imgs.length)return 'noimg';

  for(var j=0;j<imgs.length;j++){
    var img=imgs[j], nw=img.naturalWidth||1, nh=img.naturalHeight||1;
    for(var q=0;q<items.length;q++){
      var x=items[q], n=document.createElement('div');
      n.setAttribute('data-wt-group',id);
      n.setAttribute('data-wt-image',id);
      n.setAttribute('data-nw',nw);
      n.setAttribute('data-nh',nh);
      n.setAttribute('data-x',x.x); n.setAttribute('data-y',x.y);
      n.setAttribute('data-w',x.width); n.setAttribute('data-h',x.height);
      n.style.cssText=[
        'position:absolute','box-sizing:border-box','display:flex',
        'align-items:center','justify-content:center','overflow:hidden',
        'padding:5px 7px','border-radius:11px','font-family:system-ui,sans-serif',
        'font-weight:600','text-align:center','line-height:1.14',
        'white-space:pre-wrap','overflow-wrap:anywhere','word-break:normal',
        'pointer-events:none','border:1px solid rgba(0,0,0,.10)',
        'box-shadow:0 1px 4px rgba(0,0,0,.16)',
        'background:'+((x.background)||'#f4f4f4'),
        'color:'+((x.foreground)||'#111111')
      ].join(';');
      var len=(x.text||'').length;
      var fs=Math.max(11,Math.min(30,(Number(x.height)||30)*0.27));
      if(len>55)fs*=0.86;
      if(len>100)fs*=0.76;
      n.style.fontSize=fs+'px';
      n.textContent=x.text||'';
      root.appendChild(n);
    }
  }
  window.__nsWtReposition&&window.__nsWtReposition();
  return 'ok';
})(__NS_ID__,__NS_ITEMS__)
"""
        .replace("__NS_ID__", JSONObject.quote(imageId))
        .replace("__NS_ITEMS__", overlaysJson)

    fun clear(): String =
        "(function(){var r=document.getElementById('__ns_wt_overlay_root');if(r)r.remove();return 'ok';})()"
}
