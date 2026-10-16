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
 'gemini.google.com':{a:'model-response,.model-response-text,message-content',b:'.markdown',stop:'button[aria-label*="Stop response" i],button[aria-label*="Stop generating" i],button[aria-label*="Stop" i],[data-testid*="stop" i],[data-test-id*="stop" i]',send:'button[aria-label*="Send" i],button.send-button'},
 'claude.ai':{a:'.font-claude-message,[data-testid="assistant-message"]',b:'',stop:'button[aria-label*="Stop" i],[data-is-streaming="true"]',send:'button[aria-label*="Send" i]'},
 'deepseek.com':{a:'.ds-markdown',b:'',stop:'',send:''},
 'grok.com':{a:'[class*="message-bubble"],[class*="response-content-markdown"]',b:'',stop:'button[aria-label*="Stop" i]',send:'button[type="submit"],button[aria-label*="Submit" i]'}
};
var __D={a:'[data-message-author-role="assistant"],.markdown,.prose',b:'',stop:'button[aria-label*="Stop" i]',send:'button[aria-label*="Send" i],button[type="submit"]'};
function __prof(){var h=location.hostname;for(var k in __P){if(h===k||h.endsWith('.'+k))return __P[k];}return __D;}
function __asst(p){var s=['[data-message-author-role="assistant"]','[data-message-role="assistant"]','[data-message-author="assistant"]','[data-role="assistant"]','article[data-turn="assistant"]','section[data-turn="assistant"]','[data-testid^="conversation-turn-"][data-turn="assistant"]','[data-testid^="conversation-turn-"]:has([data-message-role="assistant"])','.agent-turn',p.a,'[data-testid*="assistant" i]','model-response','.font-claude-message','.ds-markdown','message-content','[class*="response-content" i]','[class*="assistant-message" i]','[class*="assistant" i]'].filter(Boolean).join(',');var l=[].slice.call(document.querySelectorAll(s));return l.filter(function(e){var r=e.getBoundingClientRect(),tx=(e.innerText||e.textContent||'').trim();return r.width>0&&r.height>0&&tx.length>0&&!l.some(function(o){return o!==e&&o.contains(e);});});}
function __reply(p){var l=__asst(p);if(l.length){var z=l[l.length-1];var inner=z.querySelector&&z.querySelector('.markdown,.prose,[class*="markdown"],[class*="prose"]');return inner||z;}var s=['article[data-turn="assistant"]','section[data-turn="assistant"]','[data-message-role="assistant"]','[data-testid^="conversation-turn-"][data-turn="assistant"]','[data-testid^="conversation-turn-"]:has([data-message-role="assistant"])','.agent-turn','.markdown','.prose','.ds-markdown','model-response','message-content','.font-claude-message','[class*="response-content" i]','[class*="markdown" i]'];var c=[];for(var i=0;i<s.length;i++){var a=[].slice.call(document.querySelectorAll(s[i]));for(var j=0;j<a.length;j++){var e=a[j],r=e.getBoundingClientRect(),tx=(e.innerText||e.textContent||'').trim();if(r.width>0&&r.height>0&&tx.length>=30&&!c.some(function(o){return o!==e&&o.contains(e);}))c.push(e);}}if(!c.length)return null;c.sort(function(a,b){return a.compareDocumentPosition(b)&Node.DOCUMENT_POSITION_FOLLOWING?-1:1;});return c[c.length-1];}
function __stream(p){
 if(p.stop){try{if(document.querySelector(p.stop))return 1;}catch(e){}}
 // Claude can expose a textual thinking/responding status without a reliable stop button.
 if(/claude\.ai$/i.test(location.hostname)){
  var tail=(document.body.innerText||'').slice(-6000);
  if(/\b(?:Claude\s+is\s+thinking|Claude\s+is\s+responding|Untangling)\b/i.test(tail))return 1;
 }
 return 0;
}
function __nrm(t){return String(t||'').replace(/\s+/g,' ').trim();}
function __markEls(){var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),n,out=[];while(n=w.nextNode()){if(n.nodeValue.indexOf('[001]')>=0&&n.parentElement)out.push(n.parentElement);}return out;}
function __gen(){var sent=window.__nsSentN||'',baseM=window.__nsBaseM||new Set(),baseT=window.__nsBaseT||new Set(),echo=[],cand=[];
 __markEls().forEach(function(e){if(baseM.has(e))return;var t=__nrm(e.innerText||e.textContent);if(!t||baseT.has(t))return;if(sent&&sent.indexOf(t.slice(0,50))>=0){echo.push(e);return;}cand.push(e);});
 if(!cand.length)return null;
 var best=null;
 for(var i=0;i<cand.length;i++){var e=cand[i],len=(e.innerText||e.textContent||'').length;
  for(var up=0;up<6;up++){var pa=e.parentElement;if(!pa||pa===document.body||pa===document.documentElement||pa.tagName==='MAIN')break;
   var pt=pa.innerText||pa.textContent||'';if(pt.length>len*3+500)break;
   if(sent&&sent.indexOf(__nrm(pt).slice(0,50))>=0)break;
   var bad=false;for(var j=0;j<echo.length&&!bad;j++){if(pa.contains(echo[j]))bad=true;}
   if(!bad){var bm=[].slice.call(baseM);for(var k=0;k<bm.length&&!bad;k++){if(pa.contains(bm[k]))bad=true;}}
   if(bad)break;e=pa;len=pt.length;}
  if(!best||e.contains(best)||!best.contains(e))best=e;}
 return best;}
function __esc(x){var sp='.*+?^{}()|[]\\/-'+String.fromCharCode(36),o='';for(var k=0;k<x.length;k++){var c=x.charAt(k);o+=(sp.indexOf(c)>=0?'\\'+c:c);}return o;}
function __scan(){var sent=window.__nsSentN;if(!sent)return null;var raw=document.body.innerText||'';if(raw.length>400000)raw=raw.slice(-400000);
 var j=raw.lastIndexOf('[001]');
 if(j>=0){var seg=raw.slice(j);if(sent.indexOf(__nrm(seg).slice(0,50))<0)return {got:1,how:'marker',text:seg,len:seg.length,found:__mk(seg)};}
 var all=__nrm(raw),tail=sent.slice(-60),i=all.lastIndexOf(tail);
 if(i>=0){var rest=all.slice(i+tail.length).trim();if(rest.length>0){var rr=rest,last=-1,end=0,mm;try{var re=new RegExp(__esc(tail).replace(/ /g,'\\s+'),'g');while((mm=re.exec(raw))){end=mm.index+mm[0].length;last=mm.index;if(mm[0].length===0)re.lastIndex++;}}catch(e){last=-1;}if(last>=0){var cand=raw.slice(end).trim();if(cand.length>0)rr=cand;}return {got:1,how:'echo',text:rr,len:rr.length,found:0};}}
 return {got:0,how:'none',text:'',len:0,found:0};}
function __uiClean(t){
 var s=String(t||'').replace(/\u00a0/g,' ');
 var rules=[
  /Message\s+collapsed(?:\s+Show\s+more)?(?:\s+just\s+now)?/ig,
  /\bShow\s+more\b/ig,
  /\bUntangling\b/ig,
  /\bClaude\s+is\s+thinking\b/ig,
  /\bClaude\s+is\s+responding\b/ig,
  /\bClaude\s+is\s+AI\s+and\s+can\s+make\s+mistakes\.?/ig,
  /\bWrite\s+a\s+message(?:\.{3}|…)?/ig,
  /\bHaiku\s+5\.5\s+Low\b/ig,
  /\bDeepThink(?:\s+Search)?\b/ig
 ];
 rules.forEach(function(r){s=s.replace(r,' ');});
 return s.replace(/[ \t]+/g,' ').replace(/ *\n */g,'\n').replace(/\n{3,}/g,'\n\n').trim();
}
function __trim(t){var s=__uiClean(t);var ls=s.split('\n'),tok=/(?:copy|share|regenerate|retry|like|dislike|edit|read aloud|show thinking|good response|bad response|sources?)\s*$/i,note=/can make mistakes|ai-generated|for reference only|check important info|double-check|generated by ai/i;
 while(ls.length){var l=ls[ls.length-1].trim();if(!l){ls.pop();continue;}var m=l.search(note);if(m>=0&&l.length-m<140){var cut=l.slice(0,m).replace(/[A-Za-z'’ ]*$/,'').trim();if(cut){ls[ls.length-1]=cut;continue;}ls.pop();continue;}var l2=l;while(tok.test(l2)){l2=l2.replace(tok,'').trim();}if(l2!==l){if(l2){ls[ls.length-1]=l2;}else{ls.pop();}continue;}if(/^\d+\s*\/\s*\d+$/.test(l)){ls.pop();continue;}break;}
 return ls.join('\n').trim();}
function __fixm(t){return String(t||'').replace(/\*{1,2}(\[\d{3}\])\*{1,2}/g,function(m,a){return a;}).replace(/([^\n\s])[ \t]*(\[\d{3}\])/g,function(m,a,b){return a+'\n\n'+b;});}
function __ntext(p,e){
 var i=(p.b&&e.querySelector(p.b))||e.querySelector('.markdown,.prose,[class*="markdown"],[class*="prose"]')||e;
 try{
  var c=i.cloneNode(true);
  var remove='button,[role="button"],nav,aside,svg,script,style,noscript,textarea,input,time,[class*="thinking"],[class*="reasoning"],[class*="thought"],[class*="think"],[data-testid*="thinking"],[data-testid*="reasoning"],[data-testid*="thought"],[data-testid*="think"],[aria-label*="thinking"],[aria-label*="reasoning"],[aria-label*="think"]';
  c.querySelectorAll(remove).forEach(function(x){x.remove();});
  [].slice.call(c.querySelectorAll('*')).reverse().forEach(function(x){
   var t=__nrm(x.innerText||x.textContent);
   if(t.length<220&&/^(?:Message collapsed(?: Show more)?(?: just now)?|Show more|just now|Untangling(?: Untangling)?|Claude is thinking|Claude is responding|Write a message(?:\.{3}|…)?|Claude is AI and can make mistakes\.?|Haiku 5\.5 Low|DeepThink(?: Search)?)$/i.test(t))x.remove();
  });
  return __uiClean(c.innerText||c.textContent||'');
 }catch(err){return __uiClean(i.innerText||i.textContent||'');}
}
function __new(p,l){var base=window.__nsBase||[],bm=new Map(),bt=new Set();base.forEach(function(x){bm.set(x.el,x.t);bt.add(x.t);});var now=l||__asst(p),hit=null;for(var i=0;i<now.length;i++){var e=now[i],t=(e.innerText||e.textContent||'').trim(),o=bm.get(e);if(o===undefined){if(bt.has(t))continue;hit=e;}else if(t!==o){hit=e;}}return hit||__gen();}
function __mk(t){var m=t.match(/\[\d{3}\]/g)||[],s={},c=0;for(var i=0;i<m.length;i++){if(!s[m[i]]){s[m[i]]=1;c++;}}return c;}
function __trk(){var bn=window.__nsBaseNodes,bt=window.__nsBaseTexts,sent=window.__nsSentN;if(!bn||!sent||!document.body)return null;
 var skip='button,nav,aside,form,textarea,input,[contenteditable],[role="button"],[role="menu"],script,style,noscript,svg';
 var tw=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),n,parts=[];
 while(n=tw.nextNode()){if(bn.has(n))continue;var v=n.nodeValue;if(!v||!v.trim())continue;var pe=n.parentElement;if(!pe)continue;
  try{if(pe.closest(skip))continue;}catch(e){}
  var t=__nrm(v);if(bt&&bt.has(t))continue;
  if(t.length>=12&&(sent.indexOf(t)>=0||(t.length>=40&&sent.indexOf(t.slice(0,40))>=0)))continue;
  parts.push(v);}
 var text=parts.join('\n');return {len:text.length,found:__mk(text)};}
function __vis(b){if(!b||!b.isConnected)return false;var s=getComputedStyle(b),r=b.getBoundingClientRect();return s.display!=='none'&&s.visibility!=='hidden'&&r.width>0&&r.height>0;}
function __ok(b){return __vis(b)&&!b.disabled&&b.getAttribute('aria-disabled')!=='true';}
function __ctls(box){var out=[],seen=new Set(),c=box.parentElement,prev=[];for(var up=0;c&&up<7;up++,c=c.parentElement){var l=[].slice.call(c.querySelectorAll('button,[role="button"]'));if(l.length>14)break;prev=l;}prev.forEach(function(x){if(!seen.has(x)){seen.add(x);out.push(x);}});return out;}
function __snap(box){var m=new Map();__ctls(box).forEach(function(b){m.set(b,__ok(b));});return m;}
function __diffSend(box,snap){var cand=__ctls(box).filter(function(b){if(!__ok(b)||b.querySelector('input[type="file"]'))return false;var was=snap&&snap.get(b);return was===undefined||was===false;});return cand.length?cand[cand.length-1]:null;}
function __findSend(p,box,snap){var sels=[];if(p.send)sels.push(p.send);for(var i=0;i<sels.length;i++){var l=[];try{l=[].slice.call(document.querySelectorAll(sels[i]));}catch(e){}for(var j=0;j<l.length;j++){if(__ok(l[j]))return l[j];}}
 var d=__diffSend(box,snap);if(d)return d;
 var g=['button[data-testid*="send" i]','button[aria-label*="send" i]','[role="button"][aria-label*="send" i]','button[type="submit"]'];for(var k=0;k<g.length;k++){var m=[].slice.call(document.querySelectorAll(g[k]));for(var n=0;n<m.length;n++){if(__ok(m[n]))return m[n];}}
 if(/deepseek/i.test(location.hostname)){var c=box.parentElement;for(var up=0;c&&up<7;up++,c=c.parentElement){var b=[].slice.call(c.querySelectorAll('button,[role="button"]')).filter(function(x){return __ok(x)&&!(x.innerText||'').trim()&&!x.querySelector('input[type="file"]')&&(box.compareDocumentPosition(x)&Node.DOCUMENT_POSITION_FOLLOWING);});if(b.length)return b[b.length-1];}}
 return null;}
function __press(b){var ev=['pointerdown','mousedown','pointerup','mouseup','click'];try{for(var i=0;i<ev.length;i++){var C=ev[i].indexOf('pointer')===0&&window.PointerEvent?PointerEvent:MouseEvent;b.dispatchEvent(new C(ev[i],{bubbles:true,cancelable:true,view:window}));}}catch(e){try{b.click();}catch(x){}}}
function __box(){var c=[].slice.call(document.querySelectorAll('#prompt-textarea, textarea, div[contenteditable="true"], div[contenteditable="plaintext-only"], [role="textbox"]')).filter(function(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;});if(!c.length)return null;c.sort(function(a,b){return b.getBoundingClientRect().bottom-a.getBoundingClientRect().bottom;});return c[0];}
"""

    private fun run(body: String) = PRELUDE + "\n;" + body

    private const val SEND_BODY = """
(function(text,doSend){
  var p=__prof(); var before=__asst(p); var n0=before.length; var len0=0;
  window.__nsBase=before.map(function(e){return {el:e,t:(e.innerText||e.textContent||'').trim()};});
  var old=__reply(p);
  if(old){var ob=p.b?(old.querySelector(p.b)||old):old;len0=((ob.innerText||ob.textContent||'').trim().length);}
  if(!len0&&n0){var old2=before[n0-1];var ob2=p.b?(old2.querySelector(p.b)||old2):old2;len0=((ob2.innerText||ob2.textContent||'').trim().length);}
  var box=__box();
  if(!box) return 'nobox';
  box.focus();
  var snap=__snap(box);
  window.__nsSentN=__nrm(text);
  try{var __bs=new WeakSet(),__bt=new Set(),__tw=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),__nn;
    while(__nn=__tw.nextNode()){__bs.add(__nn);var __x=__nrm(__nn.nodeValue);if(__x.length>=20)__bt.add(__x);}
    window.__nsBaseNodes=__bs;window.__nsBaseTexts=__bt;window.__nsSentAt=Date.now();}catch(e){window.__nsBaseNodes=null;window.__nsBaseTexts=null;}
  try{var me=__markEls();window.__nsBaseM=new Set(me);window.__nsBaseT=new Set(me.map(function(e){return __nrm(e.innerText||e.textContent);}));}catch(e){window.__nsBaseM=new Set();window.__nsBaseT=new Set();}
  function boxText(){return String((box.tagName==='TEXTAREA'||box.tagName==='INPUT')?box.value:(box.innerText||box.textContent)||'').trim();}
  if(box.tagName==='TEXTAREA'||box.tagName==='INPUT'){
    var proto=box.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
    var setter=Object.getOwnPropertyDescriptor(proto,'value').set;
    setter.call(box,'');
    box.dispatchEvent(new Event('input',{bubbles:true}));
    setter.call(box,text);
    try{box.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));}catch(e){box.dispatchEvent(new Event('input',{bubbles:true}));}
    box.dispatchEvent(new Event('change',{bubbles:true}));
  } else {
    // Same as the Novel Translator extension: one direct textContent write + one input event.
    // document.execCommand('insertText') on a long chapter takes SECONDS in ProseMirror/Quill
    // editors; textContent is instant. DeepSeek's editor is the only one that needs execCommand.
    var done=false;
    if(/deepseek/i.test(location.hostname)){
      try{
        var sel=window.getSelection(),range=document.createRange();
        range.selectNodeContents(box); sel.removeAllRanges(); sel.addRange(range);
        done=document.execCommand('insertText',false,text)&&boxText().length>=Math.min(text.length,20);
      }catch(e){done=false;}
    }
    if(!done) box.textContent=text;
    try{box.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text.length>1000?null:text}));}
    catch(e){box.dispatchEvent(new Event('input',{bubbles:true}));}
  }
  if(doSend){
    // Do not press Send until the editor has committed the full text and the control is enabled.
    // The first readiness check happens 300 ms after insertion; retries only re-check readiness,
    // they never submit more than once.
    window.__nsSendState='pending';
    var tailN=__nrm(text).slice(-60);
    function occ(){var a=__nrm(document.body.innerText||''),c=0,i=-1;while((i=a.indexOf(tailN,i+1))>=0)c++;return c;}
    var baseOcc=occ();
    var expected=__nrm(text);
    function boxText(){return String((box.tagName==='TEXTAREA'||box.tagName==='INPUT')?box.value:(box.innerText||box.textContent)||'').trim();}
    function composerMatches(){return box.isConnected&&__nrm(boxText())===expected;}
    function sentOk(){
      var r=boxText();
      return (!r||r.length<Math.min(text.trim().length,20)) || (tailN.length>0&&occ()>baseOcc);
    }
    function keyEnter(){box.focus();var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};box.dispatchEvent(new KeyboardEvent('keydown',o));box.dispatchEvent(new KeyboardEvent('keypress',o));box.dispatchEvent(new KeyboardEvent('keyup',o));}
    var started=Date.now();
    function confirmSend(method){
      setTimeout(function(){
        if(sentOk()){
          window.__nsSendState='sent:'+method;
        }else{
          // Never retry a possibly successful submit: duplicate chapter prompts are worse than a clear failure.
          window.__nsSendState='failed';
        }
      },1800);
    }
    function waitUntilReady(){
      if(!box.isConnected){
        window.__nsSendState='failed';
        return;
      }
      var matches=composerMatches();
      var btn=matches?__findSend(p,box,snap):null;
      if(matches&&btn&&__ok(btn)){
        __press(btn);
        confirmSend('btn');
        return;
      }
      if(Date.now()-started<1800){
        setTimeout(waitUntilReady,120);
        return;
      }
      // Compatibility fallback for chatbots that do not expose a detectable Send button.
      // Only use Enter after verifying the exact composer text; never do both.
      if(matches){
        keyEnter();
        confirmSend('enter');
      }else{
        window.__nsSendState='failed';
      }
    }
    setTimeout(waitUntilReady,300);
  }
