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

    private const val FAST_PRELUDE = """
function __providerFast(){
  var h=location.hostname.toLowerCase();
  if(h==='chatgpt.com'||h.endsWith('.chatgpt.com')||h==='chat.openai.com'||h.endsWith('.chat.openai.com'))return 'chatgpt';
  if(h==='gemini.google.com'||h.endsWith('.gemini.google.com'))return 'gemini';
  if(h==='deepseek.com'||h.endsWith('.deepseek.com'))return 'deepseek';
  if(h==='grok.com'||h.endsWith('.grok.com'))return 'grok';
  if(h==='claude.ai'||h.endsWith('.claude.ai'))return 'claude';
  return 'generic';
}
function __boxFast(){
  var p=__providerFast(),e=null;
  try{
    if(p==='gemini'){
      e=document.querySelector('rich-textarea .ql-editor[contenteditable="true"]:not(.ql-clipboard)') ||
        document.querySelector('[contenteditable="true"][aria-label="Enter a prompt for Gemini"]') ||
        document.querySelector('[contenteditable="true"][aria-label*="prompt for Gemini" i]');
    }else if(p==='chatgpt'){
      e=document.querySelector('#prompt-textarea') || document.querySelector('div.ProseMirror[contenteditable="true"]');
    }else if(p==='deepseek'){
      e=document.querySelector('textarea#chat-input') || document.querySelector('textarea');
    }
  }catch(x){}
  if(!e){
    var a=[];
    try{a=[].slice.call(document.querySelectorAll('textarea,div[contenteditable="true"],div[contenteditable="plaintext-only"],[role="textbox"]'));}catch(x){}
    a=a.filter(function(x){
      if(x.classList&&x.classList.contains('ql-clipboard'))return false;
      var r=x.getBoundingClientRect();
      return r.width>0&&r.height>0;
    });
    a.sort(function(x,y){return y.getBoundingClientRect().bottom-x.getBoundingClientRect().bottom;});
    e=a[0]||null;
  }
  return e;
}

function __visibleFast(e){
  if(!e)return false;
  var r=e.getBoundingClientRect();
  return r.width>0&&r.height>0;
}
    """

    private fun run(body: String) = PRELUDE + "\n;" + FAST_PRELUDE + "\n;" + body

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
function __visibleFast(e){
  if(!e)return false;
  var r=e.getBoundingClientRect();
  return r.width>0&&r.height>0;
}

"""

    // type the text into the chat box (and press send if asked). Returns "ok:<assistant message count>" or "nobox"
    private const val FAST_SEND_BODY = """
(function(text,doSend){
  var p=__prof(),before=__asst(p),n0=before.length,len0=0,old=__reply(p);
  if(old)len0=((old.innerText||old.textContent||'').trim().length);
  var box=__boxFast();
  if(!box)return 'nobox';
  var prov=__providerFast();
  function ev(e,n,d){
    try{e.dispatchEvent(new Event(n,{bubbles:true,composed:true}));}catch(x){}
    if(d){try{e.dispatchEvent(new InputEvent(n,{bubbles:true,composed:true,inputType:'insertText',data:d}));}catch(x){}}
  }
  box.focus();
  if(prov==='gemini'&&box.isContentEditable){
    var html=String(text).split('\n').map(function(line){
      if(!line.trim())return '<p><br></p>';
      return '<p>'+String(line).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;')+'</p>';
    }).join('');
    box.innerHTML=html;
    try{
      var range=document.createRange(),sel=window.getSelection(),last=box.lastElementChild||box;
      range.selectNodeContents(last);range.collapse(false);sel.removeAllRanges();sel.addRange(range);
    }catch(x){}
    ev(box,'focus');ev(box,'beforeinput',text);ev(box,'input');ev(box,'input',text);ev(box,'change');
  }else if(box.tagName==='TEXTAREA'||box.tagName==='INPUT'){
    var proto=box.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
    try{Object.getOwnPropertyDescriptor(proto,'value').set.call(box,text);}catch(x){box.value=text;}
    ev(box,'input');ev(box,'change');
  }else{
    try{
      var sel2=window.getSelection(),range2=document.createRange();
      range2.selectNodeContents(box);sel2.removeAllRanges();sel2.addRange(range2);
      document.execCommand('insertText',false,text);
    }catch(x){box.textContent=text;}
    if(!((box.innerText||box.textContent||'').trim()))box.textContent=text;
    ev(box,'beforeinput',text);ev(box,'input');ev(box,'input',text);ev(box,'change');
  }
  if(doSend){
    var tries=0;
    function submit(){
      var btn=null;
      try{
        if(p.send){
          var bs=[].slice.call(document.querySelectorAll(p.send));
          for(var i=0;i<bs.length;i++){
            if(__visibleFast(bs[i])&&!bs[i].disabled&&bs[i].getAttribute('aria-disabled')!=='true'){btn=bs[i];break;}
          }
        }
      }catch(x){}
      if(!btn){
        try{
          var fs=[].slice.call(document.querySelectorAll('button[data-testid*="send" i],button[aria-label*="send" i],button[aria-label*="submit" i],button[type="submit"]'));
          for(var j=0;j<fs.length;j++){
            if(__visibleFast(fs[j])&&!fs[j].disabled&&fs[j].getAttribute('aria-disabled')!=='true'){btn=fs[j];break;}
          }
        }catch(x){}
      }
      if(btn){btn.click();return;}
      if(++tries<12){setTimeout(submit,40);return;}
      try{
        box.focus();
        box.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true,composed:true}));
        box.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,composed:true}));
      }catch(x){}
    }
    setTimeout(submit,0);
  }
  return 'ok:'+n0+':'+len0;
})(__TEXT__,__SEND__)
""";


    fun send(text: String, doSend: Boolean): String =
        run(FAST_SEND_BODY.replace("__TEXT__", org.json.JSONObject.quote(text)).replace("__SEND__", doSend.toString()))

    // "<assistant msg count>|<streaming 0/1>|<length of last reply>"
    fun readLen(): String = run("(function(){var p=__prof();var l=__asst(p);var n=l.length;var e=__reply(p);var b=e&&(p.b?(e.querySelector(p.b)||e):e);var len=b?((b.innerText||b.textContent||'').trim().length):0;return n+'|'+__stream(p)+'|'+len;})()")

    fun readText(): String = run("(function(){var p=__prof();var e=__reply(p);if(!e)return '';var b=p.b?(e.querySelector(p.b)||e):e;return (b.innerText||b.textContent||'').trim();})()")

    fun stop(): String = run("(function(){var p=__prof();var b=p.stop?document.querySelector(p.stop):null;if(b&&b.tagName==='BUTTON')b.click();return 'k';})()")

    // wipes a long leftover text (previous chapter) from the chat box
    fun clearBox(): String = run("(function(){var b=__box();if(!b)return 'n';var t=(b.value!==undefined?b.value:b.innerText)||'';if(t.length<150)return 's';b.focus();if(b.tagName==='TEXTAREA'||b.tagName==='INPUT'){Object.getOwnPropertyDescriptor(b.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype,'value').set.call(b,'');b.dispatchEvent(new Event('input',{bubbles:true}));}else{document.execCommand('selectAll',false,null);document.execCommand('delete',false,null);}return 'c';})()")

    fun captureSegments(contentSel: String, titleSel: String): String =
        run("""
(function(rootSel,titleSel){
  var root=null,titleEl=null;
  try{if(rootSel)root=document.querySelector(rootSel);}catch(e){}
  try{if(titleSel)titleEl=document.querySelector(titleSel);}catch(e){}
  if(!root)return JSON.stringify({ok:false});

  function norm(t){return String(t||'').replace(/[\t ]+/g,' ').replace(/\n{3,}/g,'\n\n').trim();}
  function vis(e){return __visibleFast(e);}
  function mark(e,id){
    try{
      e.setAttribute('data-ns-source-id',id);
      e.setAttribute('data-ns-source-text',norm(e.innerText||e.textContent).slice(0,220));
    }catch(x){}
  }

  var out=[],used={};
  if(titleEl&&vis(titleEl)){
    var tt=norm(titleEl.innerText||titleEl.textContent);
    if(tt){
      mark(titleEl,'000');
      out.push({id:'000',text:tt,selector:'[data-ns-source-id="000"]'});
      used['000']=1;
    }
  }

  var candidates=[];
  try{
    candidates=[].slice.call(root.querySelectorAll('p,blockquote,li'));
  }catch(e){}
  if(!candidates.length){
    try{candidates=[].slice.call(root.querySelectorAll('div'));}catch(e){}
  }

  // Keep only leaf-like blocks. If a site uses nested div wrappers,
  // the outer container is not treated as a chapter paragraph.
  var filtered=[];
  for(var i=0;i<candidates.length;i++){
    var e=candidates[i],t=norm(e.innerText||e.textContent);
    if(!vis(e)||!t||t.length<1)continue;
    var hasCandidateChild=false;
    for(var j=0;j<candidates.length;j++){
      if(i!==j&&e.contains(candidates[j])){hasCandidateChild=true;break;}
    }
    if(hasCandidateChild)continue;
    filtered.push(e);
  }

  if(!filtered.length){
    var all=[].slice.call(root.children||[]);
    filtered=all.filter(function(e){return vis(e)&&norm(e.innerText||e.textContent).length>0;});
  }

  for(var k=0;k<filtered.length;k++){
    var el=filtered[k],text=norm(el.innerText||el.textContent);
    if(!text)continue;
    var id=String(k+1).padStart(3,'0');
    while(used[id])id='0'+id;
    mark(el,id);
    out.push({id:id,text:text,selector:'[data-ns-source-id="'+id+'"]'});
    used[id]=1;
  }

  return JSON.stringify({ok:out.length>0,segments:out});
})(__ROOT__,__TITLE__)
""".replace("__ROOT__", org.json.JSONObject.quote(contentSel))
 .replace("__TITLE__", org.json.JSONObject.quote(titleSel)))

    fun captureSiteSnapshot(sel: String): String =
        run("""
(function(sel){
  var content=null;
  try{if(sel)content=document.querySelector(sel);}catch(e){}
  if(!content){
    var ss=['#chapter-content','.chapter-content','.reading-content','.entry-content','.article-content','article','main'];
    for(var i=0;i<ss.length&&!content;i++){try{var x=document.querySelector(ss[i]);if(x&&__visibleFast(x)&&((x.innerText||'').trim().length>500))content=x;}catch(e){}}
  }
  if(!content)return JSON.stringify({ok:false});
  var css='',scripts=[],inline='';
  try{
    for(var i=0;i<document.styleSheets.length;i++){
      try{
        var rs=document.styleSheets[i].cssRules;
        if(rs){for(var j=0;j<rs.length;j++){css+=(rs[j].cssText||'')+'\n';if(css.length>=220000)break;}}
      }catch(e){}
      if(css.length>=220000)break;
    }
  }catch(e){}
  try{
    var sscr=document.scripts||[];
    for(var k=0;k<sscr.length;k++){
      var sc=sscr[k];
      if(sc.src)scripts.push(sc.src);
      else if((sc.textContent||'').trim())inline+=(sc.textContent||'').slice(0,10000)+'\n';
      if(scripts.length>=120&&inline.length>=60000)break;
    }
  }catch(e){}
  return JSON.stringify({
    ok:true,url:location.href,title:document.title||'',
    contentSelector:sel||'',
    contentHtml:(content.outerHTML||'').slice(0,260000),
    cssText:css.slice(0,220000),
    scripts:scripts.slice(0,120),
    scriptsInline:inline.slice(0,60000),
    capturedAt:Date.now()
  });
})(__SEL__)
""".replace("__SEL__", org.json.JSONObject.quote(sel)))

    fun applyMapped(sel: String, segmentsJson: String, mapJson: String, font: String = "", size: Int = 18): String =
        APPLY_MAPPED_BODY
            .replace("__SEL__", org.json.JSONObject.quote(sel))
            .replace("__SEGS__", segmentsJson)
            .replace("__MAP__", mapJson)
            .replace("__FONT__", org.json.JSONObject.quote(font))
            .replace("__SIZE__", size.toString())

    private const val APPLY_MAPPED_BODY = """
(function(sel,segments,map,font,size){
  var root=null;
  try{if(sel)root=document.querySelector(sel);}catch(e){}
  if(!root&&window.__nsEl&&document.contains(window.__nsEl))root=window.__nsEl;
  if(!root)return 'noel';

  if(!window.__nsMapOrig)window.__nsMapOrig={};
  if(!window.__nsMapRoot||window.__nsMapRoot!==root){
    window.__nsMapOrig={};
    window.__nsMapRoot=root;
  }

  function norm(s){return String(s||'').replace(/\s+/g,' ').trim();}
  function findId(id){
    try{
      var q='[data-ns-source-id="'+String(id).replace(/"/g,'')+'"]';
      return document.querySelector(q);
    }catch(e){return null;}
  }
  function findText(txt){
    var want=norm(txt),nodes=[];
    try{nodes=[].slice.call(root.querySelectorAll('p,div,li,blockquote,section,span'));}catch(e){}
    var best=null,bestScore=0;
    for(var i=0;i<nodes.length;i++){
      var n=nodes[i],t=norm(n.innerText||n.textContent);
      if(!t)continue;
      var score=0;
      if(t===want)score=100000;
      else if(t.indexOf(want)===0&&want.length>30)score=want.length;
      if(score>bestScore){bestScore=score;best=n;}
    }
    return best;
  }
  var applied=0,missing=0;
  for(var i=0;i<segments.length;i++){
    var seg=segments[i],id=String(seg.id||''),tr=map[id];
    if(typeof tr!=='string'||!tr.trim())continue;

    var el=findId(id);
    if(!el&&seg.selector){
      try{el=document.querySelector(seg.selector);}catch(e){}
    }
    if(!el)el=findText(seg.text||'');
    if(!el){missing++;continue;}

    if(!window.__nsMapOrig[id])window.__nsMapOrig[id]=el.innerHTML;

    el.innerHTML='';
    var span=document.createElement('span');
    span.textContent=tr.trim();
    if(font&&font.length)span.style.fontFamily="'NSBn',sans-serif";
    span.style.fontSize=(size||18)+'px';
    span.style.lineHeight='1.75';
    el.appendChild(span);
    el.setAttribute('data-ns','1');
    el.setAttribute('data-ns-source-id',id);
    if(!window.__nsMapTr)window.__nsMapTr={};
    window.__nsMapTr[id]=el.innerHTML;
    applied++;
  }

  try{
    if(font&&font.length){
      var st=document.getElementById('__ns_font');
      if(!st){
        st=document.createElement('style');
        st.id='__ns_font';
        (document.head||document.documentElement).appendChild(st);
      }
      st.textContent="@font-face{font-family:'NSBn';src:url('https://ns.local/fonts/"+font+".ttf') format('truetype');font-display:swap;}";
    }
  }catch(e){}

  window.__nsShown=1;window.__nsMapped=1;
  window.__nsEl=root;
  window.__nsOrig=root.innerHTML;
  return applied+':'+missing;
})(__SEL__,__SEGS__,__MAP__,__FONT__,__SIZE__)
"""

    val HIDE_REPLY_MARKERS = """
(function(){
  var p=__prof(),e=__reply(p);
  if(!e)return 'none';
  var els=[];
  try{els=[].slice.call(e.querySelectorAll('p,div,span'));}catch(x){}
  var n=0;
  for(var i=0;i<els.length;i++){
    var t=(els[i].innerText||els[i].textContent||'').trim();
    if(/^\[\d{1,4}\]$/.test(t)){els[i].style.display='none';n++;}
  }
  return 'hidden:'+n;
})()
"""

    fun hideReplyMarkers(): String = run(HIDE_REPLY_MARKERS)

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
        "(function(sel){if(window.__nsMapped&&window.__nsMapRoot){" +
        "return document.querySelector('[data-ns-source-id]')?'ok':'lost';}" +
        "var el=null;try{if(sel)el=document.querySelector(sel);}catch(e){}" +
        "if(!el&&window.__nsEl&&document.contains(window.__nsEl))el=window.__nsEl;" +
        "if(!el)el=document.querySelector('[data-ns="1"]');" +
        "return (el&&el.getAttribute('data-ns')==='1')?'ok':'lost';})(" +
        org.json.JSONObject.quote(sel) + ")"

    val TOGGLE = "(function(){" +
        "if(window.__nsMapped&&window.__nsMapOrig){" +
        "var show=!window.__nsShown;for(var id in window.__nsMapOrig){" +
        "var e=document.querySelector('[data-ns-source-id="'+id+'"]');" +
        "if(e){e.innerHTML=show?(window.__nsMapTr&&window.__nsMapTr[id]||e.innerHTML):window.__nsMapOrig[id];}" +
        "}" +
        "window.__nsShown=show?1:0;return show?'tr':'orig';" +
        "}" +
        "var el=window.__nsEl;if(!el||window.__nsOrig==null||!document.contains(el))return 'none';" +
        "if(window.__nsShown){window.__nsTr=el.innerHTML;el.innerHTML=window.__nsOrig;window.__nsShown=0;el.removeAttribute('data-ns');return 'orig';}" +
        "else{el.innerHTML=window.__nsTr;window.__nsShown=1;el.setAttribute('data-ns','1');return 'tr';}" +
        "})()"

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



}
