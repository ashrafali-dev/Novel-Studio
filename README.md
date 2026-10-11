# Novel Studio

**Novel Studio হলো একটি Android app, যা novel পড়ার সময় chapter-এর লেখা বের করে ChatGPT, Gemini, Claude, DeepSeek বা Grok-এর web chatbot দিয়ে বাংলা অনুবাদ করায় এবং সেই অনুবাদ সরাসরি novel page-এ বসিয়ে দেয়।**

**সহজভাবে: Novel পড়ুন → Chapter Extract করুন → AI দিয়ে বাংলা Translate করুন → Translation Novel-এ দেখুন → Next Chapter-এ যান।**

আলাদা AI API key না থাকলেও নিজের AI chatbot account দিয়ে এই workflow ব্যবহার করা যায়। AI chatbot-এ আগে login থাকতে হবে; Novel Studio নিজে কোনো API key দেয় না।

## কী কী করতে পারে

### 📖 Novel Reader
- WebView-এর মাধ্যমে novel website-এ সরাসরি পড়া
- Novel / Chatbot / Split — তিনটি screen mode
- Split Mode-এ একই সঙ্গে novel ও chatbot ব্যবহার
- Next / Previous chapter navigation
- Browser history
- Bookmark এবং bookmark list
- Dark Mode
- Font/reading related settings

### ⚡ Chapter Extraction
- এক চাপেই current chapter extract
- Fast DOM-based extraction
- বিভিন্ন site-এর জন্য আলাদা extraction handling
- WebNovel-এর জন্য dedicated chapter/navigation handling
- JavaScript-heavy page-এর জন্য compatible extraction path
- Chapter পরিবর্তনের পর automatic extraction polling
- Current chapter-এর stale text ব্যবহার না করে নতুন chapter থেকে extraction

### 🤖 AI Chatbot Translation
Built-in chatbot options:
- ChatGPT
- Gemini
- Claude
- DeepSeek
- Grok

মূল workflow:

**Novel → Extract Chapter → AI Chatbot → বাংলা Translation → Novel Page-এ Insert**

Translation শেষ হলে প্রয়োজনীয় translated text novel page-এ বসানোর চেষ্টা করে। Insert করা না গেলে translation Library-তে backup হিসেবে সংরক্ষণ করা হয়।

### 🔄 Auto Translation Mode
Auto mode চালু থাকলে:
1. Next / Previous chapter চাপলে নতুন chapter খোলে
2. নতুন chapter automatically extract হয়
3. chatbot-এ পাঠানো হয়
4. translation শেষ হলে novel page-এ বসে যায়
5. progress indicator দিয়ে কাজের অবস্থা দেখা যায়

আগে save করা translation থাকলে সেটি ব্যবহার করে chapter-এ দ্রুত translation বসানো যায়।

### 📚 Offline Translation Library
- অনুবাদ করা chapter locally save
- Novel অনুযায়ী chapter list
- Saved translation আবার দেখা
- Novel rename করা
- পুরো novel delete করা
- একটি novel অথবা সব translation TXT হিসেবে export
- Translation insert করতে সমস্যা হলেও Library-তে backup থাকে

### 📖 Glossary
নিজের glossary তৈরি ও ব্যবহার করা যায়।

- Glossary entry add/edit
- Bulk glossary add
- Glossary enable/disable
- Chapter-এ থাকা glossary term শনাক্ত
- শনাক্ত term AI prompt-এর সঙ্গে পাঠানো
- পুরো glossary copy করা
- Glossary delete করা

এতে character name, title এবং নির্দিষ্ট terminology বারবার একইভাবে translate করানো যায়।

### 🔖 Bookmarks
- Current novel page bookmark করা
- Bookmark list থেকে আবার chapter/page খোলা
- Long-press করে bookmark delete

### 🌐 Novel Sites
Built-in site shortcuts-এর মধ্যে বর্তমানে রয়েছে:
- WebNovel
- NovelBin

এছাড়াও WebView-এর মাধ্যমে অন্যান্য supported novel site ব্যবহার করা যায় এবং site-specific settings/extraction handling রয়েছে।

### 🛠️ Reader Workflow Tools
- Instant Extract button
- Next / Previous chapter controls
- Translation progress indicator
- Original ↔ translated text toggle
- Prompt editor
- Auto-extract after Next / Previous toggle
- Site Settings
- Ad blocking
- Browser history
- Translation export

## মূল উদ্দেশ্য

Novel Studio এমন readers-এর জন্য তৈরি যারা novel পড়েন কিন্তু আলাদা AI API key নেই বা API-এর খরচ দিতে চান না।

এটি novel reader এবং AI chatbot-কে একই workflow-এর মধ্যে এনে:

**Novel পড়া → Chapter Extract → AI Translation → বাংলা Translation Insert → Next Chapter**

এই পুরো কাজটি সহজ করে।

## APK Build

GitHub repository-তে গিয়ে:

**Actions → Build APK → Artifacts → app-debug.apk**

নতুন APK ইনস্টল করার আগে চাইলে:

**☰ → Export All Translations (.txt)**

দিয়ে saved translation-এর backup রাখা যায়।
