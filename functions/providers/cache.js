/**
 * cache.js — In-memory snapshot of the AI provider configuration.
 *
 * Holds a snapshot of `ai_settings` (routing) and `api_providers` (provider docs)
 * so the router never has to read Firestore on the hot path more than necessary.
 *
 * Invalidation:
 *   - TTL_MS (90s) elapsed since last load, OR
 *   - `config_version/current.version` changed since last load, OR
 *   - no snapshot yet.
 *
 * `config_version` is a single cheap doc read on every getSnapshot(); this is the
 * mechanism that gives near-real-time invalidation when an admin switches providers.
 *
 * IMPORTANT: nothing here calls functions.config() — config is read lazily inside the
 * provider modules / router, exactly as before, to avoid deploy-time timeouts.
 */

const admin = require("firebase-admin");

let snapshot = null; // { settings: Map<cap, {activeProvider, fallbackEnabled}>, providers: Map<id, doc> }
let loadedAt = 0; // epoch ms
let versionAtLoad = 0; // config_version observed at load

const TTL_MS = 90 * 1000;

async function getConfigVersion() {
  try {
    const doc = await admin
      .firestore()
      .collection("config_version")
      .doc("current")
      .get();
    return doc.exists ? doc.data().version || 0 : 0;
  } catch (e) {
    // If we can't read it, treat as version 0 — TTL-only invalidation still applies.
    return 0;
  }
}

async function loadSnapshot() {
  const db = admin.firestore();
  const [settingsSnap, providersSnap, versionDoc] = await Promise.all([
    db.collection("ai_settings").get(),
    db.collection("api_providers").get(),
    db.collection("config_version").doc("current").get(),
  ]);

  const settings = new Map();
  settingsSnap.forEach((d) => {
    const data = d.data() || {};
    settings.set(d.id, {
      activeProvider: data.activeProvider || null,
      fallbackEnabled: data.fallbackEnabled !== false, // default true
    });
  });

  const providers = new Map();
  providersSnap.forEach((d) => {
    providers.set(d.id, { id: d.id, ...(d.data() || {}) });
  });

  const version = versionDoc.exists ? versionDoc.data().version || 0 : 0;

  snapshot = { settings, providers };
  loadedAt = Date.now();
  versionAtLoad = version;
  return snapshot;
}

/**
 * Returns the cached snapshot, reloading if TTL expired, config_version changed,
 * or no snapshot exists yet.
 */
async function getSnapshot() {
  const now = Date.now();
  let currentVersion = 0;
  try {
    currentVersion = await getConfigVersion();
  } catch (e) {
    currentVersion = versionAtLoad;
  }

  if (
    !snapshot ||
    now - loadedAt > TTL_MS ||
    currentVersion !== versionAtLoad
  ) {
    await loadSnapshot();
  }
  return snapshot;
}

module.exports = {
  getSnapshot,
  getConfigVersion,
  TTL_MS,
};
