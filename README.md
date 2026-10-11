# Novel Studio

**Novel Studio is an Android app that extracts novel chapters, sends them to web-based AI chatbots such as ChatGPT, Gemini, Claude, DeepSeek, or Grok for translation, and inserts the translated text directly back into the novel page.**

**In simple terms: Read Novel → Extract Chapter → Translate with AI → Read the Translation → Go to the Next Chapter.**

You do not need a separate AI API key. You can use your own logged-in AI chatbot account through its web interface. Novel Studio does not provide or require its own AI API key.

## Features

### 📖 Novel Reader
- Read novel websites directly through WebView
- Novel / Chatbot / Split screen modes
- Read the novel and use the chatbot side-by-side in Split Mode
- Next / Previous chapter navigation
- Browser history
- Bookmarks and bookmark list
- Dark Mode
- Font and reading-related settings

### ⚡ Chapter Extraction
- Extract the current chapter with one tap
- Fast DOM-based extraction
- Site-specific extraction handling
- Dedicated WebNovel chapter/navigation handling
- Compatible extraction path for JavaScript-heavy pages
- Automatic extraction polling after chapter navigation
- Prevents stale chapter text from being reused when moving to a new chapter

### 🤖 AI Chatbot Translation
Built-in chatbot options:
- ChatGPT
- Gemini
- Claude
- DeepSeek
- Grok

Core workflow:

**Novel → Extract Chapter → AI Chatbot → Translation → Insert into Novel Page**

After translation, Novel Studio attempts to extract the translated response and insert the required text directly into the novel page. If insertion fails, the translation is saved to the offline Library as a backup.

### 🔄 Auto Translation Mode
When Auto Mode is enabled:
1. Press Next / Previous to open a new chapter
2. The new chapter is automatically extracted
3. The chapter is sent to the chatbot
4. The translated text is inserted into the novel page
5. A progress indicator shows the current process

Previously saved translations can also be reused for faster chapter translation.

### 📚 Offline Translation Library
- Save translated chapters locally
- Organize chapters by novel
- View saved translations
- Rename novels
- Delete an entire novel
- Export a single novel or all translations as TXT
- Keep a backup when translation insertion fails

### 📖 Glossary
Create and use your own translation glossary.

- Add/edit glossary entries
- Bulk glossary entry input
- Enable/disable glossary
- Detect glossary terms present in a chapter
- Send detected terms together with the AI translation prompt
- Copy the complete glossary
- Delete glossary entries

This helps keep character names, titles, and specific terminology consistent across chapters.

### 🔖 Bookmarks
- Bookmark the current novel page
- Open saved pages from the bookmark list
- Long-press to delete bookmarks

### 🌐 Novel Sites
Built-in site shortcuts currently include:
- WebNovel
- NovelBin

Other supported novel websites can also be used through WebView, with site-specific settings and extraction handling where available.

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

## Purpose

Novel Studio is designed for novel readers who want AI-assisted translation but do not have a separate AI API key or do not want to pay for API usage.

It brings the novel reader and AI chatbot into one workflow:

**Read Novel → Extract Chapter → AI Translation → Insert Translation → Next Chapter**

The goal is to make the entire reading and translation process possible inside a single Android app.

## APK Build

Go to the GitHub repository:

**Actions → Build APK → Artifacts → app-debug.apk**

Before installing a new APK, you can back up your saved translations using:

**☰ → Export All Translations (.txt)**
