// Diagrams of a proposal: a graph of typed nodes drawn with one shape and icon per kind, and the
// same graph as a draw.io file. Pure: no DOM here, so it runs under `node --test`. `scene` returns
// plain objects; `toDom` turns them into SVG with createElementNS and textContent only, because
// the labels were written by an agent and must never be parsed as markup.

export const TYPES = {
  service: "Servicio",
  database: "Base de datos",
  file: "Fichero",
  config: "Configuración",
  agent: "Agente",
  person: "Persona",
  external: "Sistema externo",
  ci: "CI y pruebas",
  other: "Otro",
};
export const CHANGES = { new: "NUEVO", modified: "CAMBIA", removed: "SE QUITA" };

const W = 168, H = 68, GAP_X = 84, GAP_Y = 26, MARGIN = 14, LEGEND_H = 30;

/** Rank of each node: longest path from a source, ignoring the edges that close a cycle. */
function ranks(nodes, edges) {
  const out = new Map(nodes.map((n) => [n.id, []]));
  for (const e of edges) out.get(e.from).push(e.to);
  const state = new Map(), back = new Set();
  const visit = (id) => {
    state.set(id, 1);
    for (const to of out.get(id)) {
      if (state.get(to) === 1) back.add(id + ">" + to);
      else if (!state.has(to)) visit(to);
    }
    state.set(id, 2);
  };
  for (const n of nodes) if (!state.has(n.id)) visit(n.id);
  const rank = new Map(nodes.map((n) => [n.id, 0]));
  // The graph without back edges is acyclic: relax until stable (bounded by the node count).
  for (let i = 0; i < nodes.length; i++) {
    let moved = false;
    for (const e of edges) {
      if (back.has(e.from + ">" + e.to) || e.from === e.to) continue;
      if (rank.get(e.to) < rank.get(e.from) + 1) {
        rank.set(e.to, rank.get(e.from) + 1);
        moved = true;
      }
    }
    if (!moved) break;
  }
  return rank;
}

export function wrap(text, width = 17, lines = 3) {
  const out = [];
  let line = "";
  for (const word of String(text).split(/\s+/).filter(Boolean)) {
    const piece = word.length > width ? word.slice(0, width - 1) + "…" : word;
    if (line && (line + " " + piece).length > width) {
      out.push(line);
      line = piece;
    } else line = line ? line + " " + piece : piece;
  }
  if (line) out.push(line);
  if (out.length > lines) {
    out.length = lines;
    out[lines - 1] = out[lines - 1].replace(/.?$/, "…");
  }
  return out;
}

/** Positions for every node and edge. Left to right, one column per rank. */
export function layout(graph) {
  const nodes = (graph?.nodes || []).map((n) => ({ ...n }));
  const ids = new Set(nodes.map((n) => n.id));
  const edges = (graph?.edges || []).filter((e) => ids.has(e.from) && ids.has(e.to)).map((e) => ({ ...e }));
  const rank = ranks(nodes, edges);
  const columns = [];
  for (const n of nodes) (columns[rank.get(n.id)] ||= []).push(n);
  const placed = new Map();
  const preds = (id) => edges.filter((e) => e.to === id && placed.has(e.from)).map((e) => placed.get(e.from).y);
  columns.forEach((col) => {
    if (!col) return;
    // Nodes with predecessors sit near them: it keeps arrows short and mostly parallel.
    const keyed = col.map((n, i) => {
      const ys = preds(n.id);
      return { n, key: ys.length ? ys.reduce((a, b) => a + b, 0) / ys.length : i * (H + GAP_Y) };
    });
    keyed.sort((a, b) => a.key - b.key);
    keyed.forEach(({ n }, i) => {
      n.col = rank.get(n.id);
      placed.set(n.id, { x: 0, y: i * (H + GAP_Y) });
      n.row = i;
    });
  });
  const tallest = Math.max(1, ...columns.map((c) => (c ? c.length : 0)));
  const height = tallest * (H + GAP_Y) - GAP_Y;
  for (const n of nodes) {
    const size = columns[n.col].length * (H + GAP_Y) - GAP_Y;
    n.w = W;
    n.h = H;
    n.x = MARGIN + n.col * (W + GAP_X);
    n.y = MARGIN + (height - size) / 2 + n.row * (H + GAP_Y);
  }
  const at = new Map(nodes.map((n) => [n.id, n]));
  for (const e of edges) {
    const a = at.get(e.from), b = at.get(e.to);
    if (b.col > a.col) {
      const x1 = a.x + W, y1 = a.y + H / 2, x2 = b.x, y2 = b.y + H / 2, dx = (x2 - x1) / 2;
      e.path = `M${x1} ${y1} C${x1 + dx} ${y1} ${x2 - dx} ${y2} ${x2} ${y2}`;
      e.mid = [(x1 + x2) / 2, (y1 + y2) / 2];
    } else {
      // Same column or a way back: leave from the bottom, loop under, enter from the bottom.
      const x1 = a.x + W / 2, y1 = a.y + H, x2 = b.x + W / 2, y2 = b.y + H, low = Math.max(y1, y2) + 30;
      e.path = `M${x1} ${y1} C${x1} ${low} ${x2} ${low} ${x2} ${y2}`;
      e.mid = [(x1 + x2) / 2, low - 8];
    }
  }
  const cols = columns.length;
  return {
    nodes,
    edges,
    width: MARGIN * 2 + cols * W + (cols - 1) * GAP_X,
    height: MARGIN * 2 + height + 34,
  };
}

// Outline of each kind, so kinds differ in shape and not only in colour (DESIGN.md).
const body = {
  service: (x, y, w, h) => ({ tag: "rect", attrs: { x, y, width: w, height: h, rx: 9 } }),
  database: (x, y, w, h) => {
    const r = 9;
    return { tag: "path", attrs: { d: `M${x} ${y + r}a${w / 2} ${r} 0 0 1 ${w} 0v${h - 2 * r}a${w / 2} ${r} 0 0 1 ${-w} 0z M${x} ${y + r}a${w / 2} ${r} 0 0 0 ${w} 0` } };
  },
  file: (x, y, w, h) => ({ tag: "path", attrs: { d: `M${x} ${y}h${w - 18}l18 18v${h - 18}h${-w}z M${x + w - 18} ${y}v18h18` } }),
  config: (x, y, w, h) => ({ tag: "path", attrs: { d: `M${x + 16} ${y}h${w - 32}l16 ${h / 2}l-16 ${h / 2}h${-(w - 32)}l-16 ${-h / 2}z` } }),
  agent: (x, y, w, h) => ({ tag: "rect", attrs: { x, y, width: w, height: h, rx: h / 2 } }),
  person: (x, y, w, h) => ({ tag: "ellipse", attrs: { cx: x + w / 2, cy: y + h / 2, rx: w / 2, ry: h / 2 } }),
  external: (x, y, w, h) => ({ tag: "rect", attrs: { x, y, width: w, height: h, rx: 4, "stroke-dasharray": "6 4" } }),
  ci: (x, y, w, h) => ({ tag: "path", attrs: { d: `M${x} ${y}h${w}v${h}h${-w}z M${x + 7} ${y}v${h} M${x + w - 7} ${y}v${h}` } }),
  other: (x, y, w, h) => ({ tag: "rect", attrs: { x, y, width: w, height: h, rx: 3 } }),
};

// 20 by 20 glyphs, stroke only.
const glyph = {
  service: "M3 4h14v5H3z M3 11h14v5H3z M6 6.5h.01 M6 13.5h.01",
  database: "M4 5c0-1.7 12-1.7 12 0v10c0 1.7-12 1.7-12 0z M4 5c0 1.7 12 1.7 12 0 M4 10c0 1.7 12 1.7 12 0",
  file: "M5 2h7l4 4v12H5z M12 2v4h4",
  config: "M3 5h14 M3 10h14 M3 15h14 M7 3v4 M13 8v4 M9 13v4",
  agent: "M4 7h12v9H4z M10 7V3 M7.5 11h.01 M12.5 11h.01 M7.5 14h5",
  person: "M10 3a3 3 0 1 0 .01 0 M4 17c0-4 3-6 6-6s6 2 6 6",
  external: "M6 15a4 4 0 0 1 0-8 5 5 0 0 1 9 1 3.5 3.5 0 0 1 0 7z",
  ci: "M10 2a8 8 0 1 0 .01 0 M6 10l3 3 5-6",
  other: "M10 8a2 2 0 1 0 .01 0",
};

const kind = (t) => (t in TYPES ? t : "other");
const text = (x, y, content, cls, extra = {}) => ({ tag: "text", attrs: { x, y, class: cls, ...extra }, text: content });

/** The picture as plain objects: {tag, attrs, text?, children?}. */
export function scene(graph) {
  const g = layout(graph);
  const used = [...new Set(g.nodes.map((n) => kind(n.type)))];
  const children = [
    {
      tag: "defs",
      children: [
        {
          tag: "marker",
          attrs: { id: "dg-arrow", viewBox: "0 0 10 10", refX: 9, refY: 5, markerWidth: 7, markerHeight: 7, orient: "auto-start-reverse" },
          children: [{ tag: "path", attrs: { d: "M0 0L10 5L0 10z", class: "dg-arrow" } }],
        },
      ],
    },
  ];
  for (const e of g.edges) {
    children.push({ tag: "path", attrs: { d: e.path, class: "dg-edge", "marker-end": "url(#dg-arrow)" } });
    if (e.label) children.push(text(e.mid[0], e.mid[1] + 4, e.label, "dg-edge-label", { "text-anchor": "middle" }));
  }
  for (const n of g.nodes) {
    const t = kind(n.type), change = n.change in CHANGES ? n.change : "unchanged";
    const group = { tag: "g", attrs: { class: `dg-node dg-${t} dg-${change}`, "data-id": n.id }, children: [] };
    const shape = body[t](n.x, n.y, n.w, n.h);
    shape.attrs.class = "dg-body";
    group.children.push({ tag: "title", text: `${TYPES[t]}: ${n.label}${change in CHANGES ? " · " + CHANGES[change].toLowerCase() : ""}` });
    group.children.push(shape);
    group.children.push({ tag: "path", attrs: { d: glyph[t], class: "dg-glyph", transform: `translate(${n.x + 14} ${n.y + n.h / 2 - 10})` } });
    const lines = wrap(n.label);
    const first = n.y + n.h / 2 - ((lines.length - 1) * 7) + 4;
    lines.forEach((l, i) => group.children.push(text(n.x + 42, first + i * 14, l, "dg-label")));
    if (change in CHANGES) {
      group.children.push({ tag: "rect", attrs: { x: n.x + n.w - 62, y: n.y - 8, width: 58, height: 15, rx: 7, class: "dg-badge" } });
      group.children.push(text(n.x + n.w - 33, n.y + 3, CHANGES[change], "dg-badge-text", { "text-anchor": "middle" }));
    }
    children.push(group);
  }
  let lx = MARGIN;
  const ly = g.height - LEGEND_H / 2 + 2;
  for (const t of used) {
    children.push({ tag: "path", attrs: { d: glyph[t], class: `dg-glyph dg-legend-${t}`, transform: `translate(${lx} ${ly - 12}) scale(0.8)` } });
    children.push(text(lx + 20, ly + 3, TYPES[t], "dg-legend"));
    lx += 28 + TYPES[t].length * 6.4;
  }
  const width = Math.max(g.width, lx + MARGIN);
  return { tag: "svg", attrs: { viewBox: `0 0 ${width} ${g.height}`, width, height: g.height, class: "dg", role: "img", "aria-label": "Diagrama" }, children, width, height: g.height };
}

/** Builds the scene as DOM nodes. Text always goes in through textContent. */
export function toDom(doc, node) {
  const el = doc.createElementNS("http://www.w3.org/2000/svg", node.tag);
  for (const [k, v] of Object.entries(node.attrs || {})) el.setAttribute(k, String(v));
  if (node.text !== undefined) el.textContent = node.text;
  for (const child of node.children || []) el.append(toDom(doc, child));
  return el;
}

const xml = (s) =>
  String(s)
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/\r?\n/g, "&#10;");
const colour = { new: ["#d5efd9", "#2e7d32"], modified: ["#fdeec4", "#b26a00"], removed: ["#f8d9d6", "#b3261e"], unchanged: ["#f4f1ea", "#44505f"] };
const shape = {
  service: "rounded=1;arcSize=12;",
  database: "shape=cylinder3;boundedLbl=1;backgroundOutline=1;size=12;",
  file: "shape=note;size=16;",
  config: "shape=hexagon;perimeter=hexagonPerimeter2;size=0.15;",
  agent: "rounded=1;arcSize=50;",
  person: "ellipse;",
  external: "rounded=1;arcSize=6;dashed=1;",
  ci: "shape=process;size=0.08;",
  other: "rounded=0;",
};

/** The graph as an uncompressed draw.io file. html=0 everywhere: labels are text, not HTML. */
export function drawio(graph, name = "Diagrama") {
  const g = layout(graph);
  const cells = ['<mxCell id="0"/>', '<mxCell id="1" parent="0"/>'];
  for (const n of g.nodes) {
    const t = kind(n.type), [fill, stroke] = colour[n.change] || colour.unchanged;
    const prefix = n.change in CHANGES ? `[${CHANGES[n.change].toLowerCase()}] ` : "";
    const style = `${shape[t]}whiteSpace=wrap;html=0;fillColor=${fill};strokeColor=${stroke};fontColor=#1b2433;strokeWidth=${n.change in CHANGES ? 2 : 1};${n.change === "removed" ? "dashed=1;" : ""}`;
    cells.push(
      `<mxCell id="n_${xml(n.id)}" value="${xml(prefix + n.label)}" style="${style}" vertex="1" parent="1"><mxGeometry x="${n.x}" y="${n.y}" width="${n.w}" height="${n.h}" as="geometry"/></mxCell>`,
    );
  }
  g.edges.forEach((e, i) =>
    cells.push(
      `<mxCell id="e_${i}" value="${xml(e.label || "")}" style="edgeStyle=orthogonalEdgeStyle;rounded=1;html=0;endArrow=block;endFill=1;" edge="1" parent="1" source="n_${xml(e.from)}" target="n_${xml(e.to)}"><mxGeometry relative="1" as="geometry"/></mxCell>`,
    ),
  );
  return (
    `<?xml version="1.0" encoding="UTF-8"?>\n<mxfile host="AgentFarm"><diagram name="${xml(name)}" id="agentfarm">` +
    `<mxGraphModel dx="0" dy="0" grid="1" gridSize="10" page="1" pageWidth="${Math.ceil(g.width)}" pageHeight="${Math.ceil(g.height)}"><root>${cells.join("")}</root></mxGraphModel></diagram></mxfile>\n`
  );
}

/** The graph of a proposal field if it is usable, else null (old proposals only have Mermaid). */
export const graphOf = (g) => (g && Array.isArray(g.nodes) && g.nodes.length ? g : null);

const SHAPES = [
  [/^\[\((.*)\)\]$/s, "database"],
  [/^\(\[(.*)\]\)$/s, "agent"],
  [/^\(\((.*)\)\)$/s, "person"],
  [/^\{\{(.*)\}\}$/s, "config"],
  [/^\[\[(.*)\]\]$/s, "ci"],
  [/^\[\/(.*)\/\]$/s, "external"],
  [/^>(.*)\]$/s, "file"],
  [/^\["?(.*?)"?\]$/s, "service"],
  [/^\("?(.*?)"?\)$/s, "other"],
  [/^\{"?(.*?)"?\}$/s, "other"],
];
const KINDS = [
  [/base de datos|\bdb\b|sqlite|postgres|mysql|redis|database/i, "database"],
  [/\.(ya?ml|json|toml|ini|env|properties)\b|config|perfil/i, "config"],
  [/\.(md|txt|csv|log|java|js|py|sh|xml)\b|fichero|archivo/i, "file"],
  [/agente|napoleon|boxer|benjamin|clover|squealer/i, "agent"],
  [/usuario|propietario|persona/i, "person"],
  [/\bci\b|workflow|pipeline|github actions/i, "ci"],
];

/**
 * Best-effort graph from a Mermaid flowchart, for proposals written before typed graphs existed.
 * Only reads nodes, shapes, arrows and :::new/:::modified/:::removed marks; anything else is
 * ignored. Returns null when nothing usable comes out, so the caller shows the raw text instead.
 */
export function fromMermaid(source) {
  if (typeof source !== "string" || !source.trim()) return null;
  const nodes = new Map();
  const edges = [];
  const clean = (s) => s.replace(/<br\s*\/?>/gi, " ").replace(/\s+/g, " ").replace(/^"|"$/g, "").trim();
  const touch = (raw) => {
    const m = /^\s*([A-Za-z0-9_-]+)\s*(.*?)\s*$/s.exec(raw);
    if (!m) return null;
    let rest = m[2];
    let change = "unchanged";
    const mark = /:::(\w+)\s*$/.exec(rest) || /^:::(\w+)/.exec(rest);
    if (mark) {
      if (mark[1] in { new: 1, modified: 1, removed: 1 }) change = mark[1];
      rest = rest.replace(mark[0], "");
    }
    let label = m[1];
    let type = null;
    if (rest) {
      for (const [re, kind] of SHAPES) {
        const hit = re.exec(rest);
        if (hit) {
          label = clean(hit[1]) || m[1];
          type = kind;
          break;
        }
      }
    }
    const known = nodes.get(m[1]);
    if (known) {
      if (type) {
        known.label = label;
        known.type = type === "service" ? known.type : type;
      }
      if (change !== "unchanged") known.change = change;
    } else {
      nodes.set(m[1], { id: m[1], label: label.slice(0, 40), type: type || "service", change });
    }
    return m[1];
  };
  for (const line0 of source.split(/\r?\n/)) {
    const line = line0.replace(/%%.*$/, "").trim();
    if (!line || /^(flowchart|graph|subgraph|end\b|classDef|class\s|style\s|linkStyle|direction|click\s)/i.test(line)) continue;
    const chain = line
      .replace(/--\s+([^->|]+?)\s+-->/g, "-->|$1|")
      .split(/\s*(?:-{2,}>|={2,}>|-\.+->|-{3,})\s*(?:\|([^|]*)\|)?\s*/);
    // split() interleaves the captured edge label between the node chunks
    let prev = null;
    let label = "";
    for (let i = 0; i < chain.length; i++) {
      if (i % 2 === 1) {
        label = chain[i] || "";
        continue;
      }
      const ids = chain[i].split("&").map(touch).filter(Boolean);
      if (prev && ids.length) for (const a of prev) for (const b of ids) edges.push({ from: a, to: b, label: clean(label).slice(0, 24) });
      prev = ids.length ? ids : prev;
      label = "";
    }
  }
  for (const n of nodes.values()) {
    if (n.type === "service") {
      const kind = KINDS.find(([re]) => re.test(n.label));
      if (kind) n.type = kind[1];
    }
  }
  const list = [...nodes.values()].slice(0, 24);
  const ids = new Set(list.map((n) => n.id));
  const seen = new Set();
  const kept = edges.filter((e) => {
    const k = e.from + ">" + e.to;
    if (!ids.has(e.from) || !ids.has(e.to) || e.from === e.to || seen.has(k)) return false;
    seen.add(k);
    return true;
  }).slice(0, 40);
  return list.length && kept.length ? { nodes: list, edges: kept } : null;
}
