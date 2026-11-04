import { E, liveSnapshots } from "./lib.js";

// The farm is state, not decoration: one crate per decision, one sack per integrated change, the pasture
// is the quota left, and each animal stands where its work is. Coordinates are in scene pixels.
const WIDTH = 420;
const MAX_CRATES = 10;
const MAX_SACKS = 14;
const SACK_ROWS = [6, 5, 3];
const PASTURE = { x: 210, y: 110, w: 76, h: 30 };

const place = (e, x, y) => {
  e.style.setProperty("--x", x);
  e.style.setProperty("--y", y);
};
const sprite = (name, w, h) => {
  const e = E("img", null, "sprite " + name);
  e.src = "/art/" + name + ".png";
  e.alt = "";
  e.width = w;
  e.height = h;
  e.style.setProperty("--w", w);
  e.style.setProperty("--h", h);
  return e;
};
const tag = () => E("span", "", "tag");

export class FarmMap {
  constructor() {
    this.root = E("div", "", "farm-window");
    this.root.setAttribute("role", "img");
    this.scene = E("div", "", "farm");
    const day = E("img", null, "scene for-light"),
      dusk = E("img", null, "scene for-dark");
    day.src = "/art/granja-dia.png";
    dusk.src = "/art/granja-atardecer.png";
    day.alt = dusk.alt = "";
    this.spent = E("i", "", "spent");
    place(this.spent, PASTURE.x, PASTURE.y);
    this.spent.style.setProperty("--h", PASTURE.h);
    this.crates = E("div");
    this.sacks = E("div");
    this.boxer = sprite("boxer", 20, 15);
    this.benjamin = sprite("benjamin", 16, 17);
    this.napoleon = sprite("napoleon", 16, 14);
    this.tags = {};
    for (const key of ["boxer", "benjamin", "napoleon", "crates", "clover", "sacks", "mill"])
      this.tags[key] = tag();
    this.scene.append(day, dusk, this.spent, this.crates, this.sacks, this.boxer, this.benjamin, this.napoleon, ...Object.values(this.tags));
    this.root.append(this.scene);
    new ResizeObserver(() => this.fit()).observe(this.root);
  }
  label(key, name, text, x, y, hot = false) {
    const t = this.tags[key];
    t.className = "tag" + (hot ? " hot" : "");
    t.replaceChildren(E("b", name), document.createTextNode(text ? " · " + text : ""));
    place(t, x, y);
  }
  update({ runtime, decisions, integrated, quotaFresh, usedPercent, initiative }) {
    const live = liveSnapshots(runtime);
    const at = (...states) => live.find((x) => states.includes(x.run_state));
    const signal = !!runtime.fresh;
    this.scene.classList.toggle("no-signal", !signal);

    const building = at("implementing", "testing");
    this.boxer.classList.toggle("working", !!building);
    place(this.boxer, building ? 30 : 118, building ? 80 : 80);
    this.label(
      "boxer",
      "Boxer",
      !signal ? "sin señal" : building ? building.task_title || (building.run_state === "testing" ? "pruebas" : "implementando") : "libre",
      building ? 84 : 128,
      building ? 79 : 79,
      !!building,
    );

    const checking = at("reviewing");
    place(this.benjamin, checking ? 150 : 204, 79);
    this.label("benjamin", "Benjamin", !signal ? "sin señal" : checking ? checking.task_title || "revisando" : "libre", checking ? 158 : 212, 78, !!checking);

    const looking = at("researching");
    place(this.napoleon, looking ? 330 : 238, 82);
    this.label("napoleon", "Napoleon", !signal ? "sin señal" : looking ? looking.task_title || "investigando" : "coordina", looking ? 338 : 262, looking ? 81 : 56, !!looking);

    const crates = Math.min(decisions, MAX_CRATES);
    if (this.crateCount !== crates) {
      this.crateCount = crates;
      this.crates.replaceChildren();
      for (let i = 0; i < crates; i++) {
        const c = sprite("caja", 13, 12);
        place(c, 128 + (i % 5) * 16, 116 + Math.floor(i / 5) * 13);
        this.crates.append(c);
      }
    }
    if (decisions) this.label("crates", decisions === 1 ? "1 caja" : decisions + " cajas", "", 160, 112, true);
    this.tags.crates.hidden = !decisions;

    const sacks = Math.min(integrated, MAX_SACKS);
    if (this.sackCount !== sacks) {
      this.sackCount = sacks;
      this.sacks.replaceChildren();
      let left = sacks;
      SACK_ROWS.forEach((count, row) => {
        for (let k = 0; k < count && left > 0; k++, left--) {
          const s = sprite("saco", 9, 7);
          place(s, 304 + k * 9 + row * 4, 131 - row * 7);
          this.sacks.append(s);
        }
      });
    }
    this.label("sacks", integrated === 1 ? "1 saco" : integrated + " sacos", "", 330, 99);

    const known = quotaFresh && usedPercent != null;
    const spentWidth = known ? Math.round((PASTURE.w * Math.min(100, Math.max(0, usedPercent))) / 100) : PASTURE.w;
    this.spent.style.setProperty("--w", spentWidth);
    this.spent.style.setProperty("--x", PASTURE.x + PASTURE.w - spentWidth);
    this.label("clover", "Clover", known ? Math.round(100 - usedPercent) + " %" : "cuota desconocida", PASTURE.x + PASTURE.w / 2, PASTURE.y - 2, known ? usedPercent >= 80 : true);

    this.tags.mill.hidden = !initiative;
    if (initiative) this.label("mill", "Molino", initiative.title, 366, 16);

    this.root.setAttribute(
      "aria-label",
      "La granja ahora: " +
        (decisions === 1 ? "1 caja espera" : decisions + " cajas esperan") + " tu decisión, " +
        (integrated === 1 ? "1 saco" : integrated + " sacos") + " en el granero, " +
        (known ? Math.round(100 - usedPercent) + " % de cuota, " : "cuota desconocida, ") +
        (building ? "Boxer trabajando." : "Boxer libre."),
    );
  }
  // On a phone the window scrolls: start where the decisions are, and let the keyboard move it too.
  fit() {
    const scrolls = this.root.clientWidth > 0 && this.root.clientWidth < this.scene.offsetWidth;
    if (scrolls) this.root.tabIndex = 0;
    else this.root.removeAttribute("tabindex");
    if (scrolls && !this.centered) {
      this.root.scrollLeft = (this.scene.offsetWidth - this.root.clientWidth) / 2 - 40;
      this.centered = true;
    }
  }
}
export { WIDTH };
