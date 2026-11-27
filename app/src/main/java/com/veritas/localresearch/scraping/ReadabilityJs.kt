package com.veritas.localresearch.scraping

object ReadabilityJs {

    const val EXTRACT_ARTICLE_SCRIPT: String = """
        (function() {
            try {
                if (!document.body) {
                    return JSON.stringify({ title: document.title || '', text: '' });
                }
                var clone = document.body.cloneNode(true);
                var removeSelectors = [
                    'script', 'style', 'noscript', 'iframe', 'svg', 'canvas', 'nav', 'footer',
                    'header', 'aside', 'form', 'button', 'input', 'select', 'template',
                    '[hidden]', '[aria-hidden="true"]',
                    '[role="navigation"]', '[role="banner"]', '[role="complementary"]',
                    '[aria-label*="cookie" i]', '[id*="cookie" i]', '[class*="cookie" i]',
                    '[class*="consent" i]', '[class*="banner" i]', '[class*="sidebar" i]',
                    '[class*="advert" i]', '[class*="promo" i]', '[class*="newsletter" i]',
                    '[class*="share" i]', '[class*="comment" i]', '[class*="menu" i]'
                ];
                removeSelectors.forEach(function(sel) {
                    clone.querySelectorAll(sel).forEach(function(el) { el.remove(); });
                });

                function cleanText(el) {
                    return (el.textContent || '').replace(/\s+/g, ' ').trim();
                }

                // Haupt-Container mit der hoechsten Textdichte (Readability-Heuristik) finden
                var candidates = clone.querySelectorAll('article, main, [role="main"], section, div');
                var bestEl = clone;
                var bestScore = 0;

                candidates.forEach(function(el) {
                    var paragraphs = el.querySelectorAll('p');
                    if (paragraphs.length < 2) return;
                    var textLen = 0;
                    var linkLen = 0;
                    paragraphs.forEach(function(p) { textLen += cleanText(p).length; });
                    el.querySelectorAll('a').forEach(function(a) { linkLen += cleanText(a).length; });
                    if (textLen < 250) return;
                    var linkDensity = linkLen / Math.max(1, textLen);
                    if (linkDensity > 0.45) return;
                    var score = textLen * (1 - linkDensity) + paragraphs.length * 40;
                    if (score > bestScore) {
                        bestScore = score;
                        bestEl = el;
                    }
                });

                var blockSelector = 'h1, h2, h3, p, li, td';
                var blocks = [];
                var seen = Object.create(null);
                bestEl.querySelectorAll(blockSelector).forEach(function(node) {
                    // Verschachtelte Container (z. B. <li><p>Text</p></li>) nicht doppelt aufnehmen
                    if (node.querySelector(blockSelector)) return;
                    var txt = cleanText(node);
                    if (txt.length >= 25 && !seen[txt]) {
                        seen[txt] = true;
                        blocks.push(txt);
                    }
                });

                var finalText = blocks.length > 0 ? blocks.join('\n') : cleanText(bestEl);
                finalText = finalText.replace(/\n{3,}/g, '\n\n').trim().slice(0, 14000);

                return JSON.stringify({
                    title: (document.title || '').trim(),
                    text: finalText
                });
            } catch (e) {
                return JSON.stringify({ title: document.title || '', text: '' });
            }
        })();
    """

    const val EXTRACT_DDG_RESULTS_SCRIPT: String = """
        (function() {
            var out = [];
            document.querySelectorAll('.result, .results_links').forEach(function(el) {
                var a = el.querySelector('a.result__a');
                var sn = el.querySelector('.result__snippet');
                if (a && a.href) {
                    var href = a.href;
                    try {
                        var u = new URL(href);
                        var uddg = u.searchParams.get('uddg');
                        if (uddg) href = uddg;
                    } catch(e) {}
                    if (href.startsWith('http')) {
                        out.push({
                            title: (a.textContent || '').replace(/\s+/g, ' ').trim(),
                            url: href,
                            snippet: sn ? (sn.textContent || '').replace(/\s+/g, ' ').trim() : ''
                        });
                    }
                }
            });
            return JSON.stringify(out.slice(0, 8));
        })();
    """

    const val EXTRACT_BRAVE_RESULTS_SCRIPT: String = """
        (function() {
            var out = [];
            document.querySelectorAll('#results .snippet, div[data-type="web"]').forEach(function(el) {
                var a = el.querySelector('a[href^="http"]');
                var desc = el.querySelector('.snippet-description, .description');
                if (a && a.href && !a.href.includes('search.brave.com')) {
                    out.push({
                        title: (a.textContent || '').replace(/\s+/g, ' ').trim(),
                        url: a.href,
                        snippet: desc ? (desc.textContent || '').replace(/\s+/g, ' ').trim() : ''
                    });
                }
            });
            return JSON.stringify(out.slice(0, 8));
        })();
    """

    const val EXTRACT_MOJEEK_RESULTS_SCRIPT: String = """
        (function() {
            var out = [];
            document.querySelectorAll('ul.results-standard li').forEach(function(el) {
                var a = el.querySelector('a.title');
                var p = el.querySelector('p.s');
                if (a && a.href) {
                    out.push({
                        title: (a.textContent || '').replace(/\s+/g, ' ').trim(),
                        url: a.href,
                        snippet: p ? (p.textContent || '').replace(/\s+/g, ' ').trim() : ''
                    });
                }
            });
            return JSON.stringify(out.slice(0, 8));
        })();
    """
}
