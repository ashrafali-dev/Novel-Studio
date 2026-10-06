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
function __reply(p){var l=__asst(p);if(l.length){var z=l[l.length-1];var inner=z.querySelector&&z.querySelector('.markdown,.prose,[class*="markdown"],[class*="prose"]');return inner||z;}var s=['article[data-turn="assistant"]','section[data-turn="assistant"]','[data-message-role="assistant"]','[data-testid^="conversation-turn-"][data-turn="assistant"]','[data-testid^="conversation-turn-"]:has([data-message-role="assistant"])','.agent-turn','.markdown','.prose','.ds-markdown','model-response','message-content','.font-claude-message','[class*="response-content" i]','[class*="markdown" i]'];var c=[];for(var i=0;i<s.length;i++){var a=[].slice.call(document.querySelectorAll(s[i]));for(var j=0;j<a.length;j++){var e=a[j],r=e.getBoundingClientRect(),tx=(e.innerText||e.textContent||'').trim();if(r.width>0&&r.height>0&&tx.length>=30&&!c.some(function(o){return o!==e&&o.contains(e);}))c.push(e);}}if(!c.length)return null;c.sort(function(a,b){return a.compareDocumentPosition(b)&Node.DOCUMENT_POSITION_FOLLOWING?-1:1;});return c[c.length-1];}
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
    }, Math.min(2500,700+text.length/40));
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
(function(sel,paras,font,size){
  var el=null;
  try{ if(sel) el=document.querySelector(sel); }catch(e){}
  // Jsoup's generated cssSelector can become stale after a SPA/navigation
  // rerender. Fall back to the same content selectors used by Extractor.
  if(!el){
    var sels=['#chapter-content','.chapter-content','.chapter_content','#chr-content','.chr-c',
      '.reading-content','.text-left','#content','.entry-content','.cha-content','.cha-words',
      '.chapter-body','.novel_content','.j_readContent','.txt','#chaptercontent','.chapter-c',
      '#article','.article-content','.content','article'];
    var best=null,bs=0;
    for(var i=0;i<sels.length;i++){
      var es=[];
      try{es=[].slice.call(document.querySelectorAll(sels[i]));}catch(e){es=[];}
      for(var j=0;j<es.length;j++){
        var x=es[j], tx=(x.innerText||'').trim();
        if(tx.length<500) continue;
        var sc=tx.length;
        sc+=(x.querySelectorAll('p').length*250);
        if(sc>bs){bs=sc;best=x;}
      }
    }
    if(!best){
      var es=[].slice.call(document.querySelectorAll('article,main,section,div'));
      for(var k=0;k<es.length;k++){
        var x=es[k],tx=(x.innerText||'').trim();
        if(tx.length<500) continue;
        var ps=x.querySelectorAll('p').length;
        if(ps<3) continue;
        var sc=tx.length+ps*250;
        if(sc>bs){bs=sc;best=x;}
      }
    }
    el=best;
  }
  // Never write into a page-wide wrapper (#app, body, a bare 'div'...): that wipes the
  // site's own UI and breaks navigation. Use the element that really holds the <p> lines.
  function dp(x){var n=0;for(var q=0;q<x.children.length;q++){if(x.children[q].tagName==='P')n++;}return n;}
  if(el&&dp(el)<5&&!el.getAttribute('data-ns')){
    var bd=null,bn=0,cs=[].slice.call(el.querySelectorAll('div,article,section,main'));
    for(var z=0;z<cs.length;z++){var nn=dp(cs[z]);if(nn>bn){bn=nn;bd=cs[z];}}
    if(bd&&bn>=5) el=bd;
    else if(el===document.body||el===document.documentElement||/^(app|root|__next|__nuxt)$/.test(el.id||'')) el=null;
  }
  if(!el) return 'noel';
  if(window.__nsEl!==el||window.__nsOrig==null){ window.__nsOrig=el.innerHTML; window.__nsEl=el; }
  var fam='';
  if(font&&font.length){
    try{
      var st=document.getElementById('__ns_font');
      if(!st){ st=document.createElement('style'); st.id='__ns_font'; (document.head||document.documentElement).appendChild(st); }
      st.textContent="@font-face{font-family:'NSBn';src:url('https://ns.local/fonts/"+font+".ttf') format('truetype');font-display:swap;}";
      fam="'NSBn',sans-serif";
    }catch(e){}
  }
  // side margin: only when the site's own container hugs the screen edge
  var rc=el.getBoundingClientRect(), cst=getComputedStyle(el);
  var padL=Math.max(0,16-Math.max(rc.left,0)-(parseFloat(cst.paddingLeft)||0));
  var padR=Math.max(0,16-Math.max(window.innerWidth-rc.right,0)-(parseFloat(cst.paddingRight)||0));
  var wrap=document.createElement('div');
  wrap.style.cssText='box-sizing:border-box;max-width:100%;text-align:left;overflow-wrap:anywhere;'
    +'padding:0 '+padR+'px 0 '+padL+'px;font-size:'+(size||18)+'px;';
  var frag=document.createDocumentFragment();
  for(var i=0;i<paras.length;i++){ var p=document.createElement('p'); p.textContent=paras[i]; p.style.margin='0 0 1em 0'; p.style.lineHeight='1.75'; if(fam){p.style.fontFamily=fam;} wrap.appendChild(p); }
  frag.appendChild(wrap);
  el.innerHTML=''; el.appendChild(frag); el.setAttribute('data-ns','1'); window.__nsShown=1;
  return 'ok';
})(__SEL__,__PARAS__,__FONT__,__SIZE__)
"""

    fun apply(sel: String, parasJson: String, font: String = "", size: Int = 18): String =
        APPLY_BODY.replace("__SEL__", org.json.JSONObject.quote(sel)).replace("__PARAS__", parasJson)
            .replace("__FONT__", org.json.JSONObject.quote(font)).replace("__SIZE__", size.toString())

    fun stillApplied(sel: String): String =
        "(function(sel){var el=null;try{if(sel)el=document.querySelector(sel);}catch(e){}" +
        "if(!el&&window.__nsEl&&document.contains(window.__nsEl))el=window.__nsEl;" +
        "if(!el)el=document.querySelector('[data-ns=\"1\"]');" +
        "return (el&&el.getAttribute('data-ns')==='1')?'ok':'lost';})(" +
        org.json.JSONObject.quote(sel) + ")"

    val TOGGLE = "(function(){var el=window.__nsEl;if(!el||window.__nsOrig==null||!document.contains(el))return 'none';" +
        "if(window.__nsShown){window.__nsTr=el.innerHTML;el.innerHTML=window.__nsOrig;window.__nsShown=0;el.removeAttribute('data-ns');return 'orig';}" +
        "else{el.innerHTML=window.__nsTr;window.__nsShown=1;el.setAttribute('data-ns','1');return 'tr';}})()"

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
            "next|next chapter|next ›|next »|›|»|→|下一章 |下一页|下一话|下一節|다음|다음화|次へ|次の話|পরবর্তী|নেক্সট"
        else
            "prev|previous|prev chapter|previous chapter|‹|«|←|上一章 |上一页|上一话|이전|이전í™”|前へ|前の話|আগের|পূর্ববর্তী"
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

    // ---------------------------------------------------------------- fast chapter extraction
    // Port of Novel Translator's DOM extractor:
    // read only the already-rendered chapter DOM, never serialize the whole page.
    // It deliberately keeps short lines (e.g. Chinese dialogue / labels) instead
    // of dropping them by character count.
    fun fastExtract(contentSel: String = "", titleSel: String = "", nextSel: String = "", prevSel: String = ""): String {
        return """
(function(){
  var SKIP={SCRIPT:1,STYLE:1,NOSCRIPT:1,SVG:1,CANVAS:1,VIDEO:1,AUDIO:1,IFRAME:1,INPUT:1,TEXTAREA:1,BUTTON:1,SELECT:1,OPTION:1,NAV:1,FOOTER:1,HEADER:1};
  var BLOCK={P:1,LI:1,BLOCKQUOTE:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,PRE:1,DIV:1,SECTION:1,ARTICLE:1};
  function clean(s){return String(s||'').replace(/\\u00a0/g,' ').replace(/[ \\t]+\\n/g,'\\n').replace(/\\n[ \\t]+/g,'\\n').replace(/\\n{3,}/g,'\\n\\n').trim();}
  function text(e){return clean(e?(e.innerText||e.textContent):'');}
  function visible(e){return !!e&&e.isConnected&&(e.offsetWidth>0||e.offsetHeight>0||e.getClientRects().length>0);}
  function pick(sel){if(!sel)return null;try{var e=document.querySelector(sel);return e&&visible(e)?e:null;}catch(x){return null;}}
  function stable(e){
    if(!e)return '';
    var id=(e.id||'').trim();
    if(id&&/^[A-Za-z_][A-Za-z0-9_-]*$/.test(id))return '#'+id;
    var cls=Array.from(e.classList||[]).filter(function(x){return /^[A-Za-z_][A-Za-z0-9_-]*$/.test(x);}).slice(0,3);
    return e.tagName.toLowerCase()+(cls.length?'.'+cls.join('.'): '');
  }
  function meaningful(s){return /[\\p{L}\\p{N}]/u.test(s||'');}
  function hasBlockChild(e){return Array.from(e.children||[]).some(function(c){return !SKIP[c.tagName]&&!!BLOCK[c.tagName];});}

  function roots(){
    var host=location.hostname.toLowerCase(), sels=[];
    if(host.indexOf('novel543.com')>=0)sels.push('.chapter-content','#content','.article-content');
    if(host.indexOf('webnovel.com')>=0)sels.push('.cha-words','.cha-content','.chapter-content','.chapter_content','article','main');
    if(host.indexOf('wtr-lab.com')>=0)sels.push('article','.chapter-content','main');
    sels.push(
      __CONTENT__,
      '.cha-words','.cha-content','.chapter-content','.chapter_content',
      '.article-content','#content','article[role="main"]','article','main','.content',
      '#chapter-content','#chr-content','.chr-c','.reading-content','.text-left',
      '.entry-content','.chapter-body','.novel_content','.j_readContent','.txt',
      '#chaptercontent','.chapter-c','#article'
    );
    var seen=[],out=[];
    sels.forEach(function(sel){
      if(!sel)return;
      var list=[];
      try{list=Array.from(document.querySelectorAll(sel));}catch(x){return;}
      list.forEach(function(e){
        if(!e||seen.indexOf(e)>=0||!e.isConnected)return;
        if(text(e).length<80)return;
        seen.push(e);out.push(e);
      });
    });
    return out.slice(0,8);
  }

  function units(root){
    var out=[],seen=[];
    function add(e){
      if(!e||SKIP[e.tagName]||seen.indexOf(e)>=0||!e.isConnected)return;
      var t=text(e);
      if(!meaningful(t))return;
      if(hasBlockChild(e))return;
      if(!t)return;
      seen.push(e);out.push(e);
    }

    var all=[root].concat(Array.from(root.querySelectorAll('p,li,blockquote,h1,h2,h3,h4,h5,h6,div,section,article,pre')));
    for(var i=0;i<all.length&&out.length<1500;i++)add(all[i]);

    // Standalone inline lines are common in mobile readers, especially WebNovel.
    Array.from(root.querySelectorAll('span,strong,b,font')).forEach(function(e){
      if(out.length>=1500||e.children.length)return;
      var p=e.parentElement,inText=false;
      while(p&&p!==root){
        if(['P','LI','BLOCKQUOTE','H1','H2','H3','H4','H5','H6','PRE'].indexOf(p.tagName)>=0){inText=true;break;}
        p=p.parentElement;
      }
      if(!inText)add(e);
    });

    // If the reader is mostly leaf spans, use them as the actual lines.
    if(out.length<3){
      Array.from(root.querySelectorAll('span')).forEach(function(e){
        if(out.length>=1500||e.children.length)return;
        var p=e.parentElement,inText=false;
        while(p&&p!==root){
          if(['P','LI','BLOCKQUOTE','H1','H2','H3','H4','H5','H6','PRE'].indexOf(p.tagName)>=0){inText=true;break;}
          p=p.parentElement;
        }
        if(!inText)add(e);
      });
    }

    // Direct text nodes between blocks.
    Array.from(root.querySelectorAll('div,section,article,p,li,blockquote,pre')).concat([root]).forEach(function(parent){
      if(out.length>=1500||seen.indexOf(parent)>=0)return;
      Array.from(parent.childNodes||[]).forEach(function(node){
        if(node.nodeType!==Node.TEXT_NODE)return;
        var v=clean(node.nodeValue);
        if(!meaningful(v))return;
        var span=document.createElement('span');
        span.setAttribute('data-ns-fast-generated','1');
        span.textContent=node.nodeValue;
        node.parentNode.replaceChild(span,node);
        add(span);
      });
    });
    return out.slice(0,1500);
  }

  var host=location.hostname.toLowerCase();
  // Novel543: use its already-rendered chapter container directly. The generic
  // unit walker is intentionally avoided here because it repeatedly calls
  // innerText/querySelectorAll over the entire chapter DOM and can turn a
  // millisecond extraction into a noticeable delay on mobile WebView.
  if(host==='novel543.com'||host.endsWith('.novel543.com')){
    var nr=pick('.chapter-content')||pick(__CONTENT__)||document.querySelector('#content,.article-content');
    if(nr){
      var nb=text(nr);
      if(meaningful(nb)&&nb.length>=80){
        var ntEl=pick(__TITLE__);
        if(!ntEl){try{ntEl=document.querySelector('.chapter-title,.chr-title,#chapter-heading,h1,h2');}catch(x){}}
        var nt=text(ntEl)||document.title||'';
        function nlink(kind,sel){
          var e=pick(sel);
          if(!e){
            var q=kind==='next'
              ? 'link[rel="next"],a[rel="next"],a.next,.next a,[aria-label*="next" i],[title*="next" i]'
              : 'link[rel="prev"],a[rel="prev"],a.prev,.prev a,[aria-label*="prev" i],[title*="prev" i]';
            try{e=document.querySelector(q);}catch(x){e=null;}
          }
          return e&&e.href?{href:e.href,text:text(e),selector:stable(e)}:null;
        }
        return JSON.stringify({ok:true,text:nb,segments:[nb],count:1,title:nt,
          pageTitle:document.title||'',novel:'',contentSel:stable(nr),
          titleSel:ntEl?stable(ntEl):'',next:nlink('next',__NEXT__),prev:nlink('prev',__PREV__)});
      }
    }
  }

  var content=pick(__CONTENT__);
  var rootList=roots();
  if(content)rootList=[content].concat(rootList.filter(function(x){return x!==content;}));
  var chosen=null,chosenUnits=[];
  for(var r=0;r<rootList.length;r++){
    var u=units(rootList[r]);
    if(u.length>0){chosen=rootList[r];chosenUnits=u;break;}
  }

  // Last resort: reader-like containers, still bounded and never a whole-page scan.
  if(!chosen){
    var cand=Array.from(document.querySelectorAll('[class*="chapter"],[class*="content"],[class*="words"],[id*="chapter"],[id*="content"],[id*="words"]'));
    cand.sort(function(a,b){return text(b).length-text(a).length;});
    for(var c=0;c<Math.min(12,cand.length);c++){
      if(text(cand[c]).length<200)continue;
      var cu=units(cand[c]);
      if(cu.length){chosen=cand[c];chosenUnits=cu;break;}
    }
  }

  if(!chosen||!chosenUnits.length)return JSON.stringify({ok:false});

  var titleEl=pick(__TITLE__);
  if(!titleEl){try{titleEl=document.querySelector('.chapter-title,.chr-title,#chapter-heading,h1,h2');}catch(x){}}
  var title=text(titleEl);
  if(!title)title=document.title||'';

  function link(kind,sel){
    var e=pick(sel);
    if(!e){
      var q=kind==='next'
        ? 'link[rel="next"],a[rel="next"],a.next,.next a,[aria-label*="next" i],[title*="next" i]'
        : 'link[rel="prev"],a[rel="prev"],a.prev,.prev a,[aria-label*="prev" i],[title*="prev" i]';
      try{e=document.querySelector(q);}catch(x){e=null;}
    }
    if(e&&e.href)return {href:e.href,text:text(e),selector:stable(e)};
    return null;
  }

  var segs=chosenUnits.map(function(e){return text(e);}).filter(meaningful);
  var body=segs.join('\\n\\n');
  var novel='';
  try{
    var m=document.querySelector('meta[property="og:novel:book_name"],meta[property="og:novel:novel_name"],meta[name="book_name"]');
    novel=m?(m.getAttribute('content')||'').trim():'';
  }catch(x){}
  if(!novel){
    try{
      var a=document.querySelector('a[href*="/novel/"],a[href*="/book/"],a[href*="/series/"]');
      if(a)novel=text(a);
    }catch(x){}
  }

  return JSON.stringify({
    ok:true,
    text:body,
    segments:segs,
    count:segs.length,
    title:title,
    pageTitle:document.title||'',
    novel:novel,
    contentSel:stable(chosen),
    titleSel:titleEl?stable(titleEl):'',
    next:link('next',__NEXT__),
    prev:link('prev',__PREV__)
  });
})()
""".trimIndent()
            .replace("__CONTENT__", org.json.JSONObject.quote(contentSel))
            .replace("__TITLE__", org.json.JSONObject.quote(titleSel))
            .replace("__NEXT__", org.json.JSONObject.quote(nextSel))
            .replace("__PREV__", org.json.JSONObject.quote(prevSel))
    }




}
