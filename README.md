# VeritasLocal

<a href="https://buymeacoffee.com/regard2462"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" height="40"></a>

Deep research on your own device, for Android, Windows and Linux. A small local language model (Qwen3 0.6B, 1.7B or 4B, GGUF) runs through `llama.cpp`, searches the web without a backend and answers only from sources it can quote.

## Pipeline

1. **Query plan:** the model splits the question into 2 to 3 search queries (JSON enforced by a GBNF grammar).
2. **Search and scraping:** search queries and pages are fetched in parallel over HTTP and cleaned with Jsoup. The search engines (DuckDuckGo, Bing, Startpage, Mojeek, Marginalia, your own SearXNG instance) can be switched on and off in the settings. They are asked in turn, each within its own budget per minute, and an engine that throttles or shows a captcha pauses for 15 minutes or longer. PDFs (studies, reports) are read with PdfBox (PdfBox-Android on Android), up to 40 pages. On Android a hidden WebView is used only for pages that need JavaScript; the desktop version skips such pages. `robots.txt` is respected, and blocked or rate limited pages are skipped. Wikipedia and arXiv come through their APIs at the same time. Search, sources and answer stay in the language of the question: the search engines get a region or language parameter, pages in another language are dropped, and English Wikipedia and arXiv are only used for English questions.
3. **Hybrid retrieval:** pages become chunks of about 500 characters. An in-memory BM25 keyword index (the system SQLite of many devices has no FTS5) and an ONNX embedding are merged with reciprocal rank fusion. The default embedding is the multilingual `multilingual-e5-small` with its own SentencePiece Unigram tokenizer in Kotlin (checked token by token against the Hugging Face `tokenizers` library); `all-MiniLM-L6-v2` remains as a small English-only option. Claims supported by several domains rank higher. A multilingual cross-encoder (`mmarco-mMiniLMv2-L12-H384`, ONNX int8) then scores question and chunk together and reorders the candidates of every sub-question. Depending on the depth, 8 to 21 chunks reach the model, at most 2 or 3 per domain.
4. **Quote first extraction:** the model returns claims with a verbatim quote and chunk id (GBNF JSON, temperature 0). Then it writes the answer from the checked claims only. From the standard depth on, it first builds an outline that assigns every checked claim to a section and then writes one section at a time from its claims (following the outline step of STORM).
5. **Deterministic checks in Kotlin:** every quote is matched against its chunk (or any other loaded chunk, since small models mix up chunk ids). The claim itself must use the words of its source, so translated or reinterpreted claims and terms smuggled in from the question are rejected. If the model delivers too few facts, the best matching source sentences are added verbatim, scored by the reranker. In the written answer every sentence must rest on a cited fact, otherwise it is removed; copied or invented quotes are removed too, and so are garbled paraphrases (a source word with a stray ending or a one-letter typo, two new compounds built from source words, or the same four words twice in one sentence) and copied sentence tails that start in the middle of a source sentence. A sentence that strays too far from the wording of its cited source is replaced by the source sentence itself (strict for models up to about 2B parameters, looser from 3B on), and so is a sentence that opens a section with "Sie", "Es" or "This" and has nothing to refer to. Short lines without punctuation count as headings. For German questions a fact only counts if its page names the main topic of the question, so a page that only shares a side word ("rechtliche Hürden") is dropped. Line breaks inside PDF sentences are joined (a line counts as wrapped if it nearly fills the column or ends on an article or conjunction), headings, imprint lines, literature references, service offers and lines that address the reader ("Ihr Betrieb") are no facts, a quote that spans a heading or list keeps only the line the claim rests on, a quote that opens with "Es" or "Sie" carries the sentence before it (and the claim may only name what that sentence names), a half sentence with "zwar" but no "aber" is replaced by the full source sentence, chunks are only split at sentence ends or real line ends, and sentence fragments or sentences cut off at the end of a chunk are rejected. Claims are checked for a second domain, and every number, date and amount in the answer must appear in the cited chunks. Unsupported numbers are highlighted in yellow. Tapping `[1]` opens the original passage with the quote marked. The answer can be selected and copied, or copied with its source list.
6. **Answer length:** Short, Medium or Detailed. Detailed works like the outline step of STORM: the model names subtopics of the question, the section headings of matching Wikipedia articles add more, and a second search round covers them. More pages and facts are used, and the answer is structured like a paper (concept, one section per subtopic, influencing factors and obstacles, open questions). The rules of step 5 still apply to every sentence.
7. **Export:** PDF, DOCX and ODT with title page, table of contents, sections and reference list. Citation styles: APA 7, Harvard, Chicago author-date, IEEE and DIN ISO 690 (numeric). Authors, dates and site names come from the meta tags of the pages (Highwire `citation_*`, Dublin Core, Open Graph, schema.org JSON-LD, the alt text of the site logo), from the document info of PDFs and from the Wikipedia and arXiv APIs. If a page or PDF names no site, the home page of its domain is read once for the name; missing years are written as "o. J." or "n.d.". In DOCX the table of contents is a real TOC field with linked entries (Word offers to update the page numbers when the file is opened), in ODT a LibreOffice index, in the PDF it has page numbers and jump links plus bookmarks. Citations link to their reference entry, web addresses are clickable.

## Build

Requires Android Studio or the Android SDK with NDK 27 or newer and CMake 3.22.1.

```bash
git clone --depth 1 https://github.com/ggml-org/llama.cpp.git app/src/main/cpp/third_party/llama.cpp
gradle assembleRelease
```

Use Gradle 8.11 or newer (the project has no wrapper files).

The release build is signed with the debug key so that it installs directly. It targets `arm64-v8a`, minSdk 28. `llama.cpp` is built with all ARM CPU variants (dotprod, fp16, i8mm, SVE, SME), and the fastest one the device supports is loaded at runtime.

### Windows and Linux

The module `desktop` shares the code of the app (search, checks, export, screens) and runs it with Compose for Desktop. Small stand-ins for the few Android classes it uses live in `desktop/src/main/kotlin`. `llama.cpp` is built separately per system and placed in `desktop/native-libs/windows-x64` or `desktop/native-libs/linux-x64` (all x86 CPU variants, the fastest is picked at runtime):

```bash
cmake -S app/src/main/cpp -B build-native -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON -DGGML_NATIVE=OFF   -DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DGGML_OPENMP=OFF -DLLAMA_CURL=OFF   -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_BUILD_TOOLS=OFF
cmake --build build-native -j
gradle :desktop:createDistributable   # program folder, plus :desktop:packageMsi on Windows or :desktop:packageDeb on Linux
```

On Windows use MSVC with the shared runtime (the default); the runtime DLLs come with the bundled Java runtime. Data and models are stored in `%LOCALAPPDATA%\VeritasLocal` or `~/.local/share/VeritasLocal`.

On first start the model manager downloads the models. Devices with 8 GB RAM or more should use Qwen3 4B, others Qwen3 1.7B. Qwen3 0.6B also works but writes clumsier text. A custom GGUF file (for example Gemma) can be imported.

## Status

Builds without errors. The full pipeline (search, PDF, reranker, extraction, verification, outline and sections, export) ran with Qwen3 0.6B and 1.7B on an x86_64 Android emulator, in the Windows version and in the Linux version (WSL). Times for a complex question at depth 2 with detailed answer, CPU only: 6 to 7 minutes on a Windows desktop with either model, about 7 minutes on Linux with 0.6B, and 9 minutes (0.6B) to 23 minutes (1.7B) on the emulator. Fact extraction takes most of the time. A run on an arm64 device is not measured yet.

The search depth sets the budget: more search queries per sub-topic, more pages, up to 21 source chunks, several extraction passes and a longer structured answer. ONNX Runtime telemetry is removed from the manifest and switched off.

## License

AGPL-3.0-or-later. `llama.cpp` (MIT) is fetched separately.
