// Diagrams of a proposal: layout, one shape per kind, and the draw.io export. Runs with `node --test`.
import { test } from "node:test";
import assert from "node:assert/strict";
import { layout, scene, toDom, drawio, wrap, TYPES, graphOf } from "../../src/main/resources/static/app/diagram.js";

const node = (id, type = "service", change = "unchanged", label = id) => ({ id, label, type, change });
const sample = {
  nodes: [node("user", "person"), node("api", "service", "modified", "API de pedidos"), node("db", "database", "new", "Pedidos"), node("cfg", "config"), node("log", "file"), node("bot", "agent"), node("ext", "external"), node("ci", "ci")],
  edges: [
    { from: "user", to: "api", label: "pide" },
    { from: "api", to: "db", label: "escribe" },
    { from: "cfg", to: "api" },
    { from: "api", to: "log" },
    { from: "bot", to: "ci" },
    { from: "ext", to: "api" },
  ],
};

const overlap = (a, b) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;

test("nodes sit in columns by dependency and never overlap", () => {
  const g = layout(sample);
  const at = Object.fromEntries(g.nodes.map((n) => [n.id, n]));
  assert.ok(at.user.x < at.api.x && at.api.x < at.db.x, "arrows go left to right");
  for (const [i, a] of g.nodes.entries()) for (const b of g.nodes.slice(i + 1)) assert.ok(!overlap(a, b), a.id + " overlaps " + b.id);
  for (const n of g.nodes) assert.ok(n.x >= 0 && n.y >= 0 && n.x + n.w <= g.width && n.y + n.h <= g.height, n.id + " is inside the picture");
});

test("a graph with a cycle or a self loop still lays out", () => {
  const g = layout({ nodes: [node("a"), node("b"), node("c")], edges: [{ from: "a", to: "b" }, { from: "b", to: "c" }, { from: "c", to: "a" }, { from: "b", to: "b" }] });
  assert.equal(g.nodes.length, 3);
  assert.ok(g.edges.every((e) => typeof e.path === "string" && e.path.startsWith("M")));
});

test("edges to nodes that do not exist are ignored", () => {
  assert.equal(layout({ nodes: [node("a")], edges: [{ from: "a", to: "zzz" }] }).edges.length, 0);
  assert.equal(layout(null).nodes.length, 0);
});

test("every kind has its own outline, not only its own colour", () => {
  const outline = (type) => {
    const s = scene({ nodes: [node("x", type)], edges: [] });
    const g = s.children.find((c) => c.tag === "g");
    const b = g.children.find((c) => c.attrs?.class === "dg-body");
    return b.tag + ":" + (b.attrs.d ? b.attrs.d.replace(/[-\d.]+/g, "#") : "") + ":" + (b.attrs.rx ?? "") + ":" + (b.attrs["stroke-dasharray"] ?? "");
  };
  const shapes = Object.keys(TYPES).filter((t) => t !== "other").map(outline);
  assert.equal(new Set(shapes).size, shapes.length, shapes.join("\n"));
});

test("a changed node says so in words, and the legend names the kinds used", () => {
  const s = scene(sample);
  const texts = JSON.stringify(s);
  assert.match(texts, /NUEVO/);
  assert.match(texts, /CAMBIA/);
  assert.match(texts, /Base de datos/);
  const legend = s.children.filter((c) => c.attrs?.class === "dg-legend").map((c) => c.text);
  assert.equal(new Set(legend).size, legend.length, "one legend entry per kind");
  assert.equal(legend.length, 8, "the eight kinds in the sample");
});

test("long labels wrap and are cut, never overflow", () => {
  const lines = wrap("Servicio de autenticación y autorización de todos los usuarios del sistema", 17, 3);
  assert.ok(lines.length <= 3 && lines.every((l) => l.length <= 17), lines.join("|"));
  assert.ok(lines.at(-1).endsWith("…"));
  assert.deepEqual(wrap("API"), ["API"]);
});

test("the DOM is built with text, so a label is never parsed as markup", () => {
  const made = [];
  const doc = {
    createElementNS: (ns, tag) => {
      const el = { ns, tag, attrs: {}, kids: [], setAttribute(k, v) { this.attrs[k] = v; }, append(c) { this.kids.push(c); } };
      made.push(el);
      return el;
    },
  };
  const evil = '<img src=x onerror=alert(1)>"&';
  toDom(doc, scene({ nodes: [node("a", "service", "unchanged", evil)], edges: [] }));
  const texts = made.filter((e) => e.textContent !== undefined).map((e) => e.textContent);
  assert.ok(texts.some((t) => t.includes("onerror")), "the label is kept as text");
  assert.ok(made.every((e) => e.innerHTML === undefined), "innerHTML is never used");
  assert.ok(made.every((e) => e.ns === "http://www.w3.org/2000/svg"));
});

test("the draw.io file escapes every value and turns HTML off", () => {
  const evil = { nodes: [node("a", "database", "new", 'x" onload="alert(1)<b>&'), node("b")], edges: [{ from: "a", to: "b", label: '"><script>' }] };
  const xml = drawio(evil, 'n"ame');
  assert.doesNotMatch(xml, /<script|<b>|onload="/);
  assert.match(xml, /&quot;/);
  for (const style of xml.match(/style="[^"]*"/g)) assert.match(style, /html=0/, style);
  assert.match(xml, /shape=cylinder3/);
  assert.match(xml, /source="n_a" target="n_b"/);
  assert.match(xml, /^<\?xml/);
  assert.equal((xml.match(/<mxCell /g) || []).length, 2 + 2 + 1, "root cells, two nodes, one edge");
});

test("only a graph with nodes counts as a graph", () => {
  assert.equal(graphOf(undefined), null);
  assert.equal(graphOf({ nodes: [] }), null);
  assert.ok(graphOf({ nodes: [node("a")] }));
});

test("an old Mermaid flowchart becomes a typed graph", async () => {
  const { fromMermaid } = await import("../../src/main/resources/static/app/diagram.js");
  const g = fromMermaid(`flowchart TD
  repo["Repositorio de integración"] --> cfg["agentfarm.yaml existente"]
  repo -->|usa| ci["CI de contratos<br/>independiente"]
  cfg --> doc["docs/adoption-analysis.md añadido"]:::new
  classDef new fill:#cfc`);
  assert.equal(g.nodes.length, 4);
  assert.equal(g.nodes.find((n) => n.id === "cfg").type, "config");
  assert.equal(g.nodes.find((n) => n.id === "doc").type, "file");
  assert.equal(g.nodes.find((n) => n.id === "doc").change, "new");
  assert.equal(g.nodes.find((n) => n.id === "ci").label, "CI de contratos independiente");
  assert.deepEqual(g.edges.map((e) => e.from + ">" + e.to + ":" + e.label), ["repo>cfg:", "repo>ci:usa", "cfg>doc:"]);
  assert.equal(fromMermaid("texto suelto sin flechas"), null);
  assert.equal(fromMermaid(""), null);
});
