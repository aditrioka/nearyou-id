/*
 * mockup-measure.js — measurement-annex extractor for the dev/mockups boards.
 *
 * Injected into a copy of a board by mockup-measure.sh and executed in headless
 * Chrome. Walks one frame's DOM, records per-element geometry + computed styles,
 * resolves colors back to the board's CSS custom-property token NAMES, and emits
 * the result as JSON inside <pre id="__measure_out"> for --dump-dom extraction.
 *
 * Expects window.__MEASURE_TARGET to be set by the wrapper:
 *   "list"        → enumerate frames (number, title, status tag)
 *   "<frame-no>"  → measure that frame (e.g. "4", "4b", "19")
 */
(async () => {
  function emit(obj) {
    const pre = document.createElement('pre');
    pre.id = '__measure_out';
    pre.textContent = JSON.stringify(obj);
    // Styles were only needed for layout, which is already computed; dropping
    // them keeps the --dump-dom output small.
    document.querySelectorAll('style, link[rel="stylesheet"]').forEach((n) => n.remove());
    document.body.replaceChildren(pre);
  }

  try {
    try { await document.fonts.ready; } catch (e) { /* fall back to system metrics */ }

    const target = String(window.__MEASURE_TARGET || 'list');
    const boardName = (location.pathname.split('/').pop() || 'board');

    // --- frame inventory: each .frame's caption is the first <h3> after it ---
    const h3s = [...document.querySelectorAll('h3')];
    function captionFor(frameEl) {
      return h3s.find((h) => frameEl.compareDocumentPosition(h) & Node.DOCUMENT_POSITION_FOLLOWING) || null;
    }
    const frames = [...document.querySelectorAll('.frame')].map((el) => {
      const h = captionFor(el);
      const raw = h ? h.textContent.trim().replace(/\s+/g, ' ') : '';
      const m = raw.match(/^(\d+[a-z]?)\s*·\s*(.*)$/);
      const capBox = h ? h.parentElement : null;
      const statusTag = capBox ? capBox.querySelector('.tag') : null;
      return {
        el,
        h,
        no: m ? m[1] : null,
        title: m ? m[2] : raw,
        dark: el.classList.contains('dark'),
        statusTag: statusTag ? statusTag.textContent.trim() : null,
      };
    });

    if (target === 'list') {
      emit({
        board: boardName,
        frames: frames.map((f) => ({ no: f.no, title: f.title, dark: f.dark, status: f.statusTag })),
      });
      return;
    }

    const fr = frames.find((f) => f.no === target);
    if (!fr) {
      emit({ error: `frame "${target}" not found`, available: frames.map((f) => f.no) });
      return;
    }

    // --- token map: resolve every --custom-prop declared for .frame selectors ---
    const tokenNames = new Set();
    for (const ss of document.styleSheets) {
      let rules;
      try { rules = ss.cssRules; } catch (e) { continue; }
      for (const r of rules || []) {
        if (r.style && /\.frame/.test(r.selectorText || '')) {
          for (const p of r.style) if (p.startsWith('--')) tokenNames.add(p);
        }
      }
    }
    const frStyle = getComputedStyle(fr.el);
    const probe = document.createElement('div');
    fr.el.appendChild(probe);
    const rgbToToken = {}; // 'rgb(30, 79, 214)' -> ['--primary', ...]
    const tokenLegend = {}; // '--primary' -> '#1E4FD6'
    for (const name of tokenNames) {
      const value = frStyle.getPropertyValue(name).trim();
      if (!value) continue;
      probe.style.color = value;
      const rgb = getComputedStyle(probe).color;
      (rgbToToken[rgb] ||= []).push(name);
      tokenLegend[name] = value;
    }
    probe.remove();

    function toHex(rgb) {
      const m = rgb.match(/rgba?\(([\d.]+),\s*([\d.]+),\s*([\d.]+)(?:,\s*([\d.]+))?\)/);
      if (!m) return rgb;
      const hex = [m[1], m[2], m[3]]
        .map((c) => Math.round(+c).toString(16).padStart(2, '0').toUpperCase())
        .join('');
      const a = m[4] !== undefined ? +m[4] : 1;
      return a === 1 ? `#${hex}` : `#${hex} @${a}`;
    }
    function tok(rgb) {
      const names = rgbToToken[rgb];
      return names ? `var(${names[0]})` : toHex(rgb);
    }

    // --- element walk ---
    const frameBox = fr.el.getBoundingClientRect();
    const round = (v) => Math.round(v * 2) / 2;
    function directText(el) {
      let t = '';
      for (const n of el.childNodes) if (n.nodeType === Node.TEXT_NODE) t += n.textContent;
      return t.replace(/\s+/g, ' ').trim();
    }
    function labelOf(el) {
      const cls = [...el.classList].join('.');
      return el.tagName.toLowerCase() + (cls ? '.' + cls : '');
    }
    function depthOf(el) {
      let d = 0;
      for (let p = el.parentElement; p && p !== fr.el; p = p.parentElement) d++;
      return d;
    }

    const elements = [];
    for (const el of fr.el.querySelectorAll('*')) {
      if (el.tagName === 'SCRIPT' || el.tagName === 'STYLE') continue;
      if (el.ownerSVGElement) continue; // svg internals: keep only the <svg> root
      const r = el.getBoundingClientRect();
      if (r.width < 0.5 && r.height < 0.5) continue;
      const cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden') continue;

      const rec = {
        d: depthOf(el),
        el: labelOf(el),
        box: { x: round(r.left - frameBox.left), y: round(r.top - frameBox.top), w: round(r.width), h: round(r.height) },
      };
      const text = directText(el);
      if (text) rec.text = text.length > 60 ? text.slice(0, 57) + '…' : text;

      // gap to previous visible sibling along the parent's main axis
      let prev = el.previousElementSibling;
      while (prev && getComputedStyle(prev).display === 'none') prev = prev.previousElementSibling;
      if (prev) {
        const pr = prev.getBoundingClientRect();
        const pcs = getComputedStyle(el.parentElement);
        const horizontal = pcs.display.includes('flex') && pcs.flexDirection.startsWith('row');
        const gap = horizontal ? r.left - pr.right : r.top - pr.bottom;
        if (isFinite(gap)) rec.gapPrev = round(gap);
      }

      const s = {};
      const pad = [cs.paddingTop, cs.paddingRight, cs.paddingBottom, cs.paddingLeft];
      if (pad.some((p) => p !== '0px')) s.pad = pad.join(' ');
      if (cs.display.includes('flex') || cs.display.includes('grid')) {
        s.layout = cs.display + (cs.display.includes('flex') ? '/' + cs.flexDirection : '');
        if (cs.gap && cs.gap !== 'normal' && cs.gap !== '0px' && cs.gap !== 'normal normal') s.gap = cs.gap;
        if (cs.alignItems !== 'normal' && cs.alignItems !== 'stretch') s.align = cs.alignItems;
        if (cs.justifyContent !== 'normal' && cs.justifyContent !== 'flex-start') s.justify = cs.justifyContent;
      }
      if (cs.borderRadius !== '0px') s.radius = cs.borderRadius;
      if (text) {
        s.font = `${cs.fontSize}/${cs.lineHeight} w${cs.fontWeight}`;
        if (cs.letterSpacing !== 'normal') s.tracking = cs.letterSpacing;
        s.fg = tok(cs.color);
      } else if (el.classList.contains('ms') || el.tagName === 'SVG') {
        s.fg = tok(cs.color);
      }
      if (cs.backgroundColor !== 'rgba(0, 0, 0, 0)') s.bg = tok(cs.backgroundColor);
      if (cs.backgroundImage !== 'none') s.bgImage = cs.backgroundImage.length > 90 ? cs.backgroundImage.slice(0, 87) + '…' : cs.backgroundImage;
      if (cs.borderTopWidth !== '0px' && cs.borderTopStyle !== 'none') {
        s.border = `${cs.borderTopWidth} ${cs.borderTopStyle} ${tok(cs.borderTopColor)}`;
      }
      if (cs.boxShadow !== 'none') s.shadow = cs.boxShadow.length > 90 ? cs.boxShadow.slice(0, 87) + '…' : cs.boxShadow;
      if (cs.animationName !== 'none') {
        s.anim = `${cs.animationName} ${cs.animationDuration} ${cs.animationTimingFunction} ${cs.animationIterationCount}`;
      }
      // shimmer/sweep effects on the boards live on ::before/::after — capture those too
      for (const pseudo of ['::before', '::after']) {
        const ps = getComputedStyle(el, pseudo);
        if (ps.animationName !== 'none') {
          s['anim' + (pseudo === '::before' ? 'Before' : 'After')] =
            `${ps.animationName} ${ps.animationDuration} ${ps.animationTimingFunction} ${ps.animationIterationCount}`;
        }
      }
      if (cs.opacity !== '1') s.opacity = cs.opacity;
      if (cs.position !== 'static') s.pos = cs.position;
      if (Object.keys(s).length) rec.style = s;
      elements.push(rec);
    }

    // caption body: every <p> in the caption container, for grounding
    const capBox = fr.h ? fr.h.parentElement : null;
    const caption = capBox ? [...capBox.querySelectorAll('p')].map((p) => p.textContent.replace(/\s+/g, ' ').trim()) : [];

    // Mobile board frames carry a fake Android statusbar; admin frames a fake
    // browser chrome. Width alone is ambiguous (admin frame 4b is also 412px).
    const isMobileBoard = !!document.querySelector('.frame .statusbar');
    emit({
      board: boardName,
      frame: {
        no: fr.no,
        title: fr.title,
        dark: fr.dark,
        status: fr.statusTag,
        w: round(frameBox.width),
        h: round(frameBox.height),
      },
      units: isMobileBoard
        ? '1px == 1dp INTENT on the 4dp grid (frame is 412px ≈ 412dp); the M3 component named in the caption is the source of truth for its metrics (docs/11 §2.8)'
        : 'CSS px, desktop frame (admin board); fluid responsive contract per frame 4b governs real layout (docs/11 §3.6)',
      fonts: document.fonts ? document.fonts.status : 'unknown',
      caption,
      tokens: tokenLegend,
      elements,
    });
  } catch (e) {
    emit({ error: String(e), stack: e && e.stack ? String(e.stack).slice(0, 500) : null });
  }
})();
