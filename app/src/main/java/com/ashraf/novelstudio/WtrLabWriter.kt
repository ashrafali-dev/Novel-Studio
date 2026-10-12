package com.ashraf.novelstudio

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

/**
 * WTR-LAB writer.
 *
 * Important: do not recreate the chapter as one HTML string. WTR's reader
 * contains real paragraph/block elements, so translation must replace the
 * text inside the corresponding original block only. Inline formatting is
 * intentionally NOT copied because it was not reliably aligned with Bengali.
 */
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
        val translated = splitInternalMarkedTranslation(translatedText)
        val source = sourceSegments(chapter.text)
        if (translated.isEmpty() || source.isEmpty() || translated.size != source.size) {
            callback(false)
            return
        }

        val js = """
(function(translated,source,font,size,pageId){
  function norm(s){
    return String(s||'')
      .replace(/\u00a0/g,' ')
      .replace(/[\t\r\n]+/g,' ')
      .replace(/\s+/g,' ')
      .trim();
  }
  function visible(e){
    if(!e)return false;
    var r=e.getBoundingClientRect(),s=getComputedStyle(e);
    return r.width>0 && r.height>0 && s.display!=='none' && s.visibility!=='hidden';
  }

  function activeBody(){
    var tr=document.querySelector('.chapter-tracker.active[data-chapter-no]') ||
            document.querySelector('.chapter-tracker.active');
    var no=tr && tr.getAttribute('data-chapter-no');
    var sels=[];
    if(no){
      sels.push(
        '#chapter-'+no+' .chapter-body',
        '#tracker-'+no+' .chapter-body',
        '.chapter-tracker[data-chapter-no="'+no+'"] .chapter-body',
        '[data-chapter-no="'+no+'"] .chapter-body'
      );
    }
    sels.push('#chapter-content','.chapter-tracker.active .chapter-body',
              '.chapter-body','.chapter-content');
    for(var i=0;i<sels.length;i++){
      try{
        var e=document.querySelector(sels[i]);
        if(e && norm(e.innerText||e.textContent).length>=40)return e;
      }catch(x){}
    }
    return null;
  }

  var root=activeBody();
  if(!root)return 'no-body';

  // If this exact chapter was already translated, restore the original DOM
  // before mapping again. Never map a translation onto another translation.
  if(root.getAttribute('data-ns')==='1' &&
     root.getAttribute('data-ns-page-id')===String(pageId||'') &&
     window.__nsOrig){
    try{ root.innerHTML=window.__nsOrig; }catch(x){}
  }

  var selectors=[
    'p','.wtr-line','[data-line]','li','blockquote','pre',
    'h1','h2','h3','h4','h5','h6',
    'div'
  ];
  var nodes=[];
  var seen=new Set();

  function isBlock(e){
    if(!e || seen.has(e) || !visible(e))return false;
    var t=norm(e.innerText||e.textContent);
    if(!t)return false;

    // A wrapper is not a paragraph. Only accept it when it does not contain
    // another candidate block. This prevents the whole chapter being treated
    // as one giant <p>/<div>.
    var nested=0;
    try{
      nested=e.querySelectorAll('p,.wtr-line,[data-line],li,blockquote,pre,h1,h2,h3,h4,h5,h6,div').length;
    }catch(x){}
    if(e.tagName==='DIV' && nested>0)return false;
    if(/^(SECTION|ARTICLE)$/.test(e.tagName))return false;

    return true;
  }

  for(var s=0;s<selectors.length;s++){
    try{
      var list=root.querySelectorAll(selectors[s]);
      for(var i=0;i<list.length;i++){
        var e=list[i];
        if(isBlock(e)){
          seen.add(e);
          nodes.push(e);
        }
      }
    }catch(x){}
  }

  // querySelectorAll above groups by selector, not DOM order. Put blocks back
  // into their actual document order.
  nodes.sort(function(a,b){
    if(a===b)return 0;
    var p=a.compareDocumentPosition(b);
    return (p & Node.DOCUMENT_POSITION_FOLLOWING) ? -1 : 1;
  });

  // First and safest mapping: source paragraph text must equal the original
  // text inside its DOM block (ignoring whitespace). This guarantees that we
  // never put Bengali into the wrong paragraph.
  var used=new Set();
  var pairs=[];
  for(var i=0;i<source.length;i++){
    var wanted=norm(source[i]);
    var found=null;
    for(var j=0;j<nodes.length;j++){
      var candidate=nodes[j];
      if(used.has(candidate))continue;
      if(norm(candidate.innerText||candidate.textContent)===wanted){
        found=candidate;
        break;
      }
    }
    if(!found){
      // Do not guess. A wrong paragraph is worse than showing no translation.
      return 'map-mismatch:'+i+':'+source.length+':'+nodes.length;
    }
    used.add(found);
    pairs.push({el:found,val:translated[i]});
  }

  // We deliberately require every translation block to have an original block.
  // No "put the rest into the last <p>" fallback.
  if(pairs.length!==translated.length)return 'map-mismatch';

  var old=root.innerHTML;

  // Replace ONLY the text content of the matched original block.
  // Therefore the original paragraph/block position remains exactly where WTR
  // put it, while all English text in that block disappears.
  for(var k=0;k<pairs.length;k++){
    var p=pairs[k];
    p.el.textContent=String(p.val||'').trim();
    p.el.style.setProperty('font-size',(size||18)+'px','important');
    p.el.style.setProperty('line-height','1.75','important');
    p.el.style.setProperty('word-break','normal','important');
    p.el.style.setProperty('overflow-wrap','break-word','important');
  }

  // Font is applied only to the translated blocks. No inline bold/italic
  // reconstruction is attempted.
  if(font && font.length){
    try{
      var st=document.getElementById('__ns_font');
      if(!st){
        st=document.createElement('style');
        st.id='__ns_font';
        (document.head||document.documentElement).appendChild(st);
      }
      st.textContent="@font-face{font-family:'NSBn';src:url('"+location.origin+"/__ns_font/"+font+".ttf') format('truetype');font-display:swap;}";
      for(var z=0;z<pairs.length;z++){
        pairs[z].el.style.setProperty('font-family',"'NSBn',sans-serif",'important');
      }
    }catch(x){}
  }

  try{ if(window.__nsWtrObserver)window.__nsWtrObserver.disconnect(); }catch(x){}

  root.setAttribute('data-ns','1');
  root.setAttribute('data-ns-page-id',pageId||'');
  root.setAttribute('data-ns-wtr-translation','1');
  window.__nsEl=root;
  window.__nsOrig=old;
  window.__nsShown=1;
  window.__nsPageId=pageId||'';

  // WTR may redraw the SPA chapter. If it does, do not blindly inject one
  // giant Bengali <p>. Re-run the exact source->original-block mapping.
  var observerBusy=false;
  window.__nsWtrObserver=new MutationObserver(function(){
    if(observerBusy || !window.__nsShown || !document.contains(root))return;
    if(root.getAttribute('data-ns')!=='1')return;

    observerBusy=true;
    try{
      var fresh=[];
      var freshSeen=new Set();
      for(var q=0;q<selectors.length;q++){
        var list=root.querySelectorAll(selectors[q]);
        for(var u=0;u<list.length;u++){
          var e=list[u];
          if(isBlock(e) && !freshSeen.has(e)){
            freshSeen.add(e);
            fresh.push(e);
          }
        }
      }
      fresh.sort(function(a,b){
        var p=a.compareDocumentPosition(b);
        return (p & Node.DOCUMENT_POSITION_FOLLOWING) ? -1 : 1;
      });

      var remap=[];
      var ok=true, taken=new Set();
      for(var a=0;a<source.length;a++){
        var want=norm(source[a]), hit=null;
        for(var b=0;b<fresh.length;b++){
          if(taken.has(fresh[b]))continue;
          if(norm(fresh[b].innerText||fresh[b].textContent)===want){
            hit=fresh[b];break;
          }
        }
        if(!hit){ok=false;break;}
        taken.add(hit);
        remap.push(hit);
      }

      if(ok){
        for(var d=0;d<remap.length;d++){
          remap[d].textContent=String(translated[d]||'').trim();
          remap[d].style.setProperty('font-size',(size||18)+'px','important');
          remap[d].style.setProperty('line-height','1.75','important');
          remap[d].style.setProperty('word-break','normal','important');
          remap[d].style.setProperty('overflow-wrap','break-word','important');
          if(font)remap[d].style.setProperty('font-family',"'NSBn',sans-serif",'important');
        }
      }
    }finally{
      observerBusy=false;
    }
  });

  try{root.__nsOriginalMutationGuard=true;window.__nsWtrObserver.observe(root,{childList:true,subtree:true});}catch(x){}
  return 'ok:mapped:'+pairs.length;
})(__TRANSLATED__,__SOURCE__,__FONT__,__SIZE__,__PAGE_ID__)
""".trimIndent()
            .replace("__TRANSLATED__", JSONArray(translated).toString())
            .replace("__SOURCE__", JSONArray(source).toString())
            .replace("__FONT__", JSONObject.quote(fontFile))
            .replace("__SIZE__", fontSizePx.toString())
            .replace("__PAGE_ID__", JSONObject.quote(pageKey))

        webView.evaluateJavascript(js) { result ->
            callback(result != null && result.contains("ok:mapped:"))
        }
    }

    private fun splitInternalMarkedTranslation(text: String): List<String> {
        // Match MainActivity's parser: markers may be adjacent or on the same line.
        val marker = Regex("""\[\d{3,}\]\s*""")
        val parts = if (marker.containsMatchIn(text)) {
            marker.split(text).map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            text.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
        }
        if (parts.size > 1) return parts
        val one = parts.firstOrNull() ?: return emptyList()
        val blankLines = one.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotEmpty() }
        if (blankLines.size >= 2) return blankLines
        val lines = one.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size >= 3) return lines
        if (one.length > 500) {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            for (ch in one) {
                sb.append(ch)
                if (sb.length >= 220 && "।.!?！？”…」』".indexOf(ch) >= 0) {
                    out.add(sb.toString().trim())
                    sb.setLength(0)
                }
            }
            if (sb.isNotBlank()) out.add(sb.toString().trim())
            return out.filter { it.isNotEmpty() }
        }
        return listOf(one)
    }

    // Must mirror MainActivity.addInternalMarkers(text, splitSingleLines = true)
    // exactly; otherwise source/translation counts diverge and WTR refuses to paste.
    private fun sourceSegments(text: String): List<String> {
        var blocks = text.split(Regex("""\n\s*\n"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (blocks.size <= 2) {
            val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size >= 3) {
                blocks = lines
            } else {
                val titleLike = Regex(
                    """^(?:#?\d+\s+)?(?:chapter|অধ্যায়|অধ্যায়)\b""",
                    RegexOption.IGNORE_CASE
                )
                val bodyIndex = blocks.indexOfFirst {
                    !titleLike.containsMatchIn(it) && it.length > 500
                }.takeIf { it >= 0 } ?: blocks.indices.maxByOrNull { blocks[it].length }

                if (bodyIndex != null && blocks[bodyIndex].length > 500) {
                    val split = chunkBySentences(blocks[bodyIndex], 260)
                    if (split.size > 1) {
                        blocks = blocks.toMutableList().also {
                            it.removeAt(bodyIndex)
                            it.addAll(bodyIndex, split)
                        }
                    }
                }
            }
        }

        if (blocks.size == 1 && blocks[0].length > 800) {
            blocks = chunkBySentences(blocks[0], 280)
        }
        return blocks
    }

    private fun chunkBySentences(text: String, target: Int): List<String> {
        val ends = "。！？!?…」』”"
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (sb.length >= target && ends.indexOf(ch) >= 0) {
                out.add(sb.toString().trim())
                sb.setLength(0)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString().trim())
        return out.filter { it.isNotEmpty() }
    }
}
