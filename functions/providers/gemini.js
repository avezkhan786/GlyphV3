/**
 * gemini.js — Google Gemini provider module (M2: Google parity).
 *
 * Implements: generateText, translate, speechToText, ocr.
 * (generateImage is intentionally NOT implemented — Gemini cannot generate images; the
 *  seeded `gemini` doc carries an `image` capability tag only so M3 can route `image`
 *  → openai. See AI_PROVIDER_HANDOFF.md §13.4.)
 *
 * This module MUST preserve, byte-for-byte in behavior, the Gemini calls that the
 * Cloud Functions already performed:
 *   - SAFETY_SETTINGS sent on every generateContent call (invariant §13.3 #5).
 *   - The system prompt / first-user-part `contents` array is forwarded verbatim
 *     (invariant §13.3 #4) — generateText does not reshape it.
 *   - Hinglish (hi-Latn) special prompts in both translate and speechToText
 *     (invariant §13.3 #7).
 *   - Never reads functions.config() at module load (invariant §13.3 #9) — only lazily
 *     inside resolveKey(), and the firebase-functions require itself is deferred so this
 *     module can also be loaded by the admin's Test-connection route without that dep.
 */

const { normalizeGenerateText, normalizeTranslate, normalizeSpeech } = require("./normalize");

const GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models";
const DEFAULT_MODEL = "gemini-2.5-flash";

// Canonical safety block (matches glyphAiAgent.js callGemini). Sending these explicitly
// is behaviorally identical to the prior calls: HARASSMENT/HATE_SPEECH were BLOCK_NONE
// before; SEXUALLY_EXPLICIT/DANGEROUS_CONTENT were omitted (i.e. Gemini defaults =
// BLOCK_MEDIUM_AND_ABOVE), so explicitly setting them to BLOCK_MEDIUM_AND_ABOVE changes
// nothing for benign chat/translation content.
const SAFETY_SETTINGS = [
  { category: "HARM_CATEGORY_HARASSMENT", threshold: "BLOCK_NONE" },
  { category: "HARM_CATEGORY_HATE_SPEECH", threshold: "BLOCK_NONE" },
  { category: "HARM_CATEGORY_SEXUALLY_EXPLICIT", threshold: "BLOCK_MEDIUM_AND_ABOVE" },
  { category: "HARM_CATEGORY_DANGEROUS_CONTENT", threshold: "BLOCK_MEDIUM_AND_ABOVE" },
];

// speechToText.js originally sent ALL FOUR categories as BLOCK_NONE. Preserve that
// exactly for transcription so explicit/edge-case speech is never blocked differently
// than before.
const STT_SAFETY_SETTINGS = [
  { category: "HARM_CATEGORY_HARASSMENT", threshold: "BLOCK_NONE" },
  { category: "HARM_CATEGORY_HATE_SPEECH", threshold: "BLOCK_NONE" },
  { category: "HARM_CATEGORY_SEXUALLY_EXPLICIT", threshold: "BLOCK_NONE" },
  { category: "HARM_CATEGORY_DANGEROUS_CONTENT", threshold: "BLOCK_NONE" },
];

// Lazy key resolver — identical pattern to the old getApiKey() in glyphAiAgent.js.
// The firebase-functions require is deferred (and wrapped in try/catch) so this module
// can be required in contexts where that package is not installed (e.g. the admin route).
function resolveKey(explicitKey) {
  if (explicitKey) return explicitKey;
  const env = process.env.GOOGLE_CLOUD_API_KEY;
  if (env) return env;
  try {
    const functions = require("firebase-functions");
    return functions.config().google?.api_key;
  } catch {
    return null;
  }
}

async function postGenerateContent({ apiKey, model, contents, generationConfig = {}, safetySettings, providerId }) {
  const key = resolveKey(apiKey);
  if (!key) {
    throw new Error("Gemini API key not configured");
  }
  const url = `${GEMINI_BASE}/${model}:generateContent?key=${key}`;

  const body = {
    contents,
    safetySettings: safetySettings || SAFETY_SETTINGS,
    generationConfig: {
      temperature: 0.7,
      maxOutputTokens: 2048,
      ...generationConfig,
    },
  };

  const t0 = Date.now();
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const errText = await response.text();
    console.error(`Gemini API error (${model}):`, errText);
    throw new Error(`Gemini API ${response.status}`);
  }

  const result = await response.json();
  const text = result.candidates?.[0]?.content?.parts?.[0]?.text?.trim();

  if (!text) {
    console.error("Empty Gemini response", JSON.stringify(result).slice(0, 300));
    throw new Error("Empty AI response");
  }

  return { text, latencyMs: Date.now() - t0 };
}

// ─── generateText ─────────────────────────────────────────
// `contents` is forwarded verbatim (the caller assembles the full Gemini-shaped
// contents array, including the system prompt as the first user part + model greeting).
async function generateText(args) {
  const { contents, model = DEFAULT_MODEL, generationConfig = {}, safetySettings, apiKey, providerId } = args;
  const res = await postGenerateContent({
    apiKey,
    model,
    contents,
    generationConfig,
    safetySettings,
    providerId,
  });
  return normalizeGenerateText({ text: res.text, model, latencyMs: res.latencyMs, providerId });
}

// ─── translate ────────────────────────────────────────────
async function translate(args) {
  const { text, targetLanguage, model = DEFAULT_MODEL, apiKey, providerId } = args;

  let prompt;
  if (targetLanguage === "hi-Latn") {
    prompt = `Convert the following text to Romanized Hindi (Hinglish). Write it using English/Latin letters exactly how a Hindi speaker would type in WhatsApp chats. Preserve natural pronunciation. Do NOT use Devanagari script. Return ONLY the romanized Hindi text, nothing else. No explanations, no quotes, no labels.\n\nText: ${text}`;
  } else {
    prompt = `Translate the following text to ${targetLanguage}. Return ONLY the translated text, nothing else. No explanations, no quotes, no labels.\n\nText: ${text}`;
  }

  const res = await postGenerateContent({
    apiKey,
    model,
    contents: [{ parts: [{ text: prompt }] }],
    generationConfig: {},
    providerId,
  });
  return normalizeTranslate({ translatedText: res.text, model, latencyMs: res.latencyMs, providerId });
}

// ─── speechToText ─────────────────────────────────────────
async function speechToText(args) {
  const {
    audioBase64,
    mimeType,
    languageHint,
    targetLanguage,
    model = DEFAULT_MODEL,
    apiKey,
    providerId,
  } = args;

  const wantHinglish = targetLanguage === "hi-Latn";
  const languageInstruction = languageHint ? `The audio is likely in ${languageHint}. ` : "";

  let prompt;
  if (wantHinglish) {
    prompt = `${languageInstruction}Transcribe the following audio into Romanized Hindi (Hinglish / Roman Hindi). Write the Hindi words using English/Latin letters, exactly how a Hindi speaker would type in WhatsApp chats. Preserve natural pronunciation. Do NOT use Devanagari script. Return ONLY the romanized text, nothing else. No explanations, no labels, no quotes. If the audio is unclear or empty, return "[inaudible]".\n\nExamples of expected output style:\n- "mujhe aapse baat karni hai"\n- "kya haal hai bhai"\n- "main kal aa raha hoon"`;
  } else {
    prompt = `${languageInstruction}Transcribe the following audio accurately. Return ONLY the transcribed text, nothing else. No explanations, no labels, no quotes. If the audio is unclear or empty, return "[inaudible]".`;
  }

  const contents = [
    {
      parts: [
        { text: prompt },
        {
          inlineData: {
            mimeType: mimeType || "audio/mp4",
            data: audioBase64,
          },
        },
      ],
    },
  ];

  const res = await postGenerateContent({
    apiKey,
    model,
    contents,
    generationConfig: { temperature: 0.1, maxOutputTokens: 2048 },
    safetySettings: STT_SAFETY_SETTINGS,
    providerId,
  });
  // The handler decides what to do with "[inaudible]"; we pass it through verbatim.
  return normalizeSpeech({ text: res.text, model, latencyMs: res.latencyMs, providerId });
}

// ─── ocr ──────────────────────────────────────────────────
// No M2 Cloud Function calls this yet, but the module method is wired so the
// capability routes correctly when it is added.
async function ocr(args) {
  const { imageBase64, mimeType = "image/jpeg", prompt, model = DEFAULT_MODEL, apiKey, providerId } = args;

  const ocrPrompt =
    prompt || "Extract all text from this image. Return ONLY the transcribed text.";

  const contents = [
    {
      parts: [
        { text: ocrPrompt },
        { inlineData: { mimeType, data: imageBase64 } },
      ],
    },
  ];

  const res = await postGenerateContent({
    apiKey,
    model,
    contents,
    generationConfig: {},
    providerId,
  });
  return normalizeSpeech({ text: res.text, model, latencyMs: res.latencyMs, providerId });
}

// ─── testConnection ────────────────────────────────────────
async function testConnection(args = {}) {
  const { apiKey, model = DEFAULT_MODEL, capability = "llm" } = args;
  if (capability === "image") {
    return {
      ok: false,
      capability,
      detail: "Gemini does not support image generation",
      latencyMs: 0,
    };
  }
  const key = resolveKey(apiKey);
  if (!key) {
    return { ok: false, capability, detail: "Gemini API key not configured", latencyMs: 0 };
  }
  const t0 = Date.now();
  try {
    const res = await fetch(`${GEMINI_BASE}/${model}:generateContent?key=${key}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        contents: [{ parts: [{ text: "Reply with the single word: OK" }] }],
        generationConfig: { temperature: 0, maxOutputTokens: 8 },
      }),
    });
    const latencyMs = Date.now() - t0;
    if (!res.ok) return { ok: false, capability, latencyMs, detail: `HTTP ${res.status}` };
    return { ok: true, capability, latencyMs, detail: "Connected to Gemini" };
  } catch (e) {
    return {
      ok: false,
      capability,
      latencyMs: Date.now() - t0,
      detail: (e && e.message) || "Network error",
    };
  }
}

module.exports = {
  generateText,
  translate,
  speechToText,
  ocr,
  testConnection,
  // exported for completeness / future use
  SAFETY_SETTINGS,
};
