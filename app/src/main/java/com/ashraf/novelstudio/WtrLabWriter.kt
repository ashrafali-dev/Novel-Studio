package com.ashraf.novelstudio

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/** WTR-LAB writer: replace translated text inside the original block elements.
 * Inline styling is intentionally discarded; block placement stays accurate. */
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
        val source = sourceSegments(chapter.text)
        if (paras.isEmpty() || source.isEmpty()) {
            callback(false)
            return
        }

        val js = """
(function(paras,source,font,size,pageId){
  function norm(s){return String(s||'').replace(/\u00a0/g,' ').replace(/\s+/g,' ').trim();}
  function visible(e){
    if(!e)return false;
    var r=e.getBoundingClientRect();
    return r.width>0 && r.height>0;
  }
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
        if(e&&norm(e.innerText||e.textContent).length>=40)return e;
      }catch(x){}
    }
    return null;
  }

  var el=activeBody();
  if(!el)return 'no-body';

  var old=el.innerHTML;
  var tags='p,li,blockquote,pre,h1,h2,h3,h4,h5,h6,div,section,article';
  var all=[].slice.call(el.querySelectorAll(tags));
  var used=new Set(), pairs=[];

  function leaf(e){
    if(!e||!visible(e))return false;
    var t=norm(e.innerText||e.textContent);
    if(!t)return false;
    if(/^(DIV|SECTION|ARTICLE)$/.test(e.tagName) &&
       e.querySelector('p,li,blockquote,pre,h1,h2,h3,h4,h5,h6'))return false;
    return true;
  }

  function exact(src){
    var s=norm(src),best=null;
    for(var i=0;i<all.length;i++){
      var e=all[i];
      if(used.has(e)||!leaf(e))continue;
      if(norm(e.innerText||e.textContent)===s){
        var pri=/^H[1-6]$/.test(e.tagName)?5:(e.tagName==='P'?4:(e.tagName==='LI'?3:1));
        var score=pri*100000-Math.abs((e.innerText||'').length-s.length);
        if(!best||score>best.score)best={el:e,score:score};
      }
    }
    return best&&best.el;
  }

  for(var i=0;i<source.length && i<paras.length;i++){
    var target=exact(source[i]);
    if(target){
      used.add(target);
      pairs.push({el:target,val:paras[i]});
    }
  }

  // If WTR flattened whitespace/spans, align remaining units by DOM order.
  var remaining=all.filter(function(e){return !used.has(e)&&leaf(e)&&!/^H[1-6]$/.test(e.tagName);});
  for(var j=0;j<paras.length;j++){
    if(pairs.some(function(p){return p.val===paras[j];}))continue;
    var t=remaining.shift();
    if(!t)break;
    used.add(t);
    pairs.push({el:t,val:paras[j]});
  }

  // Never partially rewrite a chapter. A bad alignment is worse than no write.
  if(pairs.length<Math.max(1,Math.floor(paras.length*0.75)))return 'map-mismatch:'+pairs.length+':'+paras.length;

  function textNodes(root){
    var out=[],w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,null);
    var n;
    while((n=w.nextNode())){
      if(!n.nodeValue||!n.nodeValue.trim())continue;
      var p=n.parentElement;
      if(!p||/^(SCRIPT|STYLE|NOSCRIPT)$/.test(p.tagName))continue;
      out.push(n);
    }
    return out;
  }

  function cutAtSpace(s,pos){
    if(pos<=0||pos>=s.length)return pos;
    var a=s.lastIndexOf(' ',pos),b=s.indexOf(' ',pos);
    if(a<1)return b>0?b:pos;
    if(b<0)return a;
    return (pos-a<=b-pos)?a:b;
  }

  // Deliberately replace only the text of each original block.
  // Keep the original block element (<p>/<li>/blockquote/etc.) in place, but
  // do NOT preserve inline formatting. This gives stable, accurate paragraph
  // placement without inventing wrong bold/italic formatting in Bengali.
  function writeBlock(el,val){
    if(!el)return false;
    el.textContent=String(val||'').trim();
    return true;
  }

  var fam='';
  if(font&&font.length){
    try{
      var st=document.getElementById('__ns_font');
      if(!st){st=document.createElement('style');st.id='__ns_font';(document.head||document.documentElement).appendChild(st);}
      st.textContent="@font-face{font-family:'NSBn';src:url('"+location.origin+"/__ns_font/"+font+".ttf') format('truetype');font-display:swap;}";
      fam="'NSBn',sans-serif";
    }catch(e){}
  }

  pairs.forEach(function(p){
    writeBlock(p.el,p.val);
    p.el.style.setProperty('font-size',(size||18)+'px','important');
    p.el.style.setProperty('line-height','1.75','important');
    p.el.style.setProperty('word-break','normal','important');
    p.el.style.setProperty('overflow-wrap','break-word','important');
    if(fam)p.el.style.setProperty('font-family',fam,'important');
  });

  try{
    if(window.__nsWtrObserver)window.__nsWtrObserver.disconnect();
  }catch(e){}
  el.setAttribute('data-ns','1');
  el.setAttribute('data-ns-page-id',pageId||'');
  el.setAttribute('data-ns-wtr-translation','1');
  window.__nsEl=el;
  window.__nsOrig=old;
  window.__nsShown=1;
  window.__nsPageId=pageId||'';

  // WTR-LAB may re-render the active chapter after SPA navigation. Re-apply
  // from the original source DOM if the site replaces our nodes.
  var expected=el.innerHTML, applying=false;
  window.__nsWtrObserver=new MutationObserver(function(){
    if(applying||!window.__nsShown||!document.contains(el))return;
    if(el.getAttribute('data-ns')!=='1')return;
    if(el.innerHTML===expected)return;
    applying=true;
    try{
      // Re-run the same mapping against the new DOM.
      var fresh=el.querySelectorAll(tags);
      var matched=0;
      for(var k=0;k<pairs.length;k++){
        var targetText=norm(source[k]||'');
        var found=null;
        for(var q=0;q<fresh.length;q++){
          var cand=fresh[q];
          if(norm(cand.innerText||cand.textContent)===targetText){found=cand;break;}
        }
        if(found){writeBlock(found,paras[k]||'');matched++;}
      }
      if(matched>=Math.max(1,Math.floor(paras.length*0.75))){
        el.setAttribute('data-ns','1');
        expected=el.innerHTML;
      }
    }finally{applying=false;}
  });
  try{window.__nsWtrObserver.observe(el,{childList:true,subtree:true});}catch(e){}
  return 'ok:mapped:'+pairs.length;
})(__PARAS__,__SOURCE__,__FONT__,__SIZE__,__PAGE_ID__)
""".trimIndent()
            .replace("__PARAS__", JSONArray(paras).toString())
            .replace("__SOURCE__", JSONArray(source).toString())
            .replace("__FONT__", JSONObject.quote(fontFile))
            .replace("__SIZE__", fontSizePx.toString())
            .replace("__PAGE_ID__", JSONObject.quote(pageKey))

        webView.evaluateJavascript(js) { result ->
            callback(result != null && result.contains("ok"))
        }
    }

    private fun splitInternalMarkedTranslation(text: String): List<String> {
        val marker = Regex("""\[\d{3,}\]\s*""")
        val marked = marker.split(text).map { it.trim() }.filter { it.isNotEmpty() }
        if (marked.size > 1) return marked
        return text.split(Regex("""\n\s*\n|\n+"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun sourceSegments(text: String): List<String> {
        var blocks = text.split(Regex("""\n\s*\n"""))
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (blocks.size <= 2) {
            val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size >= 3) blocks = lines
            else {
                val idx = blocks.indices.maxByOrNull { blocks[it].length }
                if (idx != null && blocks[idx].length > 500) {
                    val chunks = chunkBySentences(blocks[idx], 260)
                    if (chunks.size > 1) blocks = blocks.toMutableList().also {
                        it.removeAt(idx); it.addAll(idx, chunks)
                    }
                }
            }
        }
        if (blocks.size == 1 && blocks[0].length > 800) blocks = chunkBySentences(blocks[0])
        return blocks
    }

    private fun chunkBySentences(text: String, target: Int = 260): List<String> {
        val ends = "。！？!?…」』”।"
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in text) {
            sb.append(c)
            if (sb.length >= target && ends.indexOf(c) >= 0) {
                out.add(sb.toString().trim())
                sb.setLength(0)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out.filter { it.isNotEmpty() }
    }
}
