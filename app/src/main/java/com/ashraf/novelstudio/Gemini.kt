package com.ashraf.novelstudio

import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebView

object Gemini {
    fun isGemini(url: String?): Boolean {
        val host = try { Uri.parse(url ?: "").host?.lowercase() ?: "" } catch (_: Exception) { "" }
        return host == "gemini.google.com" || host.endsWith(".gemini.google.com")
    }

    fun isInternalAuthUrl(uri: Uri): Boolean {
        val host = (uri.host ?: "").lowercase()
        return host == "accounts.google.com" ||
            host.endsWith(".accounts.google.com") ||
            host == "gemini.google.com" ||
            host.endsWith(".gemini.google.com")
    }

    fun send(webView: WebView, text: String, doSend: Boolean, callback: ValueCallback<String>?) {
        val quoted = org.json.JSONObject.quote(text)
        val js = """
(function(text,doSend){
  function visible(e){if(!e)return false;var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
  function all(root,selector,out){
    out=out||[];
    try{var a=root.querySelectorAll(selector);for(var i=0;i<a.length;i++)out.push(a[i]);}catch(e){}
    try{var nodes=root.querySelectorAll('*');for(var j=0;j<nodes.length;j++)if(nodes[j].shadowRoot)all(nodes[j].shadowRoot,selector,out);}catch(e){}
    return out;
  }
  function findBox(){
    var s=['rich-textarea .ql-editor','rich-textarea [contenteditable="true"]','div.ql-editor[contenteditable="true"]','[aria-label="Enter a prompt here"]','[contenteditable="true"][role="textbox"]','[contenteditable="true"]','textarea','[role="textbox"]'];
    var c=[];
    for(var i=0;i<s.length;i++){var a=all(document,s[i],[]);for(var j=0;j<a.length;j++)if(visible(a[j])&&!c.includes(a[j]))c.push(a[j]);}
    c.sort(function(a,b){return b.getBoundingClientRect().height-a.getBoundingClientRect().height;});
    return c[0]||null;
  }
  function nativeSet(e,v){
    try{var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(p,'value').set.call(e,v);}catch(x){e.value=v;}
    e.dispatchEvent(new Event('input',{bubbles:true}));try{e.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:v}));}catch(x){}e.dispatchEvent(new Event('change',{bubbles:true}));
  }
  var box=findBox();if(!box)return 'nobox';
  if(box.tagName==='TEXTAREA'||box.tagName==='INPUT'){nativeSet(box,text);}
  else{
    try{
      box.focus();var sel=window.getSelection(),range=document.createRange();range.selectNodeContents(box);sel.removeAllRanges();sel.addRange(range);
      var ok=document.execCommand('insertText',false,text);
      if(!ok||!((box.innerText||box.textContent||'').trim())){
        var safe=String(text).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
        box.innerHTML='<p>'+safe.replace(/\r?\n/g,'<br>')+'</p>';
      }
    }catch(e){box.textContent=text;}
    try{box.dispatchEvent(new InputEvent('beforeinput',{bubbles:true,cancelable:true,inputType:'insertText',data:text}));}catch(e){}
    try{box.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));}catch(e){}
    box.dispatchEvent(new Event('change',{bubbles:true}));
  }
  if(doSend)setTimeout(function(){
    var s=['button[aria-label*="Send" i]','button[aria-label*="Submit" i]','button.send-button','button[data-testid*="send" i]'],btn=null;
    for(var i=0;i<s.length&&!btn;i++){var a=all(document,s[i],[]);for(var j=0;j<a.length;j++){var b=a[j];if(visible(b)&&!b.disabled&&b.getAttribute('aria-disabled')!=='true'){btn=b;break;}}}
    if(btn){btn.click();return;}
    try{box.focus();box.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));box.dispatchEvent(new KeyboardEvent('keyup',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));}catch(e){}
  },120);
  return 'ok';
})(__TEXT__,__SEND__)
""".trimIndent().replace("__TEXT__", quoted).replace("__SEND__", doSend.toString())
        webView.evaluateJavascript(js, callback)
    }
}
