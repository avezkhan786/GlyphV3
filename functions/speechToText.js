/**
 * speechToText.js — Cloud Function for Speech-to-Text + Translation
 *
 * Endpoint:
 *   speechToText(audioBase64, audioEncoding, sampleRate, languageCode, targetLanguage)
 *     → { recognizedText, translatedText, sourceLanguage }
 *
 * Backend flow:
 *   1. Validate input (auth, rate limit, audio size)
 *   2. Call Google Cloud Speech-to-Text via Gemini API
 *   3. If targetLanguage specified, translate recognized text
 *   4. Return result
 */

const functions = require("firebase-functions");
const admin = require("firebase-admin");

const router = require("./providers/router");

// Firebase Admin is initialized in index.js

// ─── Rate limiter (shared with translate.js) ──────────────

const RATE_LIMIT_WINDOW_MS = 60 * 1000;
const RATE_LIMIT_MAX = 15; // max 15 STT requests per minute per user

async function checkRateLimit(userId) {
  const db = admin.firestore();
  const ref = db.collection("rate_limits_stt").doc(userId);
  const now = Date.now();

  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const data = snap.data() || { count: 0, windowStart: now };

    if (now - data.windowStart > RATE_LIMIT_WINDOW_MS) {
      tx.set(ref, { count: 1, windowStart: now });
      return true;
    }

    if (data.count >= RATE_LIMIT_MAX) {
      return false;
    }

    if (snap.exists) {
      tx.update(ref, { count: admin.firestore.FieldValue.increment(1) });
    } else {
      tx.set(ref, { count: 1, windowStart: now });
    }
    return true;
  });
}

// ─── Main Cloud Function ──────────────────────────────────

exports.speechToText = functions
  .runWith({
    timeoutSeconds: 120,
    memory: "1GB",
  })
  .https.onCall(async (data, context) => {
    const tStart = Date.now();
    const timings = {};

    console.log("=== Speech-to-Text request started ===");

    // 1. Auth check
    const userId = context.auth?.uid || `anon_${context.rawRequest?.ip || "unknown"}`;
    console.log("User ID:", userId);

    // 2. API key — reads from functions/.env (modern dotenv), fallback to legacy config.
    // Gate the hard error on legacy (unseeded) mode: when a provider is configured it
    // supplies its own key, so a missing env key must not block it (AI_PROVIDER_HANDOFF §13.3 #9).
    const apiKey = process.env.GOOGLE_CLOUD_API_KEY || functions.config().google?.api_key;
    const sttRouting = await router.getRouting("stt");
    if (sttRouting.providerId === "legacy_gemini" && !apiKey) {
      throw new functions.https.HttpsError(
        "internal",
        "Google Cloud API key not configured."
      );
    }

    // 3. Validate input
    const { audioBase64, targetLanguage, languageHint } = data;

    if (!audioBase64 || typeof audioBase64 !== "string") {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "audioBase64 is required."
      );
    }

    // Max audio size: ~10MB base64 ≈ ~7.5MB raw audio ≈ ~5 min of AAC
    if (audioBase64.length > 10 * 1024 * 1024) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "Audio file too large. Maximum 5 minutes."
      );
    }

    const tValidated = Date.now();
    timings.validation = tValidated - tStart;

    // 4. Rate limit
    const allowed = await checkRateLimit(userId);
    if (!allowed) {
      throw new functions.https.HttpsError(
        "resource-exhausted",
        "Rate limit exceeded. Try again in a minute."
      );
    }
    timings.rateLimit = Date.now() - tValidated;

    // 5. Speech-to-Text via the provider router (Gemini in M2 / Google parity).
    // Hinglish prompt handling + SAFETY_SETTINGS (all BLOCK_NONE) live in
    // providers/gemini.js; the contents array (prompt + inline audio) is assembled there.
    let recognizedText = null;
    // Check if Hinglish (romanized Hindi) is requested
    const wantHinglish = targetLanguage === "hi-Latn";
    try {
      console.log("Routing speech recognition via provider router...");
      const tSttStart = Date.now();

      const sttResult = await router.speechToText({
        audioBase64,
        mimeType: data.mimeType || "audio/mp4", // original always sent audio/mp4
        languageHint,
        targetLanguage,
      });
      recognizedText = sttResult.text;

      const tSttEnd = Date.now();
      timings.stt = tSttEnd - tSttStart;

      if (!recognizedText || recognizedText === "[inaudible]") {
        console.log("No speech recognized or audio was inaudible");
        return {
          recognizedText: null,
          translatedText: null,
          error: "no_speech",
          timings: { ...timings, total: Date.now() - tStart },
        };
      }

      console.log(
        "Speech recognized:",
        recognizedText.substring(0, 100) + "..."
      );
    } catch (err) {
      console.error("Speech recognition error:", err.message, err.stack);
      throw new functions.https.HttpsError(
        "internal",
        "Speech recognition failed. Please try again."
      );
    }

    // 6. If targetLanguage specified, translate the recognized text
    //    For Hinglish: the transcription itself IS the result, skip translation
    let translatedText = null;
    if (wantHinglish) {
      // Hinglish was already handled in the transcription prompt — recognized text IS the Hinglish
      console.log("Hinglish mode: transcription is the final output, skipping translation step");
      translatedText = null; // No separate translation needed
    } else if (
      targetLanguage &&
      typeof targetLanguage === "string" &&
      targetLanguage.length <= 10
    ) {
      try {
        console.log("Translating recognized text to:", targetLanguage);
        const tTranslateStart = Date.now();

        const trResult = await router.translate({
          text: recognizedText,
          targetLanguage,
        });
        translatedText = trResult.translatedText;

        timings.translation = Date.now() - tTranslateStart;
        console.log(
          "Translation successful:",
          (translatedText || "").substring(0, 50)
        );
      } catch (err) {
        console.error("Translation error (non-fatal):", err.message);
        // Translation failure is non-fatal - we still have the recognized text
        translatedText = null;
      }
    }

    timings.total = Date.now() - tStart;
    console.log("=== Speech-to-Text completed ===", JSON.stringify(timings));

    return {
      recognizedText,
      translatedText,
      timings,
    };
  });
