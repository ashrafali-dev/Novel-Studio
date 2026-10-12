package com.ashraf.novelstudio

import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject

internal class WtrLabExtractor {
    fun extract(webView: WebView, attempt: Int = 0, callback: (Chapter?) -> Unit) {
        val url = webView.url.orEmpty()
        if (url.isBlank()) { callback(null); return }

        val script = """
            (function(){
              try {
                var selectors = [
                  '#chapter-content',
                  '.chapter-tracker.active .chapter-body',
                  '.chapter-tracker.active #chapter-content',
                  '.chapter-body',
                  '.chapter-content',
                  '.reader-content',
                  '.reading-content',
                  '[class*="chapter-content"]',
                  '[id*="chapter-content"]'
                ];
                var root = null, used = '';
                for (var i=0; i<selectors.length; i++) {
                  try {
                    var candidate = document.querySelector(selectors[i]);
                    if (candidate && (candidate.innerText || candidate.textContent || '').trim().length >= 40) {
                      root = candidate; used = selectors[i]; break;
                    }
                  } catch(e) {}
                }
                if (!root) return JSON.stringify({ok:false, text:'', count:0});

                var clone = root.cloneNode(true);
                var remove = 'script,style,noscript,button,input,select,textarea,nav, .ad, .ads, .advertisement, .chapter-navigation, .next, .prev, [aria-hidden="true"]';
                try { clone.querySelectorAll(remove).forEach(function(el){el.remove();}); } catch(e) {}
                var text = (clone.innerText || clone.textContent || '')
                  .replace(/\r/g, '')
                  .replace(/[\t\u00a0]+/g, ' ')
                  .replace(/ *\n */g, '\n')
                  .replace(/\n{3,}/g, '\n\n')
                  .trim();

                var heading = '';
                try {
                  var h = clone.querySelector('h1,h2,h3,h4,[class*="title"]');
                  if (h) heading = (h.innerText || h.textContent || '').trim();
                } catch(e) {}
                if (!heading) heading = (text.split('\n').find(function(line){return line.trim().length > 2;}) || '').trim();

                var number = '';
                var counterNodes = Array.from(document.querySelectorAll('body *')).filter(function(el){
                  return el.children.length === 0 && /\bCh\.?\s*\d+\s*\/\s*\d+/i.test(el.textContent || '');
                });
                if (counterNodes.length) {
                  var cm = (counterNodes[0].textContent || '').match(/\bCh\.?\s*(\d+)\s*\/\s*(\d+)/i);
                  if (cm) number = cm[1];
                }
                if (!number) {
                  var nm = heading.match(/^\s*#?\s*(\d+)\b/);
                  if (nm) number = nm[1];
                }
                if (!number) {
                  var um = location.pathname.match(/\/chapter-(\d+)/i);
                  if (um) number = um[1];
                }

                var count = 0;
                try { count = clone.querySelectorAll('p,.wtr-line,[data-line],li,br').length; } catch(e) {}
                if (count < 1) count = text.split(/\n+/).filter(function(s){return s.trim().length > 0;}).length;

                var findLink = function(dir) {
                  var els = Array.from(document.querySelectorAll('a[href],button,[role="button"]'));
                  var re = dir === 'next' ? /^(next|next\s*›|next\s*»|›|»|→|next chapter)$/i : /^(prev|previous|‹|«|←|previous chapter|prev chapter)$/i;
                  for (var j=0; j<els.length; j++) {
                    var el = els[j], label = ((el.innerText || el.textContent || el.getAttribute('aria-label') || el.getAttribute('title') || '')+'').replace(/\s+/g,' ').trim();
                    if (!label || !re.test(label)) continue;
                    var r = el.getBoundingClientRect();
                    if (r.width > 0 && r.height > 0) return {href: el.href || '', selector: ''};
                  }
                  return {href:'', selector:''};
                };

                return JSON.stringify({
                  ok: text.length >= 40 && count > 0,
                  text: text,
                  count: count,
                  title: heading,
                  number: number,
                  novel: document.title || 'wtr-lab.com',
                  contentSel: used || '#chapter-content',
                  titleSel: '',
                  next: findLink('next'),
                  prev: findLink('prev')
                });
              } catch(e) {
                return JSON.stringify({ok:false,text:'',count:0,error:String(e)});
              }
            })()
        """.trimIndent()

        webView.evaluateJavascript(script) { raw ->
            val payload = decode(raw)
            val parsed = runCatching { JSONObject(payload) }.getOrNull()
            val body = parsed?.optString("text", "")?.trim().orEmpty()
            val count = parsed?.optInt("count", 0) ?: 0
            val ok = parsed?.optBoolean("ok", false) == true && body.length >= 40 && count > 0
            if (!ok) {
                if (attempt < 5) webView.postDelayed({ extract(webView, attempt + 1, callback) }, 220L)
                else callback(null)
                return@evaluateJavascript
            }

            val title = parsed?.optString("title", "")?.trim().orEmpty()
            val number = parsed?.optString("number", "")?.trim().orEmpty()
            val next = parsed?.optJSONObject("next")?.optString("href", "")?.takeIf { it.isNotBlank() }
                ?: WtrLabNavigator().nextFromUrl(url)
            val prev = parsed?.optJSONObject("prev")?.optString("href", "")?.takeIf { it.isNotBlank() }
                ?: WtrLabNavigator().prevFromUrl(url)

            val chapter = Chapter(
                title.ifBlank { "Chapter ${number.ifBlank { "?" }}" },
                body,
                next,
                prev,
                url,
                parsed?.optString("novel", "")?.trim().orEmpty().ifBlank { "wtr-lab.com" },
                number,
                parsed?.optString("contentSel", "#chapter-content").orEmpty().ifBlank { "#chapter-content" },
                parsed?.optString("titleSel", "").orEmpty(),
                parsed?.optJSONObject("next")?.optString("selector", "").orEmpty(),
                parsed?.optJSONObject("prev")?.optString("selector", "").orEmpty()
            )
            SiteProfiles.remember(webView.context, chapter)
            callback(chapter)
        }
    }

    private fun decode(raw: String?): String = try {
        if (raw == null || raw == "null") "" else JSONArray("[$raw]").getString(0)
    } catch (_: Exception) { "" }
}
