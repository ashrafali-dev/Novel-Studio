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
 'chatgpt.com':{a:'[data-message-author-role="assistant"]',b:'.markdown',stop:'[data-testid="stop-button"],button[aria-label*="Stop" i]',send:'[data-testid="send-button"],button[aria-label*="Send" i]'},
 'chat.openai.com':{a:'[data-message-author-role="assistant"]',b:'.markdown',stop:'[data-testid="stop-button"],button[aria-label*="Stop" i]',send:'[data-testid="send-button"],button[aria-label*="Send" i]'},
 'gemini.google.com':{a:'model-response,.model-response-text,message-content',b:'.markdown',stop:'button[aria-label*="Stop" i]',send:'button[aria-label*="Send" i],button.send-button'},
 'claude.ai':{a:'.font-claude-message,[data-testid="assistant-message"]',b:'',stop:'button[aria-label*="Stop" i],[data-is-streaming="true"]',send:'button[aria-label*="Send" i]'},
 'deepseek.com':{a:'.ds-markdown',b:'',stop:'',send:''},
 'grok.com':{a:'[class*="message-bubble"],[class*="response-content-markdown"]',b:'',stop:'button[aria-label*="Stop" i]',send:'button[type="submit"],button[aria-label*="Submit" i]'}
};
var __D={a:'[data-message-author-role="assistant"],.markdown,.prose',b:'',stop:'button[aria-label*="Stop" i]',send:'button[aria-label*="Send" i],button[type="submit"]'};
function __prof(){var h=location.hostname;for(var k in __P){if(h===k||h.endsWith('.'+k))return __P[k];}return __D;}
function __asst(p){var s=[p.a,'[data-message-author-role="assistant"]','model-response','.font-claude-message','.ds-markdown','[class*="response-content" i]','[class*="assistant-message" i]'].filter(Boolean).join(',');var l=[].slice.call(document.querySelectorAll(s));return l.filter(function(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0&&!l.some(function(o){return o!==e&&o.contains(e);});});}
function __stream(p){return (p.stop&&document.querySelector(p.stop))?1:0;}
function __box(){var c=[].slice.call(document.querySelectorAll('#prompt-textarea, textarea, div[contenteditable="true"], div[contenteditable="plaintext-only"], [role="textbox"]')).filter(function(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;});if(!c.length)return null;c.sort(function(a,b){return b.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom;});return c[0];}
"""

    private fun run(body: String) = PRELUDE + "\n;" + body

    private const val SEND_BODY = """
(function(text,doSend){
  var p=__prof(); var before=__asst(p); var n0=before.length; var len0=0;
  if(n0){var old=before[n0-1];var ob=p.b?(old.querySelector(p.b)||old):old;len0=(ob.innerText||'').length;}
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
    fun readLen(): String = run("(function(){var p=__prof();var l=__asst(p);var n=l.length;var len=0;if(n){var e=l[n-1];var b=p.b?(e.querySelector(p.b)||e):e;len=(b.innerText||b.textContent||'').length;}return n+'|'+__stream(p)+'|'+len;})()")

    fun readText(): String = run("(function(){var p=__prof();var l=__asst(p);var n=l.length;if(!n)return '';var e=l[n-1];var b=p.b?(e.querySelector(p.b)||e):e;return (b.innerText||b.textContent||'').trim();})()")

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
        "if(!el)el=document.querySelector('[data-ns="1"]');" +
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
  var els=[].slice.call(document.querySelectorAll('a,button,[role=button],div,span,li,i'));
  var best=null,bs=0;
  for(var i=0;i<els.length;i++){
    var e=els[i];
    var tc=(e.textContent||'').trim();
    if(tc.length>25) continue;
    var cn=(typeof e.className==='string')?e.className:'';
    var meta=(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')+' '+cn+' '+(e.id||'')+' '+(e.getAttribute('data-eventname')||'');
    var s=0;
    if(re.test(tc)) s+=5; else if(tc.length<=20&&wre.test(tc)) s+=3;
    if(wre.test(meta)) s+=2;
    if(s===0) continue;
    if(/disabled/i.test(cn)||e.disabled||e.getAttribute('aria-disabled')==='true') continue;
    var r=e.getBoundingClientRect(); if(r.width<3||r.height<3) continue;
    if(/chap/i.test(meta+tc)) s+=1;
    if(s>bs){bs=s;best=e;}
  }
  if(!best) return 'none';
  try{ best.click(); }catch(x){}
  return 'clicked';
})()
"""

    fun clickNext(dir: String): String {
        val alts = if (dir == "next")
            "next|next chapter|next ›|next »|›|»|→|下一章|下一页|下一话|下一節|다음|다음화|次へ|次の話|পরবর্তী|নেক্সট"
        else
            "prev|previous|prev chapter|previous chapter|‹|«|←|上一章|上一页|上一话|이전|이전화|前へ|前の話|আগের|পূর্ববর্তী"
        val word = if (dir == "next") "next" else "prev(?!iew)"
        return CLICK_BODY.replace("__ALTS__", alts).replace("__WORD__", word)
    }
}
