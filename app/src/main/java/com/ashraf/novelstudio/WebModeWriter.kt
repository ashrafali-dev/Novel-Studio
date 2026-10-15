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
    var de=document.scrollingElement||document.documentElement;
    // ABS: the page itself scrolls, so the bubbles live INSIDE the page (position:absolute) and the
    // browser scrolls them together with the images on its own thread: no lag, no shaking.
    // FIXED (only when an inner element scrolls): bubbles follow the images from script.
    var ABS=(de.scrollHeight||0)>(window.innerHeight||0)+80;
    var w=document.createElement('div'); w.id='__ns_wm_overlay';
    w.style.cssText=ABS
      ?'position:absolute;left:0;top:0;width:0;height:0;overflow:visible;z-index:2147483647;pointer-events:none;'
      :'position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483647;pointer-events:none;overflow:hidden;';
    var fam=font?"'NSBn',sans-serif":'sans-serif';
    for(var i=0;i<items.length;i++){
      var it=items[i];
      var b=document.createElement('div'); b.className='__ns_b';
      b.setAttribute('data-s',it.s);
      b.setAttribute('data-g',[it.cx,it.cy,it.w,it.h,it.a,it.f].join(','));
      b.style.cssText='position:absolute;left:0;top:0;display:none;box-sizing:border-box;overflow:hidden;'
        +'align-items:center;justify-content:center;padding:3px;pointer-events:none;will-change:transform;'
        +'background:'+it.bg+';color:'+it.fg+';font-family:'+fam+';font-weight:600;'
        +(it.sf?'border-radius:12px;box-shadow:0 0 7px 5px '+it.bg+';':'border-radius:4px;box-shadow:0 0 0 3px '+it.bg+';');
      var t=document.createElement('div'); t.textContent=it.t;
      // Preserve Bengali grapheme clusters/ conjuncts and use a shaping-friendly text layout.
      // OCR bubble bounds can be slightly smaller than the speech bubble, so fit() below
      // measures both axes and shrinks the text instead of letting it spill outside.
      t.style.cssText='display:block;box-sizing:border-box;width:100%;max-width:100%;min-width:0;'
        +'text-align:center;line-height:1.5;white-space:normal;word-break:keep-all;text-wrap:balance;'
        // Bengali vowel signs (ি ী ু ৃ ে ৈ) stick out above and below the line box; room for them
        // stops them being clipped by the box edge or by the gradient's background-clip.
        +'padding:0.22em 0.1em;'
        +'overflow-wrap:normal;hyphens:none;font-kerning:normal;'
        // Keep browser defaults for complex-script shaping (including Bengali conjuncts).
        +'font-variant-ligatures:normal;text-rendering:optimizeLegibility;';
      b.style.setProperty('font-family',fam,'important'); t.style.setProperty('font-family',fam,'important');
      if(it.g2&&it.g2!==it.fg){
        // the original lettering was a gradient: keep it
        t.style.background='linear-gradient(to bottom,'+it.fg+','+it.g2+')';
        t.style.webkitBackgroundClip='text'; t.style.backgroundClip='text';
        t.style.webkitTextFillColor='transparent'; t.style.color='transparent';
      }
      b.appendChild(t); w.appendChild(b);
    }
    var LAZY=['data-src','data-original','data-lazy-src','data-lazy','data-url','data-echo','data-actualsrc','data-orig-src','data-pc-src'];
    var cache={},miss={};
    function norm(u){return (u||'').split('#')[0];}
    function base(u){var x=norm(u),q=x.indexOf('?');return q>0?x.slice(0,q):x;}
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
      var bs0=base(s);
      for(var i2=0;i2<imgs.length;i2++){var l2=srcsOf(imgs[i2]);
        for(var k2=0;k2<l2.length;k2++){if(base(l2[k2])===bs0){cache[s]=imgs[i2];return imgs[i2];}}}
      miss[s]=now; return null;}
    // Largest font (up to maxPx) at which the text still fits inside its box.
    function fit(b,maxPx){
      var t=b.firstChild,lo=3.5,hi=Math.max(4,maxPx),best=3.5;
      // Binary-search the largest font size that fits horizontally AND vertically.
      // Checking scrollHeight as well as width prevents multi-line Bengali text from
      // escaping the speech-bubble overlay when the translated sentence is longer.
      for(var n=0;n<12;n++){
        var mid=(lo+hi)/2; t.style.fontSize=mid+'px';
        var fits=t.scrollWidth<=t.clientWidth+1 &&
          t.scrollHeight<=b.clientHeight-4 &&
          t.getBoundingClientRect().height<=b.clientHeight-2;
        if(fits){best=mid;lo=mid;}else{hi=mid;}
      }
      t.style.fontSize=best+'px';
      t.style.overflow='visible';
    }
    var MARGIN=1400;
    function layout(){
      if(!document.contains(w)){
        // Another overlay replaced this one -> stop. Otherwise the page (a re-render, a lazy-load swap)
        // threw our overlay away: put it back instead of silently giving up.
        if(window.__nsEl!==w){stop();return;}
        if(window.__nsShown){try{(document.body||document.documentElement).appendChild(w);}catch(e){}}
        if(!document.contains(w))return;
      }
      var bs=w.querySelectorAll('.__ns_b'),vh=window.innerHeight;
      var o=ABS?w.getBoundingClientRect():null;
      for(var i=0;i<bs.length;i++){
        var b=bs[i],im=findImg(b.getAttribute('data-s'));
        if(!im){if(b.style.display!=='none')b.style.display='none';continue;}
        var r=im.getBoundingClientRect();
        if(r.width<8){if(b.style.display!=='none')b.style.display='none';continue;}
        var g=b.getAttribute('data-g').split(',');
        var bw=g[2]*r.width,bh=g[3]*r.height;
        var vx=r.left+g[0]*r.width,vy=r.top+g[1]*r.height;       // centre in the viewport
        var near=!(vy+bh/2<-MARGIN||vy-bh/2>vh+MARGIN);
        if(!near){if(b.style.display!=='none')b.style.display='none';continue;}
        var px=ABS?(vx-o.left):vx,py=ABS?(vy-o.top):vy;          // centre in the overlay's own space
        var key=Math.round(px)+','+Math.round(py)+','+Math.round(bw)+','+Math.round(bh);
        if(b.getAttribute('data-k')!==key){
          b.setAttribute('data-k',key);
          b.style.width=bw+'px'; b.style.height=bh+'px';
          b.style.transform='translate3d('+(px-bw/2)+'px,'+(py-bh/2)+'px,0)'+((g[4]*1)?' rotate('+g[4]+'deg)':'');
        }
        if(b.style.display!=='flex')b.style.display='flex';
        var fw=String(Math.round(r.width));
        if(b.getAttribute('data-fw')!==fw){b.setAttribute('data-fw',fw);fit(b,g[5]*r.width*1.2);}
      }}
    var raf=0,lastRun=0;
    function sched(){
      if(raf)return;
      // In ABS mode scrolling needs no work at all except showing bubbles that come near: 4x a second is plenty.
      raf=requestAnimationFrame(function(){raf=0;
        var n=Date.now(); if(ABS&&n-lastRun<250)return; lastRun=n;
        try{layout();}catch(e){}});}
    var iv=setInterval(function(){lastRun=0;sched();},500);
    function onScroll(){ if(ABS)sched(); else {if(raf)return;raf=requestAnimationFrame(function(){raf=0;try{layout();}catch(e){}});} }
    function stop(){window.removeEventListener('scroll',onScroll,true);window.removeEventListener('resize',sched);clearInterval(iv);}
    window.addEventListener('scroll',onScroll,{capture:true,passive:true}); window.addEventListener('resize',sched);
    window.__nsWmStop=stop;
    (document.body||document.documentElement).appendChild(w);
    window.__nsEl=w; window.__nsOrig=''; window.__nsTr=w.innerHTML; window.__nsShown=1;
    w.setAttribute('data-ns','1'); w.setAttribute('data-ns-page-id',pid||''); window.__nsPageId=pid||'';
    lastRun=0; layout();

    // Long-press a translated bubble -> small "Re-translate" button (Kotlin bridge: NsRetr).
    try{
      if(!window.__nsLp){
        window.__nsLp=1;
        var lpT=0,lpX=0,lpY=0,lpMenu=null,lpSwallow=0;
        var lpHide=function(){if(lpMenu&&lpMenu.parentNode)lpMenu.parentNode.removeChild(lpMenu);lpMenu=null;};
        var lpHit=function(x,y){
          var ov=document.getElementById('__ns_wm_overlay'); if(!ov)return null;
          var bs=ov.querySelectorAll('.__ns_b'),best=null;
          for(var i=0;i<bs.length;i++){var bb=bs[i]; if(bb.style.display==='none')continue;
            var r=bb.getBoundingClientRect();
            if(x>=r.left&&x<=r.right&&y>=r.top&&y<=r.bottom)best=bb;}
          return best;};
        var lpShow=function(bb,x,y){
          lpHide();
          var g=bb.getAttribute('data-g').split(','),s=bb.getAttribute('data-s');
          var m=document.createElement('div'); m.id='__ns_lp_menu';
          m.textContent='\uD83D\uDD01 Re-translate';
          m.style.cssText='position:fixed;z-index:2147483647;pointer-events:auto;padding:11px 18px;border-radius:12px;'
            +'font:600 15px sans-serif;color:#fff;background:rgba(36,40,58,.97);border:1px solid rgba(255,255,255,.2);'
            +'box-shadow:0 4px 18px rgba(0,0,0,.5);-webkit-user-select:none;user-select:none;white-space:nowrap;';
          var vw=window.innerWidth||360;
          m.style.left=Math.max(8,Math.min(vw-190,x-80))+'px';
          m.style.top=Math.max(8,y-70)+'px';
          var shownAt=Date.now();
          m.addEventListener('click',function(ev){
            ev.preventDefault();ev.stopPropagation();
            if(Date.now()-shownAt<350)return;
            lpHide();
            try{window.NsRetr.retranslate(s,parseFloat(g[0]),parseFloat(g[1]),parseFloat(g[2]),parseFloat(g[3]));}catch(e){}
          });
          (document.body||document.documentElement).appendChild(m); lpMenu=m;
          setTimeout(function(){if(lpMenu===m)lpHide();},5000);
        };
        document.addEventListener('touchstart',function(e){
          if(lpMenu&&!lpMenu.contains(e.target))lpHide();
          clearTimeout(lpT);
          if(!e.touches||e.touches.length!==1)return;
          var t=e.touches[0]; lpX=t.clientX; lpY=t.clientY;
          lpT=setTimeout(function(){var bb=lpHit(lpX,lpY); if(!bb)return; lpSwallow=Date.now(); lpShow(bb,lpX,lpY);},600);
        },{capture:true,passive:true});
        document.addEventListener('touchmove',function(e){
          var t=e.touches&&e.touches[0]; if(!t)return;
          if(Math.abs(t.clientX-lpX)>10||Math.abs(t.clientY-lpY)>10)clearTimeout(lpT);
        },{capture:true,passive:true});
        document.addEventListener('touchend',function(){clearTimeout(lpT);},{capture:true,passive:true});
        document.addEventListener('touchcancel',function(){clearTimeout(lpT);},{capture:true,passive:true});
        document.addEventListener('contextmenu',function(e){
          if(Date.now()-lpSwallow<1500){e.preventDefault();e.stopPropagation();}
        },true);
      }
    }catch(e){}
    // First layout may measure a fallback font while the bundled Bengali font is still loading.
    // Re-fit every bubble after font readiness so conjuncts and long Bengali words stay intact.
    try{
      if(font && document.fonts && document.fonts.ready){
        document.fonts.ready.then(function(){
          if(!document.contains(w))return;
          var bs=w.querySelectorAll('.__ns_b');
          for(var fi=0;fi<bs.length;fi++)bs[fi].removeAttribute('data-fw');
          lastRun=0;layout();
        });
      }
    }catch(e){}
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
            val t = WmSfx.fix(translated.getOrNull(i)?.trim().orEmpty())
            if (t.isEmpty()) continue
            arr.put(JSONObject()
                .put("t", t).put("s", WebModeLogic.normalizeUrl(b.src))
                .put("cx", r5(b.cx)).put("cy", r5(b.cy)).put("w", r5(b.w)).put("h", r5(b.h))
                .put("a", r5(b.angle)).put("f", r5(b.font))
                .put("bg", css(b.bg)).put("fg", css(b.fg)).put("g2", css(b.fg2)).put("sf", b.soft))
        }
        // The translated text goes in last, so nothing in it can be mistaken for a placeholder.
        return BUBBLES
            .replace("__FONT__", JSONObject.quote(font))
            .replace("__PID__", JSONObject.quote(pageId))
            .replace("__ITEMS__", arr.toString())
    }
}

/**
 * Safety net for sound effects the chatbot handed back in English letters (BOOM, HAH ...): the
 * common ones are replaced by their Bengali form. Anything unknown is kept exactly as received.
 */
internal object WmSfx {
    private val MAP = mapOf(
        "BOOM" to "ধুম!", "BANG" to "দুম!", "CRASH" to "ঝনঝন!", "SMASH" to "ধ্বংস!", "THUD" to "ধপ!",
        "THUMP" to "ধুপ!", "BAM" to "ধাম!", "POW" to "পাউ!", "WHAM" to "ধড়াম!", "SLAM" to "দড়াম!",
        "CLANG" to "ঠং!", "CLANK" to "ঠকাস!", "CLICK" to "ক্লিক!", "SLASH" to "ঝপ!", "SWISH" to "সাঁই!",
        "SWOOSH" to "শাঁ!", "WHOOSH" to "সোঁ!", "ZAP" to "ঝাপ!", "CRACK" to "মড়াৎ!", "SNAP" to "ঠাস!",
        "SPLASH" to "ছপাৎ!", "DRIP" to "টপ!", "RUMBLE" to "গুড়গুড়!", "ROAR" to "গর্জন!", "GRR" to "গরররর!",
        "GRRR" to "গরররর!", "HAH" to "হাঃ", "HAA" to "হাআ", "HAAH" to "হাঃ", "HUFF" to "হাঁস!",
        "PANT" to "হাঁপ", "GASP" to "হাঁ!", "GULP" to "গিল!", "GRUNT" to "গোঙানি", "SIGH" to "আহ্...",
        "SHHH" to "শশশ!", "HMM" to "হুম...", "HM" to "হুম", "UGH" to "উফ!", "AGH" to "আঃ!", "AAH" to "আহ!",
        "AAAH" to "আআআ!", "AAAAH" to "আআআআ!", "KYAA" to "কিয়াআ!", "EEK" to "ইশ!", "TCH" to "চ!",
        "TSK" to "চ!", "PHEW" to "ফুঃ", "GRAB" to "খপ!", "STAB" to "খচ!", "THUNK" to "ঠক!", "KNOCK" to "ঠকঠক",
        "FLASH" to "ঝলক!", "SPARK" to "স্ফুলিঙ্গ!", "RIP" to "ছিঁড়!", "TEAR" to "ফড়াত!", "THWACK" to "চটাস!",
        "WHAP" to "চটাস!", "SLAP" to "চড়!", "PUNCH" to "ঘুষি!", "KICK" to "লাথি!", "THROB" to "ধকধক",
        "BADUM" to "ধুকধুক", "TICK" to "টিক", "TOCK" to "টক", "BEEP" to "বিপ!", "BUZZ" to "ভনভন"
    )

    private fun letters(s: String) = s.filter { it.isLetter() }

    fun fix(t: String): String {
        if (t.isEmpty()) return t
        if (t == "~" || t == "-" || t == "—" || t == "…") return ""     // chatbot's "this was noise" answer: leave the picture alone
        val l = letters(t)
        if (l.isEmpty() || l.any { it.code > 0x24F }) return t          // already (partly) non-Latin
        val key = l.uppercase().replace(Regex("(.)\\1{2,}"), "$1$1")
        return MAP[key] ?: MAP[l.uppercase()] ?: t
    }
}
