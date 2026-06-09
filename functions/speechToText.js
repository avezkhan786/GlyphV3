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

    // 2. API key
    const apiKey = "AIzaSyAVZ22mqebWYT3I9QbFXGXfiJV7SkOWmfE";
    if (!apiKey) {
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

    // 5. Speech-to-Text using Gemini 2.5 Flash (multimodal)
    // Gemini can directly process audio and transcribe it
    let recognizedText = null;
    // Check if Hinglish (romanized Hindi) is requested
    const wantHinglish = targetLanguage === "hi-Latn";
    try {
      console.log("Calling Gemini API for speech recognition...");
      const tSttStart = Date.now();

      const languageInstruction = languageHint
        ? `The audio is likely in ${languageHint}. `
        : "";

      let prompt;
      if (wantHinglish) {
        // Directly transcribe into Romanized Hindi (Hinglish)
        prompt = `${languageInstruction}Transcribe the following audio into Romanized Hindi (Hinglish / Roman Hindi). Write the Hindi words using English/Latin letters, exactly how a Hindi speaker would type in WhatsApp chats. Preserve natural pronunciation. Do NOT use Devanagari script. Return ONLY the romanized text, nothing else. No explanations, no labels, no quotes. If the audio is unclear or empty, return "[inaudible]".

Examples of expected output style:
- "mujhe aapse baat karni hai"
- "kya haal hai bhai"
- "main kal aa raha hoon"`;
      } else {
        prompt = `${languageInstruction}Transcribe the following audio accurately. Return ONLY the transcribed text, nothing else. No explanations, no labels, no quotes. If the audio is unclear or empty, return "[inaudible]".`;
      }

      const response = await fetch(
        `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${apiKey}`,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            contents: [
              {
                parts: [
                  { text: prompt },
                  {
                    inlineData: {
                      mimeType: "audio/mp4",
                      data: audioBase64,
                    },
                  },
                ],
              },
            ],
            generationConfig: {
              temperature: 0.1, // Low temperature for accurate transcription
              maxOutputTokens: 2048,
            },
            safetySettings: [
              {
                category: "HARM_CATEGORY_HARASSMENT",
                threshold: "BLOCK_NONE",
              },
              {
                category: "HARM_CATEGORY_HATE_SPEECH",
                threshold: "BLOCK_NONE",
              },
              {
                category: "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                threshold: "BLOCK_NONE",
              },
              {
                category: "HARM_CATEGORY_DANGEROUS_CONTENT",
                threshold: "BLOCK_NONE",
              },
            ],
          }),
        }
      );

      const tSttEnd = Date.now();
      timings.stt = tSttEnd - tSttStart;

      if (!response.ok) {
        const errorText = await response.text();
        console.error("Gemini STT API error:", errorText);
        throw new Error(
          `Gemini API error: ${response.status} ${response.statusText}`
        );
      }

      const result = await response.json();
      recognizedText = result.candidates?.[0]?.content?.parts?.[0]?.text?.trim();

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

        let translatePrompt;
        // Special handling for translation TO Hinglish from non-Hindi audio
        if (targetLanguage === "hi-Latn") {
          translatePrompt = `Convert the following text to Romanized Hindi (Hinglish). Write it using English/Latin letters exactly how a Hindi speaker would type in WhatsApp. Do NOT use Devanagari script. Return ONLY the romanized Hindi text.\n\nText: ${recognizedText}`;
        } else {
          translatePrompt = `Translate the following text to ${targetLanguage}. Return ONLY the translated text, nothing else. No explanations, no quotes, no labels.\n\nText: ${recognizedText}`;
        }

        const translateResponse = await fetch(
          `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${apiKey}`,
          {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
            },
            body: JSON.stringify({
              contents: [
                {
                  parts: [
                    {
                      text: translatePrompt,
                    },
                  ],
                },
              ],
              safetySettings: [
                {
                  category: "HARM_CATEGORY_HARASSMENT",
                  threshold: "BLOCK_NONE",
                },
                {
                  category: "HARM_CATEGORY_HATE_SPEECH",
                  threshold: "BLOCK_NONE",
                },
              ],
            }),
          }
        );

        timings.translation = Date.now() - tTranslateStart;

        if (!translateResponse.ok) {
          console.error(
            "Translation API error:",
            await translateResponse.text()
          );
          // Don't fail the whole request, just return without translation
          translatedText = null;
        } else {
          const translateResult = await translateResponse.json();
          translatedText =
            translateResult.candidates?.[0]?.content?.parts?.[0]?.text?.trim();
          console.log(
            "Translation successful:",
            (translatedText || "").substring(0, 50)
          );
        }
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
