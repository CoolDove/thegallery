package com.dove.thegallery;

import java.util.List;

/**
 * The page served by Gallery sharing: a thumbnail grid that opens each image full size, so the
 * receiving device can pick the pictures it wants instead of taking one ZIP of everything.
 * Image bytes are served separately at /i/&lt;index&gt; and /t/&lt;index&gt;.
 */
final class SharePage {

    /** One tile's caption; the bytes and their URLs are owned by the server. */
    static final class Item {
        final String name;
        final String format;
        final String date;

        Item(String name, String format, String date) {
            this.name = name;
            this.format = format;
            this.date = date;
        }
    }

    private SharePage() { }

    static String html(List<Item> items) {
        StringBuilder page = new StringBuilder(4096);
        page.append("<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">\n")
                .append("<title>The Gallery</title>\n<style>\n").append(STYLE).append("</style>\n</head>\n<body>\n")
                .append("<header><h1>The Gallery</h1><p>").append(items.size())
                .append(items.size() == 1 ? " image" : " images")
                .append(" · tap one to view it full size</p></header>\n<main>\n");
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            page.append("<figure>\n<button class=\"thumb\" data-open=\"").append(index)
                    .append("\" aria-label=\"Open\"><img src=\"t/").append(index)
                    .append("\" alt=\"\" loading=\"lazy\"></button>\n<figcaption>")
                    .append("<span class=\"name\">").append(escape(item.name)).append("</span>")
                    .append("<span class=\"meta\">").append(escape(item.format)).append(" · ")
                    .append(escape(item.date)).append("</span>")
                    .append("<span class=\"acts\"><button class=\"copy\" data-copy=\"").append(index)
                    .append("\">Copy</button><a href=\"i/").append(index)
                    .append("\" target=\"_blank\" rel=\"noopener\">Open</a></span>")
                    .append("</figcaption>\n</figure>\n");
        }
        page.append("</main>\n").append(VIEWER)
                .append("<div id=\"toast\"></div>\n<script>\n").append(SCRIPT)
                .append("</script>\n</body>\n</html>\n");
        return page.toString();
    }

    private static String escape(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }

    private static final String STYLE = """
            *{box-sizing:border-box}
            html,body{margin:0;min-height:100%}
            body{background:#13151b;color:#f4f5f8;font:15px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;-webkit-tap-highlight-color:transparent}
            header{display:flex;align-items:baseline;gap:10px;flex-wrap:wrap;padding:22px 22px 0}
            h1{margin:0;font-size:20px;font-weight:600;letter-spacing:.01em}
            header p{margin:0;color:#9aa0ad;font-size:13px}
            main{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:14px;padding:16px 22px 48px}
            figure{margin:0;background:#1b1e27;border:1px solid #2a2e3a;border-radius:14px;overflow:hidden}
            .thumb{display:block;width:100%;padding:0;border:0;background:#0e1015;cursor:zoom-in}
            .thumb img{display:block;width:100%;aspect-ratio:16/10;object-fit:cover}
            figcaption{padding:9px 11px 11px}
            .name{display:block;font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
            .meta{display:block;color:#8b909c;font-size:11.5px;margin-top:1px}
            .acts{display:flex;gap:8px;margin-top:9px}
            .acts button,.acts a{flex:1;text-align:center;color:#e7e9ef;background:#252a36;border:1px solid #333a49;border-radius:8px;padding:5px 0;font:inherit;font-size:12px;text-decoration:none;cursor:pointer}
            #viewer{position:fixed;inset:0;z-index:5;display:none;flex-direction:column;background:#0a0b0f}
            #viewer.open{display:flex}
            .bar{display:flex;align-items:center;gap:8px;padding:10px 14px;font-size:13px}
            .bar .title{flex:1;min-width:0;color:#c3c7d1;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
            .bar a,.bar button{color:#fff;background:#252a36;border:1px solid #333a49;border-radius:8px;padding:6px 11px;font:inherit;text-decoration:none;cursor:pointer}
            .stage{flex:1;min-height:0;display:flex;align-items:center;justify-content:center;padding:0 6px 14px}
            .stage img{max-width:100%;max-height:100%;object-fit:contain}
            .nav{position:absolute;top:50%;transform:translateY(-50%);width:44px;height:58px;border:0;border-radius:12px;background:#0008;color:#fff;font-size:24px;cursor:pointer}
            #prev{left:8px}
            #next{right:8px}
            #toast{position:fixed;left:50%;bottom:26px;transform:translateX(-50%);z-index:9;background:#2b3140;color:#fff;padding:9px 16px;border-radius:20px;font-size:13px;opacity:0;transition:opacity .18s;pointer-events:none}
            #toast.show{opacity:1}
            """;

    private static final String VIEWER = """
            <div id="viewer">
            <div class="bar"><span class="title"></span><a class="open" target="_blank" rel="noopener">Open</a><button class="copy">Copy</button><button class="close">Close</button></div>
            <div class="stage"><img alt=""></div>
            <button class="nav" id="prev" aria-label="Previous">‹</button>
            <button class="nav" id="next" aria-label="Next">›</button>
            </div>
            """;

    /**
     * Copying an image needs a secure context, so a plain LAN address falls back to opening the
     * original full-size image, where the mobile browser's own long-press copy works.
     */
    private static final String SCRIPT = """
            const viewer = document.getElementById('viewer');
            const stage = viewer.querySelector('.stage img');
            const title = viewer.querySelector('.title');
            const openLink = viewer.querySelector('.open');
            const toastBox = document.getElementById('toast');
            const NAMES = Array.from(document.querySelectorAll('figure .name')).map(n => n.textContent);
            const COUNT = NAMES.length;
            let current = 0;
            let toastTimer;

            function toast(text) {
              toastBox.textContent = text;
              toastBox.classList.add('show');
              clearTimeout(toastTimer);
              toastTimer = setTimeout(() => toastBox.classList.remove('show'), 1900);
            }

            function show(index) {
              current = (index + COUNT) % COUNT;
              stage.src = 'i/' + current;
              title.textContent = NAMES[current];
              openLink.href = 'i/' + current;
              viewer.classList.add('open');
              document.body.style.overflow = 'hidden';
            }

            function hide() {
              viewer.classList.remove('open');
              stage.removeAttribute('src');
              document.body.style.overflow = '';
            }

            async function copy(index) {
              const url = 'i/' + index;
              try {
                if (!navigator.clipboard || !window.ClipboardItem) throw new Error('no image clipboard');
                const blob = await (await fetch(url)).blob();
                const bitmap = await createImageBitmap(blob);
                const canvas = document.createElement('canvas');
                canvas.width = bitmap.width;
                canvas.height = bitmap.height;
                canvas.getContext('2d').drawImage(bitmap, 0, 0);
                const png = await new Promise((done, fail) => canvas.toBlob(
                  b => b ? done(b) : fail(new Error('encode')), 'image/png'));
                await navigator.clipboard.write([new ClipboardItem({ 'image/png': png })]);
                toast('Copied to the clipboard');
              } catch (error) {
                window.open(url, '_blank');
                toast('Long-press the image to copy it');
              }
            }

            document.addEventListener('click', event => {
              const tile = event.target.closest('[data-open]');
              if (tile) { show(Number(tile.dataset.open)); return; }
              const button = event.target.closest('[data-copy]');
              if (button) copy(Number(button.dataset.copy));
            });
            viewer.querySelector('.copy').addEventListener('click', () => copy(current));
            viewer.querySelector('.close').addEventListener('click', hide);
            document.getElementById('prev').addEventListener('click', () => show(current - 1));
            document.getElementById('next').addEventListener('click', () => show(current + 1));
            document.addEventListener('keydown', event => {
              if (!viewer.classList.contains('open')) return;
              if (event.key === 'Escape') hide();
              else if (event.key === 'ArrowLeft') show(current - 1);
              else if (event.key === 'ArrowRight') show(current + 1);
            });
            """;
}
