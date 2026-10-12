package com.ashraf.novelstudio

import android.webkit.WebView
import org.json.JSONArray

/** WTR-LAB writer: keep the site's .wtr-line DOM instead of rebuilding the chapter. */
internal class WtrLabWriter {
    fun write(webView: WebView, chapter: Chapter, translatedText: String, fontFile: String, fontSizePx: Int, pageKey: String, callback: (Boolean) -> Unit) {
        val paras = splitInternalMarkedTranslation(translatedText)
        val js = """
(function(paras,font,size,pageId){
  function text(e){return String((e&&e.innerText)||'').replace(/\u00a0/g,' ').trim();}
  function activeBody(){
    var tr=document.querySelector('.chapter-tracker.active[data-chapter-no]')||document.querySelector('.chapter-tracker.active');
    var no=tr&&tr.getAttribute('data-chapter-no');
    var m=location.pathname.match(/\/chapter-(\d+)\/?$/i); no=m?m[1]:no;
    var sels=[];
    if(no)sels.push('#chapter-'+no+' .chapter-body','[data-chapter-no="'+no+'"] .chapter-body');
    sels.push('.chapter-tracker.active .chapter-body','.chapter-body');
    for(var i=0;i<sels.length;i++){try{var e=document.querySelector(sels[i]);if(e&&text(e).length>80)return e;}catch(x){}}
    return null;
  }
  var body=activeBody(); if(!body)return 'no-body';
  var lines=Array.from(body.querySelectorAll('.wtr-line, [data-line]')).filter(function(e){return !e.querySelector('.wtr-line, [data-line]');});
  if(lines.length<paras.length)return 'line-mismatch:'+lines.length+':'+paras.length;
  var old=body.innerHTML;
  for(var i=0;i<paras.length;i++){
    var line=lines[i]; line.textContent=String(paras[i]||'');
    line.style.setProperty('font-size',(size||18)+'px','important');
    if(font)line.style.setProperty('font-family',font,'important');
    line.style.setProperty('line-height','1.75','important');
  }
  body.setAttribute('data-ns','1'); body.setAttribute('data-ns-page-id',pageId||'');
  window.__nsEl=body; window.__nsOrig=old; window.__nsShown=1; window.__nsPageId=pageId||'';
  return 'ok';
})(__PARAS__,__FONT__,__SIZE__,__PAGE_ID__)
        """.trimIndent()
            .replace("__PARAS__", JSONArray(paras).toString())
            .replace("__FONT__", org.json.JSONObject.quote(fontFile))
            .replace("__SIZE__", fontSizePx.toString())
            .replace("__PAGE_ID__", org.json.JSONObject.quote(pageKey))
        webView.evaluateJavascript(js) { result -> callback(result != null && result.contains("ok")) }
    }

    private fun splitInternalMarkedTranslation(text: String): List<String> {
        val marker = Regex("""(?m)^\[\d{3,}\]\s*""")
        val marked = marker.split(text).map { it.trim() }.filter { it.isNotEmpty() }
        if (marked.size > 1) return marked
        return text.split(Regex("""\n\s*\n|\n+""")).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
