package com.ashraf.novelstudio

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

internal class QidianWriter {
    fun write(webView: WebView, chapter: Chapter, translatedText: String, fontFile: String, fontSizePx: Int, pageKey: String, callback: (Boolean) -> Unit) {
        val paragraphs = split(translatedText).map { it.replace(Regex("\\s+\\[\\d{1,3}\\]$"), "").trim() }
        val script = """
(function(paras,font,size,pageId){
  var m=location.pathname.match(/\/chapter\/(\d+)\/(\d+)/);
  if(!m)return 'not-chapter';
  var id=m[2], root=document.getElementById('c-'+id);
  if(!root)return 'no-root';
  var wrap=root.closest('.print')||root.parentElement;
  if(!wrap)return 'no-wrap';
  var title=wrap.querySelector('h1.title');
  var lines=Array.from(root.querySelectorAll(':scope > p .content-text'));
  var offset=paras.length===lines.length+1?1:0;
  if(paras.length-offset!==lines.length)return 'line-mismatch:'+paras.length+':'+lines.length;
  function setTextKeepMarker(el,value){
    if(!el)return;
    var review=el.querySelector('.review');
    Array.from(el.childNodes).forEach(function(n){if(n!==review && n.nodeType===3)n.remove();});
    if(review)el.insertBefore(document.createTextNode(value+' '),review);
    else el.textContent=value;
  }
  if(offset===1 && title)setTextKeepMarker(title,paras[0]||'');
  lines.forEach(function(el,i){
    el.textContent=String(paras[i+offset]||'');
    el.style.setProperty('font-size',(size||18)+'px','important');
    el.style.setProperty('line-height','1.8','important');
    if(font)el.style.setProperty('font-family','NSBn','important');
  });
  if(title && offset===1){
    title.style.setProperty('font-size',(size||18)+'px','important');
    if(font)title.style.setProperty('font-family','NSBn','important');
  }
  root.setAttribute('data-ns','1');
  root.setAttribute('data-ns-page-id',pageId||'');
  window.__nsEl=root; window.__nsOrig=null; window.__nsShown=1; window.__nsPageId=pageId||'';
  return 'ok';
})(__PARAS__,__FONT__,__SIZE__,__PAGE_ID__)
        """.trimIndent()
            .replace("__PARAS__", JSONArray(paragraphs).toString())
            .replace("__FONT__", JSONObject.quote(fontFile))
            .replace("__SIZE__", fontSizePx.toString())
            .replace("__PAGE_ID__", JSONObject.quote(pageKey))
        webView.evaluateJavascript(script) { raw -> callback(raw != null && raw.contains("ok")) }
    }

    private fun split(text: String): List<String> {
        val marker = Regex("\\[\\d{3,}\\]\\s*")
        val parts = if (marker.containsMatchIn(text)) marker.split(text) else text.split(Regex("\\n\\s*\\n"))
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
