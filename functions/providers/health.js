/**
 * health.js — Provider health probing (M4).
 *
 * Runs each enabled, module-backed provider's testConnection and persists the outcome
 * into the provider's `health` doc. The per-row admin **Test connection** button already
 * writes health (glyph-admin .../api-providers/[id]/test); this module extends that to a
 * bulk/automatic probe of every provider at once.
 *
 * Design notes:
 *  - No Firebase require at module load. The six provider modules are admin-safe to
 *    require from the admin portal (firebase-functions/firebase-admin are only required
 *    lazily, or not at all, by those modules), so this file can be `require`d by both the
 *    Cloud Functions runtime AND the admin route without those deps installed in the admin
 *    tree. firebase-admin + cache are required lazily inside probeAllProviders() only.
 *  - The probe logic (buildProbeList / runProbes) is pure: it takes a snapshot-shaped
 *    object and an injected `writeHealth(id, health)` callback. The Cloud Functions runtime
 *    supplies a Firestore-backed writer via probeAllProviders(); the admin route supplies
 *    its own adminDb-backed writer. This keeps the providers tree free of index.js changes
 *    (per M4 constraint #6 — no new CF callable / index export required for M4).
 *
 * The MODULES registry here is intentionally a copy of router.js's; keep the two in sync.
 */

const MODULES = {
  gemini: require("./gemini"),
  google_tts: require("./google_tts"),
  elevenlabs: require("./elevenlabs"),
  deepl: require("./deepl"),
  openai: require("./openai"),
  anthropic: require("./anthropic"),
  deepseek: require("./deepseek"),
  openrouter: require("./openrouter"),
};

/**
 * Pick a capability to exercise for a provider. Prefer a non-image capability (gemini's
 * testConnection refuses `image`; nothing else needs it for a smoke test).
 */
function probeCapability(doc) {
  const caps = Array.isArray(doc.capabilities) ? doc.capabilities : [];
  const pick = caps.find((c) => c !== "image") || caps[0];
  return pick || "llm";
}

// Resolve the backing module for a provider doc. A custom provider can name its
// implementation module via `doc.module` (filename without .js); otherwise we fall
// back to the provider's doc id (the convention used by the six seeded providers).
function moduleOf(doc) {
  if (!doc) return undefined;
  return MODULES[doc.module || doc.id];
}

function buildProbeList(snapshot) {
  const list = [];
  if (!snapshot || !snapshot.providers) return list;
  snapshot.providers.forEach((doc) => {
    if (!doc || !doc.enabled) return;
    const mod = moduleOf(doc);
    if (!mod || typeof mod.testConnection !== "function") return; // custom provider w/o module
    list.push({ id: doc.id, doc, capability: probeCapability(doc) });
  });
  return list;
}

/**
 * Run testConnection for every enabled, module-backed provider and persist health via the
 * injected `writeHealth(id, health)` callback. Returns the probe results for the caller.
 * `writeHealth` failures are swallowed (best-effort), as are unexpected test throws.
 */
async function runProbes(snapshot, writeHealth) {
  const list = buildProbeList(snapshot);
  const results = [];
  for (const item of list) {
    const mod = moduleOf(item.doc);
    let res;
    try {
      res = await mod.testConnection({
        apiKey: item.doc.apiKey || undefined,
        baseUrl: item.doc.baseUrl || undefined,
        model: item.doc.defaultModel || undefined,
        capability: item.capability,
      });
    } catch (e) {
      res = {
        ok: false,
        capability: item.capability,
        latencyMs: 0,
        detail: e && e.message ? e.message : "Unknown error",
      };
    }

    const health = {
      status: res.ok ? "healthy" : "unhealthy",
      lastChecked: Date.now(),
      lastError: res.ok ? null : res.detail || "Health check failed",
      latencyMs: typeof res.latencyMs === "number" ? res.latencyMs : null,
    };

    if (writeHealth) {
      try {
        await writeHealth(item.id, health);
      } catch {
        // best-effort persist — never fail the probe run over a write error
      }
    }

    results.push({
      id: item.id,
      capability: item.capability,
      ok: !!res.ok,
      latencyMs: health.latencyMs,
      detail: res.detail || null,
    });
  }
  return results;
}

/**
 * Cloud Functions runtime entry point: loads the live snapshot and writes health to
 * Firestore. firebase-admin + cache are required lazily so this module stays requireable
 * from the admin portal (where those packages may not resolve from functions/providers).
 *
 * NOTE: a periodic/scheduled probe can call this — but wiring it as a scheduled function
 * would add an index.js export, which M4 deliberately avoids. Drive bulk checks via the
 * admin route (POST /api/api-providers/health-check) for now; add a scheduler later if
 * desired.
 */
async function probeAllProviders() {
  const cache = require("./cache");
  const admin = require("firebase-admin");
  const snapshot = await cache.getSnapshot();
  const db = admin.firestore();
  return runProbes(snapshot, async (id, health) => {
    await db.collection("api_providers").doc(id).set({ health }, { merge: true });
  });
}

module.exports = {
  MODULES,
  probeCapability,
  buildProbeList,
  runProbes,
  probeAllProviders,
};
