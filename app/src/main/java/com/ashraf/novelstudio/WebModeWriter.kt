package com.ashraf.novelstudio

import org.json.JSONArray
import org.json.JSONObject

/**
 * Shows a translation on an IMAGE chapter. There is no text element to replace on such pages, so the
 * translation is drawn on top of the page. Two ways, both plugged into the existing
 * "View original / View translation" pill (Js.TOGGLE): the toggle empties/refills the wrapper, and the
 * wrapper lets touches through while empty, so the original images become usable again.
 *
 *  - [bubbleScript]: each translated paragraph goes back onto the spot where its original text was
 *    (same position, rotation and text size, over the cleaned bubble). The page stays scrollable.
 *  - [script]: fallback reader panel, used when the translation cannot be matched to the bubbles.
 */
internal object WebModeWriter {
    private const val BODY = """
(function(paras,title,font,size,pid){
  try{
    var old=document.getElementById('__ns_wm_overlay'); if(old&&old.parentNode)old.parentNode.removeChild(old);
    var w=document.createElement('div'); w.id='__ns_wm_overlay';
    w.style.cssText='position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483647;pointer-events:none;';
    var box=document.createElement('div');
    box.style.cssText='position:absolute;left:0;top:0;right:0;bottom:0;overflow-y:auto;-webkit-overflow-scrolling:touch;'
      +'pointer-events:auto;background:#101218;color:#e8eaf0;padding:18px 16px 90px;box-sizing:border-box;line-height:1.8;'
      +'font-size:'+size+'px;'+(font?"font-family:'NSBn',sans-serif;":'font-family:sans-serif;');
    if(title){var h=document.createElement('div');h.textContent=title;
      h.style.cssText='font-weight:700;font-size:'+(size+3)+'px;margin:0 0 16px;color:#fff;';box.appendChild(h);}
    for(var i=0;i<paras.length;i++){var t=(paras[i]||'').trim();if(!t)continue;
      var p=document.createElement('p');p.textContent=t;p.style.cssText='margin:0 0 '+Math.round(size*0.9)+'px;';box.appendChild(p);}
    w.appendChild(box);
    (document.body||document.documentElement).appendChild(w);
    window.__nsEl=w; window.__nsOrig=''; window.__nsTr=w.innerHTML; window.__nsShown=1;
    w.setAttribute('data-ns','1'); w.setAttribute('data-ns-page-id',pid||''); window.__nsPageId=pid||'';
    return 'ok';
  }catch(e){return 'err:'+e;}
})(__PARAS__,__TITLE__,__FONT__,__SIZE__,__PID__)
"""

    fun script(paras: List<String>, title: String, font: String, sizePx: Int, pageId: String): String =
        BODY.replace("__PARAS__", JSONArray(paras).toString())
            .replace("__TITLE__", JSONObject.quote(title))
            .replace("__FONT__", JSONObject.quote(font))
            .replace("__SIZE__", sizePx.toString())
            .replace("__PID__", JSONObject.quote(pageId))

    private const val BUBBLES = """
(function(items,font,pid){
  try{
    if(window.__nsWmStop){try{window.__nsWmStop();}catch(e){}}
    var old=document.getElementById('__ns_wm_overlay'); if(old&&old.parentNode)old.parentNode.removeChild(old);
    var w=document.createElement('div'); w.id='__ns_wm_overlay';
    w.style.cssText='position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483647;pointer-events:none;overflow:hidden;';
    var fam=font?"'NSBn',sans-serif":'sans-serif';
    for(var i=0;i<items.length;i++){
      var it=items[i];
      var b=document.createElement('div'); b.className='__ns_b';
      b.setAttribute('data-s',it.s);
      b.setAttribute('data-g',[it.cx,it.cy,it.w,it.h,it.a,it.f].join(','));
      b.style.cssText='position:absolute;left:0;top:0;display:none;box-sizing:border-box;overflow:hidden;'
        +'align-items:center;justify-content:center;padding:2px;border-radius:6px;pointer-events:none;'
        +'background:'+it.bg+';color:'+it.fg+';font-family:'+fam+';font-weight:600;';
      var t=document.createElement('div'); t.textContent=it.t;
      t.style.cssText='width:100%;text-align:center;line-height:1.3;overflow-wrap:break-word;';
      b.appendChild(t); w.appendChild(b);
    }
    var LAZY=['data-src','data-original','data-lazy-src','data-lazy','data-url','data-echo','data-actualsrc','data-orig-src','data-pc-src'];
    var cache={},miss={};
    function norm(u){return (u||'').split('#')[0];}
    function srcsOf(im){
      var o=[],c=im.currentSrc||im.src; if(c)o.push(norm(c));
      for(var a=0;a<LAZY.length;a++){var v=im.getAttribute(LAZY[a]);
        if(v&&v.length>4){try{o.push(norm(new URL(v,document.baseURI).href));}catch(e){}}}
      var ss=im.getAttribute('data-srcset')||im.getAttribute('srcset');
      if(ss){try{o.push(norm(new URL(ss.split(',')[0].trim().split(/\s+/)[0],document.baseURI).href));}catch(e){}}
      return o;}
    function findImg(s){
      var c=cache[s]; if(c&&document.contains(c))return c;
      var now=Date.now(); if(miss[s]&&now-miss[s]<1500)return null;
      var imgs=document.images;
      for(var i=0;i<imgs.length;i++){var l=srcsOf(imgs[i]);
        for(var k=0;k<l.length;k++){if(l[k]===s){cache[s]=imgs[i];return imgs[i];}}}
      miss[s]=now; return null;}
    // Largest font (up to maxPx) at which the text still fits inside its box: never overflows, and a
    // short translation simply stays at the original size, centred.
    function fit(b,maxPx){
      var t=b.firstChild,lo=5,hi=Math.max(6,maxPx),best=5;
      for(var n=0;n<9;n++){
        var mid=(lo+hi)/2; t.style.fontSize=mid+'px';
        if(t.offsetHeight<=b.clientHeight-4&&t.scrollWidth<=t.clientWidth+1){best=mid;lo=mid;}else{hi=mid;}
      }
      t.style.fontSize=best+'px';}
    function layout(){
      if(!document.contains(w)){stop();return;}
      var bs=w.querySelectorAll('.__ns_b'),vh=window.innerHeight;
      for(var i=0;i<bs.length;i++){
        var b=bs[i],im=findImg(b.getAttribute('data-s'));
        if(!im){b.style.display='none';continue;}
        var r=im.getBoundingClientRect();
        if(r.width<8||r.bottom<-80||r.top>vh+80){b.style.display='none';continue;}
        var g=b.getAttribute('data-g').split(',');
        var bw=g[2]*r.width,bh=g[3]*r.height,cx=r.left+g[0]*r.width,cy=r.top+g[1]*r.height;
        if(cy+bh/2<-20||cy-bh/2>vh+20){b.style.display='none';continue;}
        b.style.width=bw+'px'; b.style.height=bh+'px';
        b.style.transform='translate('+(cx-bw/2)+'px,'+(cy-bh/2)+'px)'+((g[4]*1)?' rotate('+g[4]+'deg)':'');
        if(b.style.display!=='flex')b.style.display='flex';
        var fw=String(Math.round(r.width));
        if(b.getAttribute('data-fw')!==fw){b.setAttribute('data-fw',fw);fit(b,g[5]*r.width);}
      }}
    var raf=0;
    function sched(){if(raf)return;raf=requestAnimationFrame(function(){raf=0;try{layout();}catch(e){}});}
    var iv=setInterval(sched,500);
    function stop(){window.removeEventListener('scroll',sched,true);window.removeEventListener('resize',sched);clearInterval(iv);}
    window.addEventListener('scroll',sched,true); window.addEventListener('resize',sched);
    window.__nsWmStop=stop;
    (document.body||document.documentElement).appendChild(w);
    window.__nsEl=w; window.__nsOrig=''; window.__nsTr=w.innerHTML; window.__nsShown=1;
    w.setAttribute('data-ns','1'); w.setAttribute('data-ns-page-id',pid||''); window.__nsPageId=pid||'';
    layout();
    return 'ok';
  }catch(e){return 'err:'+e;}
})(__ITEMS__,__FONT__,__PID__)
"""

    private fun css(c: Int) = String.format("#%06X", c and 0xFFFFFF)
    private fun r5(x: Float): Double = Math.round(x * 100000.0) / 100000.0

    /**
     * Draws each translated paragraph on the spot of its original text. [translated] is index-aligned
     * with [WebModeLayout.bubbles]; a null/blank entry leaves that bubble's original text untouched.
     */
    fun bubbleScript(layout: WebModeLayout, translated: List<String?>, font: String, pageId: String): String {
        val arr = JSONArray()
        for ((i, b) in layout.bubbles.withIndex()) {
            val t = translated.getOrNull(i)?.trim().orEmpty()
            if (t.isEmpty()) continue
            arr.put(JSONObject()
                .put("t", t).put("s", WebModeLogic.normalizeUrl(b.src))
                .put("cx", r5(b.cx)).put("cy", r5(b.cy)).put("w", r5(b.w)).put("h", r5(b.h))
                .put("a", r5(b.angle)).put("f", r5(b.font))
                .put("bg", css(b.bg)).put("fg", css(b.fg)))
        }
        // The translated text goes in last, so nothing in it can be mistaken for a placeholder.
        return BUBBLES
            .replace("__FONT__", JSONObject.quote(font))
            .replace("__PID__", JSONObject.quote(pageId))
            .replace("__ITEMS__", arr.toString())
    }
}
