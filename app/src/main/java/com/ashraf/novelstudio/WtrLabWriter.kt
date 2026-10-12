package com.ashraf.novelstudio

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/** WTR-LAB writer: replace the active chapter body as one unit so original
 * English lines cannot remain below/after the Bengali translation. */
internal class WtrLabWriter {
    fun write(
        webView: WebView,
        chapter: Chapter,
        translatedText: String,
        fontFile: String,
        fontSizePx: Int,
        pageKey: String,
        callback: (Boolean) -> Unit
    ) {
        val paras = splitInternalMarkedTranslation(translatedText)
        if (paras.isEmpty()) {
            callback(false)
            return
        }

        val js = """
(function(paras,font,size,pageId){
  function text(e){return String((e&&e.innerText)||e&&e.textContent||'').replace(/\u00a0/g,' ').trim();}
  function activeBody(){
    var tr=document.querySelector('.chapter-tracker.active[data-chapter-no]')||document.querySelector('.chapter-tracker.active');
    var no=tr&&tr.getAttribute('data-chapter-no');
    var sels=[];
    if(no)sels.push('#chapter-'+no+' .chapter-body',
      '#tracker-'+no+' .chapter-body',
      '.chapter-tracker[data-chapter-no="'+no+'"] .chapter-body',
      '[data-chapter-no="'+no+'"] .chapter-body');
    sels.push('#chapter-content','.chapter-tracker.active .chapter-body','.chapter-body','.chapter-content');
    for(var i=0;i<sels.length;i++){
      try{
        var e=document.querySelector(sels[i]);
        if(e&&text(e).length>=40)return e;
      }catch(x){}
    }
    return null;
  }

  var body=activeBody();
  if(!body)return 'no-body';

  // Keep the site's original DOM for the View original button.
  var old=body.innerHTML;
  var html='';
  for(var i=0;i<paras.length;i++){
    var p=String(paras[i]||'').trim();
    if(!p)continue;
    html+='<p class="ns-wtr-translation">'+
      p.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/\n/g,'<br>')+
      '</p>';
  }
  if(!html)return 'empty';

  // Stop an older observer before replacing the body.
  try{ if(window.__nsWtrObserver) window.__nsWtrObserver.disconnect(); }catch(e){}

  body.innerHTML=html;
  body.setAttribute('data-ns','1');
  body.setAttribute('data-ns-page-id',pageId||'');
  body.setAttribute('data-ns-wtr-translation','1');

  var style=document.getElementById('__ns_wtr_style');
  if(!style){
    style=document.createElement('style');
    style.id='__ns_wtr_style';
    (document.head||document.documentElement).appendChild(style);
  }
  style.textContent=
    '.ns-wtr-translation{margin:0 0 1em 0 !important;font-size:'+(size||18)+'px !important;line-height:1.75 !important;word-break:normal !important;overflow-wrap:break-word !important;font-family:NSBn,sans-serif !important;}';

  window.__nsEl=body;
  window.__nsOrig=old;
  window.__nsTr=body.innerHTML;
  window.__nsShown=1;
  window.__nsPageId=pageId||'';

  // WTR-LAB is an SPA and can re-render #chapter-content after our write.
  // If it does, immediately restore the translation instead of allowing the
  // original English DOM to appear underneath it.
  var applying=false;
  window.__nsWtrObserver=new MutationObserver(function(){
    if(applying||!window.__nsShown||!document.contains(body))return;
    if(body.getAttribute('data-ns')!=='1')return;
    var current=body.innerHTML;
    if(current===window.__nsTr)return;
    applying=true;
    try{
      body.innerHTML=window.__nsTr;
      body.setAttribute('data-ns','1');
      body.setAttribute('data-ns-page-id',pageId||'');
    }finally{
      applying=false;
    }
  });
  try{
    window.__nsWtrObserver.observe(body,{childList:true,subtree:true});
  }catch(e){}

  // Wait for the injected NSBn font before forcing the family on the new nodes.
  try{
    if(document.fonts&&document.fonts.load){
      document.fonts.load((size||18)+'px NSBn').then(function(){
        if(window.__nsShown&&document.contains(body)){
          body.querySelectorAll('.ns-wtr-translation').forEach(function(e){
            e.style.setProperty('font-family','NSBn,sans-serif','important');
          });
        }
      });
    }
  }catch(e){}

  return 'ok';
})(__PARAS__,__FONT__,__SIZE__,__PAGE_ID__)
""".trimIndent()
            .replace("__PARAS__", JSONArray(paras).toString())
            .replace("__FONT__", JSONObject.quote(fontFile))
            .replace("__SIZE__", fontSizePx.toString())
            .replace("__PAGE_ID__", JSONObject.quote(pageKey))

        webView.evaluateJavascript(js) { result ->
            callback(result != null && result.contains("ok"))
        }
    }

    private fun splitInternalMarkedTranslation(text: String): List<String> {
        val marker = Regex("""(?m)^\[\d{3,}\]\s*""")
        val marked = marker.split(text).map { it.trim() }.filter { it.isNotEmpty() }
        if (marked.size > 1) return marked
        return text.split(Regex("""\n\s*\n|\n+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
