// Can the owner integrate this PR from the panel, and if not, why. Pure, so it runs under
// `node --test`. The server still decides; this only says it beforehand, in plain words.

const stripEvidence = (text) => text.replace(": evidence missing", ": sin evidencia todavía");

/**
 * @returns {{show: boolean, ok: boolean, reasons: {text: string, to?: string, label?: string}[]}}
 * `show` is false when the person can't decide anything here (not a review, or not the owner).
 */
export function integration({ state, pr, sha, ready, blockers = [], role, githubConfigured, twoFactor }) {
  if (state !== "review" || role !== "owner") return { show: false, ok: false, reasons: [] };
  const reasons = [];
  if (!pr || !sha) reasons.push({ text: "Todavía no hay una PR con un commit que integrar." });
  if (githubConfigured === false)
    reasons.push({ text: "Falta conectar GitHub: sin ello el panel no puede fusionar.", to: "/governance", label: "Conectar GitHub" });
  if (twoFactor?.required_to_integrate && !twoFactor?.active)
    reasons.push({ text: "Para integrar hace falta activar tu segundo factor.", to: "/governance", label: "Activar segundo factor" });
  if (!ready) {
    const list = blockers.length ? blockers : ["Faltan criterios o mandamientos por cubrir."];
    for (const blocker of list) reasons.push({ text: stripEvidence(blocker) });
  }
  return { show: true, ok: reasons.length === 0, reasons };
}
