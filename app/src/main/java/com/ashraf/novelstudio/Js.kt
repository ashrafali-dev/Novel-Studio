package com.ashraf.novelstudio

// All the JavaScript we inject into pages lives here.
object Js {
    val DARK_ON = "(function(){var id='__nsdark';if(document.getElementById(id))return;var s=document.createElement('style');s.id=id;" +
        "s.textContent='html{filter:invert(1) hue-rotate(180deg)!important;background:#fff}img,video,picture,canvas{filter:invert(1) hue-rotate(180deg)!important}';" +
        "(document.head||document.documentElement).appendChild(s);})();"
    val DARK_OFF = "(function(){var e=document.getElementById('__nsdark');if(e)e.remove();})();"

    // ---------------------------------------------------------------- chatbot helpers (per-site profiles)
    private val PRELUDE = """
var __P={
 'chatgpt.com':{a:'[data-message-author-role="assistant"],[data-message-role="assistant"],article[data-turn="assistant"]',b:'.markdown',stop:'[data-testid="stop-button"],button[aria-label*="Stop" i]',send:'[data-testid="send-button"],button[aria-label*="Send" i]'},
 'chat.openai.com':{a:'[data-message-author-role="assistant"],[data-message-role="assistant"],article[data-turn="assistant"]',b:'.markdown',stop:'[data-testid="stop-button"],button[aria-label*="Stop" i]',send:'[data-testid="send-button"],button[aria-label*="Send" i]'},
 'gemini.google.com':{a:'model-response,.model-response-text,message-content',b:'.markdown',stop:'button[aria-label*="Stop" i]',send:'button[aria-label*="Send" i],button.send-button'},
 'claude.ai':{a:'.font-claude-message,[data-testid="assistant-message"]',b:'',stop:'button[aria-label*="Stop" i],[data-is-streaming="true"]',send:'button[aria-label*="Send" i]'},
 'deepseek.com':{a:'.ds-markdown',b:'',stop:'',send:''},
 'grok.com':{a:'[class*="message-bubble"],[class*="response-content-markdown"]',b:'',stop:'button[aria-label*="Stop" i]',send:'button[type="submit"],button[aria-label*="Submit" i]'}
};
var __D={a:'[data-message-author-role="assistant"],.markdown,.prose',b:'',stop:'button[aria-label*="Stop" i]',send:'button[aria-label*="Send" i],button[type="submit"]'};
function __prof(){var h=location.hostname;for(var k in __P){if(h===k||h.endsWith('.'+k))return __P[k];}return __D;}
function __asst(p){var s=['[data-message-author-role="assistant"]','[data-message-role="assistant"]','[data-message-author="assistant"]','[data-role="assistant"]','article[data-turn="assistant"]','section[data-turn="assistant"]','[data-testid^="conversation-turn-"][data-turn="assistant"]','[data-testid^="conversation-turn-"]:has([data-message-role="assistant"])','.agent-turn',p.a,'[data-testid*="assistant" i]','model-response','.font-claude-message','.ds-markdown','message-content','[class*="response-content" i]','[class*="assistant-message" i]','[class*="assistant" i]'].filter(Boolean).join(',');var l=[].slice.call(document.querySelectorAll(s));return l.filter(function(e){var r=e.getBoundingClientRect(),tx=(e.innerText||e.textContent||'').trim();return r.width>0&&r.height>0&&tx.length>0&&!l.some(function(o){return o!==e&&o.contains(e);});});}
function __reply(p){if(location.hostname==='gemini.google.com'){var g=__asst(p);if(g.length){var z=g[g.length-1],best=z,bt=((z.innerText||z.textContent||'').trim());var qs=['.markdown','.model-response-text','message-content','[class*="markdown" i]'];for(var qi=0;qi<qs.length;qi++){var aa=[];try{aa=[].slice.call(z.querySelectorAll(qs[qi]));}catch(e){aa=[];}for(var aj=0;aj<aa.length;aj++){var ae=aa[aj],ar=ae.getBoundingClientRect(),at=(ae.innerText||ae.textContent||'').trim();if(ar.width>0&&ar.height>0&&at.length>bt.length){best=ae;bt=at;}}}return best;}}var l=__asst(p);if(l.length){var z=l[l.length-1];var inner=z.querySelector&&z.querySelector('.markdown,.prose,[class*="markdown"],[class*="prose"]');return inner||z;}var s=['article[data-turn="assistant"]','section[data-turn="assistant"]','[data-message-role="assistant"]','[data-testid^="conversation-turn-"][data-turn="assistant"]','[data-testid^="conversation-turn-"]:has([data-message-role="assistant"])','.agent-turn','.markdown','.prose','.ds-markdown','model-response','message-content','.font-claude-message','[class*="response-content" i]','[class*="markdown" i]'];var c=[];for(var i=0;i<s.length;i++){var a=[].slice.call(document.querySelectorAll(s[i]));for(var j=0;j<a.length;j++){var e=a[j],r=e.getBoundingClientRect(),tx=(e.innerText||e.textContent||'').trim();if(r.width>0&&r.height>0&&tx.length>=30&&!c.some(function(o){return o!==e&&o.contains(e);}))c.push(e);}}if(!c.length)return null;c.sort(function(a,b){return a.compareDocumentPosition(b)&Node.DOCUMENT_POSITION_FOLLOWING?-1:1;});return c[c.length-1];}
function __stream(p){return (p.stop&&document.querySelector(p.stop))?1:0;}
function __box(){var c=[].slice.call(document.querySelectorAll('#prompt-textarea, textarea, div[contenteditable="true"], div[contenteditable="plaintext-only"], [role="textbox"]')).filter(function(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;});if(!c.length)return null;c.sort(function(a,b){return b.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom;});return c[0];}
"""

    private fun run(body: String) = PRELUDE + "\n;" + body

    private const val SEND_BODY = """
(function(text,doSend){
  var p=__prof(); var before=__asst(p); var n0=before.length; var len0=0;
  var old=__reply(p);
  if(old){var ob=p.b?(old.querySelector(p.b)||old):old;len0=((ob.innerText||ob.textContent||'').trim().length);}
  if(!len0&&n0){var old2=before[n0-1];var ob2=p.b?(old2.querySelector(p.b)||old2):old2;len0=((ob2.innerText||ob2.textContent||'').trim().length);}
  var box=__box();
  if(!box) return 'nobox';
  box.focus();
  if(box.tagName==='TEXTAREA'||box.tagName==='INPUT'){
    var proto=box.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto,'value').set.call(box,text);
    box.dispatchEvent(new Event('input',{bubbles:true}));
  } else {
    var sel=window.getSelection(); var range=document.createRange();
    range.selectNodeContents(box); sel.removeAllRanges(); sel.addRange(range);
    document.execCommand('insertText',false,text);
    if(!(box.innerText||'').trim()) box.textContent=text;
    box.dispatchEvent(new Event('input',{bubbles:true}));
    try{box.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));}catch(e){}
  }
  if(doSend){
    setTimeout(function(){
      var btn=null;
      try{btn=p.send?document.querySelector(p.send):null;}catch(e){}
      if(!btn) btn=document.querySelector('button[data-testid*="send" i],button[aria-label*="send" i],button[type="submit"]');
      if(btn&&!btn.disabled&&btn.getAttribute('aria-disabled')!=='true') btn.click();
      else {
        box.focus();
        box.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));
        box.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));
      }
    }, 60);
  }
  return 'ok:'+n0+':'+len0;
})(__TEXT__,__SEND__)
"""

    // type the text into the chat box (and press send if asked). Returns "ok:<assistant message count>" or "nobox"
    fun send(text: String, doSend: Boolean): String =
        run(SEND_BODY.replace("__TEXT__", org.json.JSONObject.quote(text)).replace("__SEND__", doSend.toString()))

    // "<assistant msg count>|<streaming 0/1>|<length of last reply>"
    fun readLen(): String = run("(function(){var p=__prof();var l=__asst(p);var n=l.length;var e=__reply(p);var b=e&&(p.b?(e.querySelector(p.b)||e):e);var len=b?((b.innerText||b.textContent||'').trim().length):0;return n+'|'+__stream(p)+'|'+len;})()")

    fun readText(): String = run("(function(){var p=__prof();var e=__reply(p);if(!e)return '';var b=p.b?(e.querySelector(p.b)||e):e;return (b.innerText||b.textContent||'').trim();})()")

    fun stop(): String = run("(function(){var p=__prof();var b=p.stop?document.querySelector(p.stop):null;if(b&&b.tagName==='BUTTON')b.click();return 'k';})()")

    // wipes a long leftover text (previous chapter) from the chat box
    fun clearBox(): String = run("(function(){var b=__box();if(!b)return 'n';var t=(b.value!==undefined?b.value:b.innerText)||'';if(t.length<150)return 's';b.focus();if(b.tagName==='TEXTAREA'||b.tagName==='INPUT'){Object.getOwnPropertyDescriptor(b.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value').set.call(b,'');b.dispatchEvent(new Event('input',{bubbles:true}));}else{document.execCommand('selectAll',false,null);document.execCommand('delete',false,null);}return 'c';})()")

    // ---------------------------------------------------------------- novel page: replace text / toggle
    private const val APPLY_BODY = """
(function(sel,paras){
  var el=null;
  try{if(sel)el=document.querySelector(sel);}catch(e){}
  if(!el)return 'noel';

  function textOf(e){return ((e&&(e.innerText||e.textContent))||'').trim();}

  var blocks=[];
  try{blocks=[].slice.call(el.querySelectorAll('p')).filter(function(x){return textOf(x).length>0;});}catch(e){blocks=[];}
  if(blocks.length<3){
    blocks=[];
    var kids=el.children||[];
    for(var k=0;k<kids.length;k++){
      if(textOf(kids[k]).length>0)blocks.push(kids[k]);
    }
  }
  if(blocks.length<1)return 'noel';

  if(window.__nsEl!==el){
    window.__nsEl=el;
    window.__nsShown=0;
    window.__nsOrigBlocks=[];
    window.__nsTrBlocks=[];
    for(var si=0;si<blocks.length;si++){
      var nodes=[];
      var w=document.createTreeWalker(blocks[si],NodeFilter.SHOW_TEXT,{
        acceptNode:function(n){
          var p=n.parentElement;
          if(!p)return NodeFilter.FILTER_REJECT;
          var tag=(p.tagName||'').toLowerCase();
          return (tag==='script'||tag==='style')?NodeFilter.FILTER_REJECT:NodeFilter.FILTER_ACCEPT;
        }
      },false);
      var n;
      while(n=w.nextNode())nodes.push({node:n,text:n.nodeValue||''});
      window.__nsOrigBlocks.push(nodes);
    }
  }

  function textNodes(root){
    var out=[];
    var w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,{
      acceptNode:function(n){
        var p=n.parentElement;
        if(!p)return NodeFilter.FILTER_REJECT;
        var tag=(p.tagName||'').toLowerCase();
        return (tag==='script'||tag==='style')?NodeFilter.FILTER_REJECT:NodeFilter.FILTER_ACCEPT;
      }
    },false);
    var n;
    while(n=w.nextNode())out.push(n);
    return out;
  }

  function putText(root,value){
    var ns=textNodes(root);
    if(!ns.length){root.appendChild(document.createTextNode(value));return;}
    var weights=[],total=0;
    for(var i=0;i<ns.length;i++){
      var w=Math.max(1,(ns[i].nodeValue||'').length);
      weights.push(w);total+=w;
    }
    var pos=0;
    for(var j=0;j<ns.length;j++){
      var take=(j===ns.length-1)?(value.length-pos):Math.round(value.length*weights[j]/total);
      if(take<0)take=0;
      ns[j].nodeValue=value.substring(pos,pos+take);
      pos+=take;
    }
  }

  var use=paras.slice();
  var first=(use[0]||'').trim();
  var h1='';
  try{
    var he=el.querySelector('h1,h2,.chapter-title,.chr-title,#chapter-heading');
    h1=textOf(he);
  }catch(e){}
  if(first&&h1){
    var nf=first.toLowerCase().replace(/^chapter\s*\d+\s*[:.#-]?\s*/,'').trim();
    var nh=h1.toLowerCase().replace(/^chapter\s*\d+\s*[:.#-]?\s*/,'').trim();
    if(first===h1||nf===nh||first.indexOf(h1)===0||h1.indexOf(first)===0)use.shift();
  }

  // If the model returned more paragraphs than the real DOM has, keep all
  // translation text by appending the overflow to the last real paragraph.
  if(use.length>blocks.length&&blocks.length>0){
    var merged=use.slice(0,blocks.length-1);
    merged.push(use.slice(blocks.length-1).join('\n\n'));
    use=merged;
  }

  var trBlocks=[];
  var count=Math.min(use.length,blocks.length);
  for(var q=0;q<count;q++){
    var value=(use[q]||'').trim();
    putText(blocks[q],value);
    trBlocks.push({
      textNodes:textNodes(blocks[q]).map(function(n){return {node:n,text:n.nodeValue||''};})
    });
  }

  window.__nsTrBlocks=trBlocks;
  el.setAttribute('data-ns','1');
  window.__nsShown=1;
  return 'ok';
})(__SEL__,__PARAS__)
    """

    fun apply(sel: String, parasJson: String): String =
        APPLY_BODY.replace("__SEL__", org.json.JSONObject.quote(sel)).replace("__PARAS__", parasJson)

    fun stillApplied(sel: String): String =
        "(function(sel){var el=null;try{if(sel)el=document.querySelector(sel);}catch(e){}" +
        "if(!el&&window.__nsEl&&document.contains(window.__nsEl))el=window.__nsEl;" +
        "if(!el)el=document.querySelector('[data-ns=\"1\"]');" +
        "return (el&&el.getAttribute('data-ns')==='1')?'ok':'lost';})(" +
        org.json.JSONObject.quote(sel) + ")"

    val TOGGLE = """(function(){
  var el=window.__nsEl;
  if(!el||!document.contains(el)||!window.__nsOrigBlocks||!window.__nsTrBlocks)return 'none';
  function setGroup(group){
    for(var i=0;i<group.length;i++){
      var n=group[i].node;
      if(document.contains(n))n.nodeValue=group[i].text||'';
    }
  }
  if(window.__nsShown){
    for(var i=0;i<window.__nsOrigBlocks.length;i++)setGroup(window.__nsOrigBlocks[i]);
    window.__nsShown=0;
    el.removeAttribute('data-ns');
    return 'orig';
  }else{
    for(var j=0;j<window.__nsTrBlocks.length;j++)setGroup(window.__nsTrBlocks[j].textNodes||[]);
    window.__nsShown=1;
    el.setAttribute('data-ns','1');
    return 'tr';
  }
})()"""


    // ---------------------------------------------------------------- click the site's own Next / Prev button
    private const val CLICK_BODY = """
(function(){
  var re=new RegExp('^('+'__ALTS__'+')$','i');
  var wre=new RegExp('__WORD__','i');
  var dir='__DIR__';

  // WebNovel and other readers may use custom elements or icon-only controls.
  // Prefer semantic selectors first, then fall back to text/metadata scoring.
  var direct = dir==='next'
    ? ['#next','[id="next"]','[data-testid="next"]','[aria-label="Next Chapter" i]','[title="Next Chapter" i]','button[title*="Next Chapter" i]','a[title*="Next Chapter" i]','mov-button#next']
    : ['#prev','[id="prev"]','[data-testid="prev"]','[aria-label="Previous Chapter" i]','[title="Previous Chapter" i]','button[title*="Previous Chapter" i]','a[title*="Previous Chapter" i]','mov-button#prev'];

  for(var d=0;d<direct.length;d++){
    var ds=[];
    try{ds=[].slice.call(document.querySelectorAll(direct[d]));}catch(e){ds=[];}
    for(var q=0;q<ds.length;q++){
      var de=ds[q],dr=de.getBoundingClientRect();
      if(dr.width>=3&&dr.height>=3&&!de.disabled&&de.getAttribute('aria-disabled')!=='true'){
        try{de.click();return 'clicked';}catch(x){}
      }
    }
  }

  var links=[].slice.call(document.querySelectorAll('a[href],button,[role=button],div,span,li,i'));
  var best=null,bs=0;
  for(var i=0;i<links.length;i++){
    var e=links[i],tc=(e.textContent||'').trim();
    if(tc.length>40) continue;
    var cn=(typeof e.className==='string')?e.className:'';
    var meta=(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+cn+' '+(e.id||'')+' '+(e.getAttribute('data-eventname')||'');
    var href=(e.getAttribute('href')||'');
    var s=0;
    if(re.test(tc)) s+=6; else if(tc.length<=25&&wre.test(tc)) s+=4;
    if(wre.test(meta)) s+=4;
    if(/chapter|\/book\//i.test(href)&&wre.test(href)) s+=3;
    if(s===0) continue;
    if(/disabled/i.test(cn)||e.disabled||e.getAttribute('aria-disabled')==='true') continue;
    var r=e.getBoundingClientRect();
    if(r.width<3||r.height<3) continue;
    if(/chap/i.test(meta+tc+href)) s+=1;
    if(s>bs){bs=s;best=e;}
  }
  if(!best) return 'none';
  try{best.click();return 'clicked';}catch(x){return 'none';}
})()
"""

    fun clickNext(dir: String): String {
        val alts = if (dir == "next")
            "next|next chapter|next ›|next »|›|»|→|下一章|下一页|下一话|下一節|다음|다음화|次へ|次の話|পরবর্তী|নেক্সট"
        else
            "prev|previous|prev chapter|previous chapter|‹|«|←|上一章|上一页|上一话|이전|이전화|前へ|前の話|আগের|পূর্ববর্তী"
        val word = if (dir == "next") "next" else "prev(?!iew)"
        return CLICK_BODY.replace("__ALTS__", alts).replace("__WORD__", word).replace("__DIR__", dir)
    }    // WebNovel navigation based on the open-source WebnovelReader crawler.
    // That project uses the site's stable chapter catalog selector:
    //   .j_catalog_list .volume-item li a
    // and opens <book-path>/catalog, then walks the adjacent chapter.
    // This avoids guessing the mobile reader's icon/button DOM.

    // Called after the WebView has loaded /book/<slug>/catalog.
    // Returns the adjacent chapter URL without navigating the catalog page.
    fun webNovelPickCatalog(dir: String, currentTitle: String): String {
        val safe = currentTitle
            .replace("\\", "\\\\")
            .replace("'", "\\'")
        return """
(function(){
  var dir='__DIR__', title='__TITLE__';
  var norm=function(s){return (s||'').replace(/\s+/g,' ').trim().toLowerCase();};
  var stripIndex=function(s){return norm(s).replace(/^\s*\d+\s*[-.:)]?\s*/,'');};
  var clean=function(u){
    try{return new URL(u,location.href).pathname.replace(/\/+$/,'');}
    catch(e){return String(u||'').split('?')[0].split('#')[0].replace(/\/+$/,'');}
  };

  var path=location.pathname.replace(/\/+$/,'');
  var bm=path.match(/^\/book\/[^/]+/i);
  var bookPath=bm?bm[0]:'';
  if(!bookPath)return 'none';

  var curTitle=stripIndex(title);
  var as=[].slice.call(document.querySelectorAll('.j_catalog_list .volume-item li a[href], .j_catalog_list a[href], a[href]'));
  var links=[];

  for(var i=0;i<as.length;i++){
    var a=as[i], h=a.getAttribute('href')||'';
    if(!h)continue;
    var p=clean(h);
    if(p===clean(location.href)||/\/catalog\/?$/i.test(p))continue;
    if(p.indexOf(bookPath+'/')!==0)continue;

    var t=stripIndex(
      a.getAttribute('title') ||
      a.getAttribute('aria-label') ||
      a.textContent ||
      ''
    );
    if(!t)continue;
    links.push({p:p,t:t});
  }

  if(!links.length)return 'none';

  var idx=-1;
  for(var x=0;x<links.length;x++){
    if(links[x].t===curTitle){idx=x;break;}
  }

  if(idx<0){
    var normalizeTitle=function(s){
      return norm(s)
        .replace(/[“”"']/g,'')
        .replace(/\s*[-–—:]\s*/g,' ')
        .replace(/\s+/g,' ')
        .trim();
    };
    var nt=normalizeTitle(curTitle);
    for(var y=0;y<links.length;y++){
      if(normalizeTitle(links[y].t)===nt){idx=y;break;}
    }
  }

  if(idx<0){
    var words=curTitle.split(/\s+/).filter(function(w){return w.length>=3;});
    var part=(curTitle.match(/\(part\s+([0-9]+)\)/i)||[])[1]||'';
    var best=-1,bestScore=0;
    for(var z=0;z<links.length;z++){
      var sc=0,t=links[z].t;
      for(var q=0;q<words.length;q++){
        if(t.indexOf(words[q])>=0)sc+=words[q].length>=5?3:1;
      }
      if(part&&new RegExp('\\(part\\s+'+part+'\\)','i').test(t))sc+=8;
      if(sc>bestScore){bestScore=sc;best=z;}
    }
    if(bestScore>=6)idx=best;
  }

  if(idx<0)return 'none';
  var ni=dir==='next'?idx+1:idx-1;
  if(ni<0||ni>=links.length)return 'edge';

  try{return new URL(links[ni].p,location.href).href;}
  catch(e){return 'none';}
})()
""".trimIndent()
            .replace("__TITLE__", safe)
            .replace("__DIR__", dir)
    }

    fun webNovelNext(dir: String, currentTitle: String): String {
        // WebNovel mobile can expose either /book/<numeric-id> or a slug.
        // Do not require a numeric bookId: the catalog URL works for both.
        val safe = currentTitle
            .replace("\\", "\\\\")
            .replace("'", "\\'")
        return """
(function(){
  var dir='__DIR__', title='__TITLE__';
  var norm=function(s){return (s||'').replace(/\s+/g,' ').trim().toLowerCase();};
  var stripIndex=function(s){return norm(s).replace(/^\s*\d+\s*[-.:)]?\s*/,'');};
  var clean=function(u){
    try{return new URL(u,location.href).pathname.replace(/\/+$/,'');}
    catch(e){return String(u||'').split('?')[0].split('#')[0].replace(/\/+$/,'');}
  };

  var path=location.pathname.replace(/\/+$/,'');
  var bm=path.match(/^\/book\/[^/]+/i);
  var bookPath=bm?bm[0]:'';
  if(!bookPath)return 'failed:no-book-path';

  var curTitle=stripIndex(title);
  var currentUrl=clean(location.href);

  function scoreTitle(a,b){
    if(a===b)return 100000;
    var aw=a.split(/\s+/).filter(function(w){return w.length>=2;});
    var score=0;
    for(var i=0;i<aw.length;i++){
      if(b.indexOf(aw[i])>=0)score+=aw[i].length>=5?3:1;
    }
    return score;
  }

  function navigateFromCatalog(html){
    var doc=new DOMParser().parseFromString(html,'text/html');
    var as=[].slice.call(doc.querySelectorAll('.j_catalog_list .volume-item li a[href], a[href]'));
    var links=[];
    for(var i=0;i<as.length;i++){
      var a=as[i], h=a.getAttribute('href')||'';
      if(!h)continue;
      var p=clean(h);
      if(p===clean(bookPath+'/catalog')||p===clean(location.href))continue;
      if(p.indexOf(bookPath+'/')!==0)continue;
      var t=stripIndex(a.getAttribute('title')||a.textContent||'');
      if(!t)continue;
      links.push({p:p,t:t});
    }
    if(!links.length)return 'failed:no-chapter-links';

    // First try exact chapter URL, if the reader exposes one.
    var idx=-1;
    for(var x=0;x<links.length;x++){
      if(links[x].p===currentUrl){idx=x;break;}
    }

    // Then exact normalized title. This correctly distinguishes:
    // Chapter 1 ... (part 1), (part 2), (part 3).
    if(idx<0){
      for(var y=0;y<links.length;y++){
        if(links[y].t===curTitle){idx=y;break;}
      }
    }

    // Last fallback: highest title similarity.
    if(idx<0){
      var best=-1,bestScore=0;
      for(var z=0;z<links.length;z++){
        var sc=scoreTitle(curTitle,links[z].t);
        if(sc>bestScore){bestScore=sc;best=z;}
      }
      if(bestScore>=3)idx=best;
    }

    if(idx<0)return 'failed:no-current-chapter';
    var ni=dir==='next'?idx+1:idx-1;
    if(ni<0||ni>=links.length)return 'failed:edge';
    try{
      location.href=new URL(links[ni].p,location.href).href;
      return 'catalog-chapter-go';
    }catch(e){return 'failed:bad-target';}
  }

  var catalogUrl=location.origin+bookPath+'/catalog';

  // The important part: this fetch is only used to read the ordered links.
  // The catalog page itself is never loaded into the WebView.
  fetch(catalogUrl,{credentials:'include',cache:'no-store'})
    .then(function(r){
      if(!r.ok)throw new Error('catalog HTTP '+r.status);
      return r.text();
    })
    .then(function(html){
      var result=navigateFromCatalog(html);
      if(result.indexOf('failed:')===0){
        console.log('[NovelStudio] WebNovel catalog parse:',result);
      }
    })
    .catch(function(e){
      console.log('[NovelStudio] WebNovel catalog fetch failed',e);
    });

  return 'webnovel-catalog-reading';
})()
""".trimIndent()
            .replace("__TITLE__", safe)
            .replace("__DIR__", dir)
    }



}
