/**
 * enhanceMessage.js — Cloud Function for AI-powered message composition
 *
 * Endpoints:
 *   enhanceMessage(text, action, options?)
 *     → { enhancedText, action, cached, timings }
 *
 * Actions:
 *   "enhance"   – Improve clarity, readability, and impact
 *   "grammar"   – Fix grammar and spelling while preserving meaning
 *   "translate" – Translate to target language (options.targetLanguage)
 *   "tone"      – Adjust tone (options.tone: formal|friendly|casual|professional)
 *
 * Backend flow:
 *   1. Validate input (max 1000 chars, rate limit)
 *   2. Check Firestore cache by hash(text + action + options)
 *   3. If cached → return immediately
 *   4. Call Gemini API with action-specific prompt
 *   5. Cache result in Firestore
 *   6. Return result
 */

const functions = require("firebase-functions");
const admin = require("firebase-admin");
const crypto = require("crypto");

const CACHE_VERSION = 1;
const MAX_TEXT_LENGTH = 1000;
const RATE_LIMIT_PER_MINUTE = 30;

// ─── Helpers ────────────────────────────────────────────

function cacheKey(text, action, options) {
  const normalized = text.trim();
  const optStr = options ? JSON.stringify(options) : "";
  return crypto
    .createHash("sha256")
    .update(`${normalized}::${action}::${optStr}::${CACHE_VERSION}`)
    .digest("hex");
}

async function checkRateLimit(userId) {
  const ref = admin.firestore().collection("rate_limits_enhance").doc(userId);
  return admin.firestore().runTransaction(async (tx) => {
    const doc = await tx.get(ref);
    const now = Date.now();
    if (doc.exists) {
      const data = doc.data();
      const windowStart = data.windowStart || 0;
      const count = data.count || 0;
      if (now - windowStart < 60000) {
        if (count >= RATE_LIMIT_PER_MINUTE) {
          throw new functions.https.HttpsError(
            "resource-exhausted",
            "Rate limit exceeded. Please wait a moment."
          );
        }
        tx.update(ref, { count: count + 1 });
      } else {
        tx.set(ref, { count: 1, windowStart: now });
      }
    } else {
      tx.set(ref, { count: 1, windowStart: now });
    }
    return true;
  });
}

function buildPrompt(text, action, options) {
  switch (action) {
    case "enhance":
      return `You are a message writing assistant for a chat application. Improve the following message to make it clearer, more readable, and more impactful. Keep it natural and conversational — this is a chat message, not an essay. Preserve the original meaning and intent. Return ONLY the improved message text, nothing else. No explanations, no quotes, no labels.

Message: ${text}`;

    case "grammar":
      return `You are a grammar and spelling correction assistant for a chat application. Fix any grammar, spelling, and punctuation errors in the following message. Preserve the original meaning, tone, and style exactly. Do NOT rephrase or rewrite — only correct errors. If the message has no errors, return it unchanged. Return ONLY the corrected message text, nothing else. No explanations, no quotes, no labels.

Message: ${text}`;

    case "translate": {
      const targetLanguage = options?.targetLanguage || "en";
      if (targetLanguage === "hi-Latn") {
        return `Convert the following text to Romanized Hindi (Hinglish). Write it using English/Latin letters exactly how a Hindi speaker would type in WhatsApp chats. Preserve natural pronunciation and the original tone/intent. Do NOT use Devanagari script. Return ONLY the romanized Hindi text, nothing else. No explanations, no quotes, no labels.

Text: ${text}`;
      }
      return `Translate the following chat message to ${targetLanguage}. Maintain the original tone, intent, and conversational style. This is a chat message, so keep it natural. Return ONLY the translated text, nothing else. No explanations, no quotes, no labels.

Message: ${text}`;
    }

    case "tone": {
      const tone = options?.tone || "friendly";
      const toneDescriptions = {
        formal: "formal and polished, suitable for professional or respectful communication",
        friendly: "warm, friendly, and approachable while remaining clear",
        casual: "relaxed and casual, like texting a close friend",
        professional: "professional and business-appropriate while still being personable",
      };
      const toneDesc = toneDescriptions[tone] || toneDescriptions.friendly;
      return `You are a tone adjustment assistant for a chat application. Rewrite the following message to sound ${toneDesc}. Preserve the original meaning and intent completely. Return ONLY the adjusted message text, nothing else. No explanations, no quotes, no labels.

Message: ${text}`;
    }

    default:
      throw new functions.https.HttpsError(
        "invalid-argument",
        `Unknown action: ${action}`
      );
  }
}

// ─── Main Cloud Function ──────────────────────────────────

exports.enhanceMessage = functions
  .runWith({
    timeoutSeconds: 30,
    memory: "512MB",
  })
  .https.onCall(async (data, context) => {
    const tStart = Date.now();
    const timings = {};

    console.log("=== Enhance message request started ===");

    // 1. Auth
    const userId =
      context.auth?.uid || `anon_${context.rawRequest?.ip || "unknown"}`;

    // 2. API key
    const apiKey = "AIzaSyAVZ22mqebWYT3I9QbFXGXfiJV7SkOWmfE";

    // 3. Validate input
    const { text, action, options } = data;

    // Warmup handler
    if (text === "warmup") {
      console.log("Warmup ping received");
      return {
        enhancedText: "Warmup acknowledged",
        action: "warmup",
        cached: false,
        timings: { total: Date.now() - tStart },
      };
    }

    if (!text || typeof text !== "string" || text.trim().length === 0) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "Text is required."
      );
    }

    if (text.length > MAX_TEXT_LENGTH) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        `Text too long. Maximum ${MAX_TEXT_LENGTH} characters.`
      );
    }

    const validActions = ["enhance", "grammar", "translate", "tone"];
    if (!action || !validActions.includes(action)) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        `Invalid action. Must be one of: ${validActions.join(", ")}`
      );
    }

    // 4. Rate limiting
    try {
      await checkRateLimit(userId);
    } catch (e) {
      if (e instanceof functions.https.HttpsError) throw e;
      console.error("Rate limit check error:", e);
    }

    timings.validation = Date.now() - tStart;

    // 5. Check cache
    const key = cacheKey(text, action, options);
    const cacheRef = admin.firestore().collection("enhance_cache").doc(key);

    try {
      const tCacheStart = Date.now();
      const cached = await cacheRef.get();
      timings.cacheRead = Date.now() - tCacheStart;

      if (cached.exists) {
        const cachedData = cached.data();
        if (cachedData.version === CACHE_VERSION && cachedData.enhancedText) {
          console.log("Cache hit - returning cached result");
          timings.total = Date.now() - tStart;
          return {
            enhancedText: cachedData.enhancedText,
            action,
            cached: true,
            timings,
          };
        }
      }
    } catch (e) {
      console.error("Cache read error (non-fatal):", e.message);
    }

    // 6. Call Gemini API
    let enhancedText;
    try {
      console.log(`Calling Gemini API for action: ${action}`);
      const tGeminiStart = Date.now();

      const prompt = buildPrompt(text, action, options);

      const response = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${apiKey}`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            contents: [{ parts: [{ text: prompt }] }],
            safetySettings: [
              { category: "HARM_CATEGORY_HARASSMENT", threshold: "BLOCK_NONE" },
              {
                category: "HARM_CATEGORY_HATE_SPEECH",
                threshold: "BLOCK_NONE",
              },
            ],
            generationConfig: {
              temperature: action === "grammar" ? 0.1 : 0.7,
              maxOutputTokens: 1024,
            },
          }),
        }
      );

      timings.gemini = Date.now() - tGeminiStart;

      if (!response.ok) {
        const errorText = await response.text();
        console.error("Gemini API error:", errorText);
        throw new Error(`Gemini API error: ${response.status}`);
      }

      const result = await response.json();
      enhancedText = result.candidates?.[0]?.content?.parts?.[0]?.text?.trim();

      if (!enhancedText) {
        console.error("Empty result from Gemini");
        throw new Error("Empty enhancement result");
      }

      console.log(
        "Enhancement successful:",
        enhancedText.substring(0, 60) + "..."
      );
    } catch (err) {
      console.error("Gemini enhancement error:", err.message);
      throw new functions.https.HttpsError(
        "internal",
        "Enhancement failed. Please try again."
      );
    }

    // 7. Cache result
    try {
      const tCacheWriteStart = Date.now();
      await cacheRef.set(
        {
          originalText: text,
          enhancedText,
          action,
          options: options || null,
          version: CACHE_VERSION,
          createdAt: admin.firestore.FieldValue.serverTimestamp(),
          createdBy: userId,
        },
        { merge: true }
      );
      timings.cacheWrite = Date.now() - tCacheWriteStart;
    } catch (e) {
      console.error("Cache write error (non-fatal):", e.message);
    }

    timings.total = Date.now() - tStart;
    console.log("=== Enhancement complete ===", timings);

    return {
      enhancedText,
      action,
      cached: false,
      timings,
    };
  });
