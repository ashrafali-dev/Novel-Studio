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
(function(sel,paras){
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
  if(!el) return 'noel';
  if(window.__nsEl!==el||window.__nsOrig==null){ window.__nsOrig=el.innerHTML; window.__nsEl=el; }
  var frag=document.createDocumentFragment();
  for(var i=0;i<paras.length;i++){ var p=document.createElement('p'); p.textContent=paras[i]; p.style.margin='0 0 1em 0'; p.style.lineHeight='1.75'; frag.appendChild(p); }
  el.innerHTML=''; el.appendChild(frag); el.setAttribute('data-ns','1'); window.__nsShown=1;
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
    fun webNovelNext(dir: String, currentTitle: String): String {
        // WebNovel's mobile reader often reuses /book/<id>/c and the
        // reader's Next button is not reliable. Use the site's chapter-list
        // endpoint first, then fall back to the real catalog DOM.
        val safe = currentTitle
            .replace("\\\\", "\\\\\\\\")
            .replace("'", "\\\\'")
        return """
(function(){
  var dir='__DIR__', title='__TITLE__';
  var norm=function(s){
    return (s||'').replace(/\\s+/g,' ').trim().toLowerCase();
  };
  var stripIndex=function(s){
    return norm(s).replace(/^\\s*\\d+\\s*[-.:)]?\\s*/,'');
  };
  var clean=function(u){
    try{return new URL(u,location.href).pathname.replace(/\\/+$/,'');}
    catch(e){return String(u||'').split('?')[0].split('#')[0].replace(/\\/+$/,'');}
  };
  var path=location.pathname;
  var bm=path.match(/^(\\/book\\/\\d+)/i);
  var bookPath=bm?bm[1]:'';
  var bookId=bm?bm[1].split('/').pop():'';
  if(!bookId)return 'failed:no-book-id';

  var curTitle=stripIndex(title);
  var currentCid='';
  try{
    var nodes=[].slice.call(document.querySelectorAll('[class*="j_chapter_"]'));
    for(var i=0;i<nodes.length;i++){
      var m=String(nodes[i].className||'').match(/(?:^|\\s)j_chapter_(\\d+)(?:\\s|$)/);
      if(m){currentCid=m[1];break;}
    }
  }catch(e){}

  function chapterTitle(x){
    return stripIndex(x.chapterName||x.title||x.name||x.chapterTitle||'');
  }
  function chapterId(x){
    return String(x.id||x.chapterId||x.cid||x.chapterID||'');
  }
  function flatten(data){
    var out=[];
    var vols=data&&data.volumeItems;
    if(!Array.isArray(vols))return out;
    for(var i=0;i<vols.length;i++){
      var items=vols[i]&&vols[i].chapterItems;
      if(!Array.isArray(items))continue;
      for(var j=0;j<items.length;j++){
        var x=items[j];
        if(x&&x.chapterLevel!==undefined&&String(x.chapterLevel)!=='0')continue;
        var id=chapterId(x);
        if(id)out.push({id:id,title:chapterTitle(x),index:x.index!==undefined?Number(x.index):(x.chapterIndex!==undefined?Number(x.chapterIndex):out.length)});
      }
    }
    return out;
  }

  function pickByTitle(list){
    if(!curTitle)return -1;
    var exact=-1;
    for(var i=0;i<list.length;i++){
      if(list[i].title===curTitle){exact=i;break;}
    }
    if(exact>=0)return exact;

    // Match the most distinctive words, including "(part N)".
    var words=curTitle.split(/\\s+/).filter(function(w){return w.length>=2;});
    var best=-1,score=0;
    for(var j=0;j<list.length;j++){
      var t=list[j].title, sc=0;
      for(var k=0;k<words.length;k++){
        if(t.indexOf(words[k])>=0)sc += words[k].length>=5 ? 2 : 1;
      }
      if(sc>score){score=sc;best=j;}
    }
    return score>=3?best:-1;
  }

  function goId(id){
    if(!id)return false;
    try{
      location.href=location.origin+bookPath+'/'+id;
      return true;
    }catch(e){return false;}
  }

  function parseResponse(txt){
    var s=(txt||'').trim();
    try{return JSON.parse(s);}catch(e){}
    s=s.replace(/^[^(]*\\(/,'').replace(/\\);?\\s*$/,'');
    try{return JSON.parse(s);}catch(e){}
    return null;
  }

  function useList(list){
    if(!list.length)return false;
    var idx=-1;
    if(currentCid){
      for(var i=0;i<list.length;i++){
        if(list[i].id===currentCid){idx=i;break;}
      }
    }
    if(idx<0)idx=pickByTitle(list);
    if(idx<0)return false;
    var ni=dir==='next'?idx+1:idx-1;
    if(ni<0||ni>=list.length)return false;
    return goId(list[ni].id);
  }

  function getCsrf(){
    try{
      var m=document.cookie.match(/(?:^|;\\s*)_csrfToken=([^;]+)/);
      return m?decodeURIComponent(m[1]):'';
    }catch(e){return '';}
  }

  var csrf=getCsrf();
  var api=location.origin+'/go/pcm/chapter/get-chapter-list?bookId='+
      encodeURIComponent(bookId)+'&pageIndex=0&_='+(new Date().getTime())+
      (csrf?'&_csrfToken='+encodeURIComponent(csrf):'');

  fetch(api,{credentials:'include',cache:'no-store'})
    .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.text();})
    .then(function(txt){
      var j=parseResponse(txt);
      var list=flatten(j&&j.data?j.data:j);
      if(useList(list))return;

      // Fallback: parse the actual catalog markup used by WebNovelReader:
      // .j_catalog_list .volume-item li a, with the chapter URL in href and
      // the canonical title in the anchor's title attribute.
      var catalogUrl=location.origin+bookPath+'/catalog';
      return fetch(catalogUrl,{credentials:'include',cache:'no-store'})
        .then(function(r){if(!r.ok)throw new Error('catalog HTTP '+r.status);return r.text();})
        .then(function(html){
          var doc=new DOMParser().parseFromString(html,'text/html');
          var as=[].slice.call(doc.querySelectorAll('.j_catalog_list .volume-item li a[href], a[href]'));
          var links=[];
          for(var i=0;i<as.length;i++){
            var a=as[i],h=a.getAttribute('href')||'',p=clean(h);
            if(!h||p===clean(catalogUrl))continue;
            var sameBook=p.indexOf(bookPath+'/')===0;
            if(!sameBook)continue;
            var t=stripIndex(a.getAttribute('title')||a.textContent||'');
            links.push({p:p,t:t});
          }
          if(!links.length)throw new Error('no chapter links');
          var idx=-1;
          if(currentCid){
            for(var x=0;x<links.length;x++){
              if(links[x].p.split('/').pop()===currentCid){idx=x;break;}
            }
          }
          if(idx<0){
            for(var y=0;y<links.length;y++){
              if(links[y].t===curTitle){idx=y;break;}
            }
          }
          if(idx<0){
            var best=-1,score=0,words=curTitle.split(/\\s+/).filter(function(w){return w.length>=2;});
            for(var z=0;z<links.length;z++){
              var sc=0;
              for(var q=0;q<words.length;q++)if(links[z].t.indexOf(words[q])>=0)sc+=words[q].length>=5?2:1;
              if(sc>score){score=sc;best=z;}
            }
            if(score>=3)idx=best;
          }
          if(idx<0)return 'failed:no-current-chapter';
          var ni=dir==='next'?idx+1:idx-1;
          if(ni<0||ni>=links.length)return 'failed:edge';
          try{location.href=new URL(links[ni].p,location.href).href;return 'catalog-go';}
          catch(e){return 'failed:bad-target';}
        });
    })
    .catch(function(e){
      console.log('[NovelStudio] WebNovel chapter navigation failed',e);
    });
  return 'webnovel-navigation-started';
})()
""".trimIndent()
            .replace("__TITLE__", safe)
            .replace("__DIR__", dir)
    }



}
