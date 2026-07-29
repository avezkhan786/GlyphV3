/**
 * verify-m4.js — M4 failover + metrics verification harness (no real Firestore / network).
 *
 * router.js destructures getSnapshot() at module load, so we cannot override cache.getSnapshot
 * after the fact. Instead we point firebase-admin's firestore() at a fake in-memory db whose
 * collections are reconfigured per test (with a bumped config_version so cache.js reloads).
 * router.MODULES entries are overridden to simulate provider success/failure, and
 * metrics.recordMetric is captured. global.fetch is stubbed so the legacy (real gemini) path
 * succeeds offline.
 *
 * Run from this directory:  node verify-m4.js
 */

const assert = require("assert");
const admin = require("firebase-admin");
const cache = require("./cache");
const router = require("./router");
const metrics = require("./metrics");

// A dummy key so the legacy (real gemini) path proceeds to the stubbed fetch in T4.
process.env.GOOGLE_CLOUD_API_KEY = "test-key";

// ─── capture metrics ─────────────────────────────────────
const recorded = [];
metrics.recordMetric = async (m) => {
  recorded.push(m);
};
metrics.setEnabled(true);

// ─── fake Firestore so getSnapshot/loadSnapshot work offline ──
let FAKE = { settings: [], providers: [], version: 0 };
const fakeDb = {
  collection(name) {
    return {
      doc() {
        return {
          get: async () => {
            if (name === "config_version") return { exists: true, data: () => ({ version: FAKE.version }) };
            return { exists: false, data: () => ({}) };
          },
        };
      },
      get: async () => {
        const arr = name === "ai_settings" ? FAKE.settings : name === "api_providers" ? FAKE.providers : [];
        return { forEach: (cb) => arr.forEach(cb) };
      },
    };
  },
};
// firebase-admin defines `firestore` as a prototype getter; a plain assignment is a
// silent no-op in non-strict mode. Override it on the prototype with a data property.
try {
  Object.defineProperty(Object.getPrototypeOf(admin), "firestore", {
    value: () => fakeDb,
    configurable: true,
  });
} catch {
  try {
    admin.firestore = () => fakeDb;
  } catch {
    /* best-effort */
  }
}

// ─── stub fetch so the legacy (real gemini) path can succeed offline ──
global.fetch = async (url) => {
  if (typeof url === "string" && url.includes("generateContent")) {
    return {
      ok: true,
      status: 200,
      text: async () => "",
      json: async () => ({ candidates: [{ content: { parts: [{ text: "legacy ok" }] } }] }),
    };
  }
  return { ok: true, status: 200, text: async () => "", json: async () => ({}) };
};

function setSnapshot(providers, settings) {
  FAKE.version += 1; // force cache.js to reload
  FAKE.providers = (providers || []).map((d) => ({ id: d.id, data: () => d }));
  FAKE.settings = (settings || []).map(([id, data]) => ({ id, data: () => data }));
}

const origModules = { ...router.MODULES };
const okMod = (ret) => ({
  generateText: async () => ret,
  translate: async () => ret,
  synthesizeSpeech: async () => ret,
  speechToText: async () => ret,
  ocr: async () => ret,
  generateImage: async () => ret,
});
const failMod = (msg) => {
  const e = new Error(msg);
  return {
    generateText: async () => {
      throw e;
    },
    translate: async () => {
      throw e;
    },
    synthesizeSpeech: async () => {
      throw e;
    },
    speechToText: async () => {
      throw e;
    },
  };
};

let pass = 0;
let fail = 0;
function check(name, cond) {
  if (cond) {
    pass++;
    console.log("  ✓ " + name);
  } else {
    fail++;
    console.error("  ✗ " + name);
  }
}

async function run() {
  // ── T1: failover gemini(active) -> anthropic by priority ──
  recorded.length = 0;
  router.MODULES.gemini = failMod("gemini down");
  router.MODULES.anthropic = okMod({ text: "hi", model: "claude-3-5-haiku-latest", latencyMs: 12 });
  setSnapshot(
    [
      { id: "gemini", enabled: true, capabilities: ["llm"], priority: 10, defaultModel: "gemini-2.5-flash" },
      { id: "anthropic", enabled: true, capabilities: ["llm"], priority: 30, defaultModel: "claude-3-5-haiku-latest" },
    ],
    [["llm", { activeProvider: "gemini", fallbackEnabled: true }]]
  );
  const r1 = await router.generateText({ contents: [{ parts: [{ text: "x" }] }] });
  check("T1 failover result.providerId === anthropic", r1.providerId === "anthropic");
  check("T1 result text correct", r1.text === "hi");
  check("T1 recorded exactly 2 metrics", recorded.length === 2);
  check("T1 #1 failed gemini", recorded[0].provider === "gemini" && recorded[0].success === false);
  check("T1 #2 ok anthropic", recorded[1].provider === "anthropic" && recorded[1].success === true);
  check("T1 metric capability=llm", recorded[1].capability === "llm");

  // ── T2: all candidates fail -> throws, metrics on each ──
  recorded.length = 0;
  router.MODULES.gemini = failMod("gemini down");
  router.MODULES.anthropic = failMod("anthropic down");
  setSnapshot(
    [
      { id: "gemini", enabled: true, capabilities: ["llm"], priority: 10, defaultModel: "gemini-2.5-flash" },
      { id: "anthropic", enabled: true, capabilities: ["llm"], priority: 30, defaultModel: "claude-3-5-haiku-latest" },
    ],
    [["llm", { activeProvider: "gemini", fallbackEnabled: true }]]
  );
  let threw = false;
  try {
    await router.generateText({ contents: [{ parts: [{ text: "x" }] }] });
  } catch {
    threw = true;
  }
  check("T2 throws after exhausting chain", threw);
  check("T2 recorded 2 fail metrics", recorded.length === 2 && recorded.every((m) => !m.success));

  // ── T3: disabled active (priority 10) -> next enabled (priority 20) ──
  recorded.length = 0;
  router.MODULES.gemini = okMod({ text: "g", model: "gemini-2.5-flash", latencyMs: 5 });
  router.MODULES.deepl = okMod({ translatedText: "es", model: "deepl-free", latencyMs: 7 });
  setSnapshot(
    [
      { id: "gemini", enabled: false, capabilities: ["translation"], priority: 10, defaultModel: "gemini-2.5-flash" },
      { id: "deepl", enabled: true, capabilities: ["translation"], priority: 20, defaultModel: "deepl-free" },
    ],
    [["translation", { activeProvider: "gemini", fallbackEnabled: true }]]
  );
  const r3 = await router.translate({ text: "hello", targetLanguage: "es" });
  check("T3 routed to deepl (active disabled)", r3.providerId === "deepl" && r3.translatedText === "es");
  check("T3 single metric (active skipped)", recorded.length === 1 && recorded[0].provider === "deepl");

  // ── T4: no seeded providers -> legacy fallback ──
  recorded.length = 0;
  router.MODULES.gemini = origModules.gemini; // real module; fetch stub returns 200
  setSnapshot([], []);
  const r4 = await router.generateText({ contents: [{ parts: [{ text: "x" }] }] });
  check("T4 legacy provider id", r4.providerId === "legacy_gemini");
  check("T4 single legacy metric", recorded.length === 1 && recorded[0].provider === "legacy_gemini");

  // ── T5: buildChain ordering (active first, then priority asc, excludes disabled) ──
  const chain = router.buildChain(
    "llm",
    (() => {
      const pmap = new Map();
      [
        { id: "anthropic", enabled: true, capabilities: ["llm"], priority: 30, defaultModel: "x" },
        { id: "gemini", enabled: true, capabilities: ["llm"], priority: 10, defaultModel: "x" },
        { id: "openai", enabled: true, capabilities: ["llm"], priority: 20, defaultModel: "x" },
        { id: "disabled", enabled: false, capabilities: ["llm"], priority: 5, defaultModel: "x" },
      ].forEach((d) => pmap.set(d.id, d));
      return { settings: new Map([["llm", { activeProvider: "gemini", fallbackEnabled: true }]]), providers: pmap };
    })()
  );
  check("T5 excludes disabled provider", !chain.find((c) => c.id === "disabled"));
  check("T5 active first then priority order", chain.map((c) => c.id).join(",") === "gemini,openai,anthropic");

  // restore
  router.MODULES = origModules;

  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
}

run().catch((e) => {
  console.error(e);
  process.exit(1);
});
