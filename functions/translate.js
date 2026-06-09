/**
 * translate.js — Cloud Function for text translation + TTS
 *
 * Endpoints:
 *   translateMessage(text, targetLanguage, userId)
 *     → { translatedText, audioUrl, cached }
 *
 * Backend flow:
 *   1. Validate input (max 500 chars, rate limit)
 *   2. Check Firestore cache by hash(text + targetLanguage)
 *   3. If cached → return immediately
 *   4. Call Gemini API for translation
 *   5. Call Google Cloud TTS for MP3
 *   6. Upload MP3 to Firebase Storage
 *   7. Cache result in Firestore
 *   8. Return result
 */

const functions = require("firebase-functions");
const admin = require("firebase-admin");
const crypto = require("crypto");

// Firebase Admin is initialized in index.js

// No need for heavy SDK imports - using direct REST API calls

// ─── Helpers ───────────────────────────────────────────────

// Cache version - increment to invalidate all old caches
// v9: Inline audio + fixed cache key collision
const CACHE_VERSION = 9;

/**
 * Generate a deterministic cache key from text + language + version.
 */
function cacheKey(text, targetLanguage) {
  // Use original text case for key to avoid "same text different case" collisions if needed, 
  // but translation is usually case-sensitive.
  // CRITICAL FIX: Ensure targetLanguage is part of the hash!
  const normalized = text.trim();
  return crypto
    .createHash("sha256")
    .update(`${normalized}::${targetLanguage}::${CACHE_VERSION}`)
    .digest("hex");
}

/**
 * Gemini 2.5 Flash TTS uses universal constellation-named voices.
 * "aoede" is a high-quality multilingual voice that works across all languages.
 */
const UNIVERSAL_VOICE = "aoede";

function getVoiceConfig(langCode) {
  return { name: UNIVERSAL_VOICE };
}

/**
 * Add WAV header to raw PCM audio data.
 * Gemini TTS returns raw PCM, we need proper WAV format for Android playback.
 */
function addWavHeader(pcmData) {
  // Check if it already has a RIFF header (Gemini sometimes returns WAV)
  if (pcmData.length >= 12 && 
      pcmData.subarray(0, 4).toString() === 'RIFF' &&
      pcmData.subarray(8, 12).toString() === 'WAVE') {
        console.log("Data already has WAV header, returning as is");
        return pcmData;
  }

  // Check for MP3 sync frame (0xFF 0xFB or similar) or ID3 header
  // ID3v2 container: starts with 'ID3'
  if (pcmData.length >= 3 && pcmData.subarray(0, 3).toString() === 'ID3') {
    console.log("Data has ID3 header (MP3), returning as is");
    return pcmData;
  }
  
  // MP3 Sync frame: First byte 0xFF, second byte & 0xE0 == 0xE0 (usually 0xFB or 0xF3)
  if (pcmData.length >= 2 && pcmData[0] === 0xFF && (pcmData[1] & 0xE0) === 0xE0) {
    console.log("Data has MP3 sync frame, returning as is");
    return pcmData;
  }

  const sampleRate = 24000; // Gemini TTS default sample rate
  const numChannels = 1; // Mono
  const bitsPerSample = 16; // 16-bit PCM
  
  const dataSize = pcmData.length;
  const header = Buffer.alloc(44);
  
  // RIFF header
  header.write('RIFF', 0);
  header.writeUInt32LE(36 + dataSize, 4); // File size - 8
  header.write('WAVE', 8);
  
  // fmt chunk
  header.write('fmt ', 12);
  header.writeUInt32LE(16, 16); // fmt chunk size
  header.writeUInt16LE(1, 20); // Audio format (1 = PCM)
  header.writeUInt16LE(numChannels, 22); // Number of channels
  header.writeUInt32LE(sampleRate, 24); // Sample rate
  header.writeUInt32LE(sampleRate * numChannels * bitsPerSample / 8, 28); // Byte rate
  header.writeUInt16LE(numChannels * bitsPerSample / 8, 32); // Block align
  header.writeUInt16LE(bitsPerSample, 34); // Bits per sample
  
  // data chunk
  header.write('data', 36);
  header.writeUInt32LE(dataSize, 40);
  
  return Buffer.concat([header, pcmData]);
}

// ─── Rate limiter (simple per-user, per-minute) ───────────

const RATE_LIMIT_WINDOW_MS = 60 * 1000;
const RATE_LIMIT_MAX = 20; // max 20 requests per minute per user

async function checkRateLimit(userId) {
  const db = admin.firestore();
  const ref = db.collection("rate_limits").doc(userId);
  const now = Date.now();

  return db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    const data = snap.data() || { count: 0, windowStart: now };

    if (now - data.windowStart > RATE_LIMIT_WINDOW_MS) {
      // Reset window
      tx.set(ref, { count: 1, windowStart: now });
      return true;
    }

    if (data.count >= RATE_LIMIT_MAX) {
      return false; // Rate limited
    }

    // Use set with merge to handle both new and existing documents
    if (snap.exists) {
      tx.update(ref, { count: admin.firestore.FieldValue.increment(1) });
    } else {
      tx.set(ref, { count: 1, windowStart: now });
    }
    return true;
  });
}

// ─── Main Cloud Function ──────────────────────────────────

exports.translateMessage = functions
  .runWith({
    timeoutSeconds: 60,
    memory: "1GB",
  })
  .https.onCall(async (data, context) => {
    const tStart = Date.now();
    const timings = {};
    
    console.log("=== Translation request started ===");
    console.log("User ID:", context.auth?.uid);
    console.log("Request data:", JSON.stringify(data));
    
    // 1. Auth check - use auth if available, fallback to IP-based rate limiting
    const userId = context.auth?.uid || `anon_${context.rawRequest?.ip || 'unknown'}`;
    console.log("Using userId for rate limiting:", userId);

    // 2. Validate API key is configured
    // Override triggered by user request: force specific hardcoded key
    const apiKey = "AIzaSyAVZ22mqebWYT3I9QbFXGXfiJV7SkOWmfE";
    // const apiKey = functions.config().google?.api_key || process.env.GOOGLE_CLOUD_API_KEY;
    if (!apiKey) {
      console.error("API key not configured");
      throw new functions.https.HttpsError(
        "internal",
        "Google Cloud API key not configured. Run: firebase functions:config:set google.api_key=\"YOUR_KEY\""
      );
    }

    // 2. Validate input
    const { text, targetLanguage, skipAudio } = data;
    
    // Quick Warmup Handler
    if (text === "warmup") {
       console.log("Warmup ping received - forcing hot start");
       return { 
         translatedText: "Warmup acknowledged", 
         audioUrl: null, 
         cached: false,
         timings: { total: Date.now() - tStart }
       };
    }
    
    console.log("Validating input - text length:", text?.length, "targetLang:", targetLanguage, "skipAudio:", skipAudio);
    
    if (!text || typeof text !== "string" || text.trim().length === 0) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "Text is required."
      );
    }
    if (text.length > 500) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "Text must be 500 characters or fewer."
      );
    }
    if (
      !targetLanguage ||
      typeof targetLanguage !== "string" ||
      targetLanguage.length > 10
    ) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "Valid targetLanguage is required."
      );
    }
    
    const tInputValidated = Date.now();
    timings.validation = tInputValidated - tStart;

    // 3. Rate limit
    const allowed = await checkRateLimit(userId);
    if (!allowed) {
      throw new functions.https.HttpsError(
        "resource-exhausted",
        "Rate limit exceeded. Try again in a minute."
      );
    }
    const tRateLimit = Date.now();
    timings.rateLimit = tRateLimit - tInputValidated;

    // 4. Check cache
    const db = admin.firestore();
    // Use targetLanguage in key generation (now fixed in helper above)
    const key = cacheKey(text, targetLanguage);
    console.log("Cache key:", key, "for lang:", targetLanguage);
    const cacheRef = db.collection("translation_cache").doc(key);
    const cached = await cacheRef.get();
    
    // Initialize translatedText variable correctly
    let translatedText = null;

    const tCacheCheck = Date.now();
    timings.cacheCheck = tCacheCheck - tRateLimit;

    if (cached.exists) {
      const cachedData = cached.data();
      
      // Safety check: Does the cached language actually match the requested one?
      if (cachedData.targetLanguage !== targetLanguage) {
         console.warn("Cache COLLISION DETECTED! Requested:", targetLanguage, "Cached:", cachedData.targetLanguage);
         // Treat as miss
      }
      // If we have a valid cache version
      else if (cachedData.version === CACHE_VERSION) {

        // Case A: Full cache? 
        // NOTE: We switched to inline audio, so 'audioUrl' in cache might be null for new entries.
        // If we want to return cached audio, we'd need to have stored the Base64 in Firestore, 
        // which we decided NOT to do to save space.
        // Therefore, for Audio requests (skipAudio=false), we MUST regenerate if we don't store it.
        // Unless... we use the old 'audioUrl' if it exists from previous versions?
        
        // Strategy:
        // 1. If skipAudio=true: Return text from cache.
        // 2. If skipAudio=false: 
        //    - If we have a legacy audioUrl, return it? (Client might support it).
        //    - If NO audioUrl (text-only cache), proceed to generation.
        
        if (skipAudio && cachedData.translatedText) {
             console.log("Cache hit (text only) - returning cached translation");
             timings.total = Date.now() - tStart;
             return {
               translatedText: cachedData.translatedText,
               audioUrl: null,
               cached: true,
               timings
             };
        }
        
        // If we overlap with a legacy entry that has a storage URL, we can return it
        if (cachedData.audioUrl && cachedData.audioUrl.startsWith("http")) {
             console.log("Cache hit - returning legacy Storage URL");
             timings.total = Date.now() - tStart;
             return {
                translatedText: cachedData.translatedText,
                audioUrl: cachedData.audioUrl, 
                cached: true,
                timings
             };
        }
        
        // Otherwise, if we have text but need audio, reuse the text
        if (cachedData.translatedText) {
           console.log("Partial Cache hit - reusing text, regenerating audio");
           translatedText = cachedData.translatedText;
        }

      } else {
        console.log("Cache hit but version mismatch - regenerating (old:", cachedData.version, "new:", CACHE_VERSION, ")");
      }
    }
    console.log("Cache miss (or partial) - proceeding with API calls");

    // 5. Translate via Gemini API (direct REST call)
    // Only if we don't already have the text from partial cache
    if (!translatedText) {
      try {
        console.log("Calling Gemini API for translation...");
        const tGeminiStart = Date.now();
        
        // Special handling for Hinglish (romanized Hindi)
        let prompt;
        if (targetLanguage === "hi-Latn") {
          prompt = `Convert the following text to Romanized Hindi (Hinglish). Write it using English/Latin letters exactly how a Hindi speaker would type in WhatsApp chats. Preserve natural pronunciation. Do NOT use Devanagari script. Return ONLY the romanized Hindi text, nothing else. No explanations, no quotes, no labels.\n\nText: ${text}`;
        } else {
          prompt = `Translate the following text to ${targetLanguage}. Return ONLY the translated text, nothing else. No explanations, no quotes, no labels.\n\nText: ${text}`;
        }
  
        const response = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${apiKey}`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            contents: [{
              parts: [{
                text: prompt
              }]
            }],
            safetySettings: [
              {
                category: "HARM_CATEGORY_HARASSMENT",
                threshold: "BLOCK_NONE"
              },
              {
                category: "HARM_CATEGORY_HATE_SPEECH", 
                threshold: "BLOCK_NONE"
              }
            ]
          })
        });
        
        const tGeminiEnd = Date.now();
        timings.gemini = tGeminiEnd - tGeminiStart;
  
        if (!response.ok) {
          const errorText = await response.text();
          console.error("Gemini API error response:", errorText);
          throw new Error(`Gemini API error: ${response.status} ${response.statusText}`);
        }
  
        const result = await response.json();
        translatedText = result.candidates?.[0]?.content?.parts?.[0]?.text?.trim();
  
        if (!translatedText) {
          console.error("Empty translation result from Gemini");
          throw new Error("Empty translation result");
        }
        console.log("Translation successful:", translatedText.substring(0, 50) + "...");
      } catch (err) {
        console.error("Gemini translation error:", err.message, err.stack);
        throw new functions.https.HttpsError(
          "internal",
          "Translation failed. Please try again."
        );
      }
    }
    
    // If client requested to skip audio, we can stop here and return/cache the text
    if (skipAudio) {
       console.log("Audio generation skipped by request. Caching text only.");
       const tCacheWriteStart = Date.now();
       // Update cache with text only (merge to avoid overwriting if something else changed, though unlikely)
       try {
          await cacheRef.set({
            originalText: text,
            translatedText,
            targetLanguage,
            audioUrl: null, // explicit null
            version: CACHE_VERSION,
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
            createdBy: userId,
          }, { merge: true });
       } catch (e) { console.error("Cache write error:", e); }
       
       timings.cacheWrite = Date.now() - tCacheWriteStart;
       timings.total = Date.now() - tStart;
       
       return {
         translatedText,
         audioUrl: null,
         cached: false,
         timings
       };
    }

    // 6. Generate TTS MP3 using Gemini 2.5 Flash TTS
    let audioUrl;
    try {
      console.log("Calling Gemini 2.5 Flash TTS API...");
      const tTtsStart = Date.now();

      console.log("Generating audio for:", translatedText.substring(0, 50));

      const ttsResponse = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent?key=${apiKey}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          contents: [{
            parts: [{
              text: translatedText
            }]
          }],
          generationConfig: {
            responseModalities: ["AUDIO"],
            speechConfig: {
              voiceConfig: {
                prebuiltVoiceConfig: {
                  voiceName: getVoiceConfig(targetLanguage).name
                }
              }
            }
          }
        })
      });

      if (!ttsResponse.ok) {
        const errorText = await ttsResponse.text();
        console.error("❌ TTS API ERROR ❌");
        console.error("Status:", ttsResponse.status, ttsResponse.statusText);
        console.error("Response:", errorText);
        throw new Error(`TTS API error: ${ttsResponse.status} ${ttsResponse.statusText}`);
      }

      const ttsResult = await ttsResponse.json();
      console.log("📦 Full TTS Response Structure:", JSON.stringify(ttsResult, null, 2));
      
      const audioContent = ttsResult.candidates?.[0]?.content?.parts?.find(part => part.inlineData)?.inlineData?.data;

      if (!audioContent) {
        console.error("❌ NO AUDIO CONTENT IN TTS RESPONSE ❌");
        console.error("Full TTS result:", JSON.stringify(ttsResult, null, 2));
        throw new Error("No audio content received from TTS API");
      }
      
      // Decode the base64 PCM audio
      const pcmBuffer = Buffer.from(audioContent, 'base64');
      console.log("🔍 Raw PCM Audio Buffer:");
      console.log("  - Base64 length:", audioContent.length);
      console.log("  - Decoded PCM size:", pcmBuffer.length, "bytes");
      console.log("  - First 16 bytes (raw PCM):", Array.from(pcmBuffer.slice(0, 16)).map(b => b.toString(16).padStart(2, '0')).join(' '));
      
      // Check if buffer is completely empty
      if (pcmBuffer.length === 0) {
        console.error("❌ AUDIO BUFFER IS EMPTY ❌");
        throw new Error("TTS returned empty audio data");
      }
      
      // Add WAV header to the raw PCM data
      console.log("Adding WAV header to raw PCM audio...");
      const wavBuffer = addWavHeader(pcmBuffer);
      console.log("✅ WAV file created:", wavBuffer.length, "bytes");
      console.log("  - WAV header (first 16 bytes):", Array.from(wavBuffer.slice(0, 16)).map(b => b.toString(16).padStart(2, '0')).join(' '));
      
      // Re-encode as base64
      const wavBase64 = wavBuffer.toString('base64');
      console.log("✅ TTS audio with WAV header, base64 size:", wavBase64.length);

      
      // Inline Audio Optimization: Skip storage upload, return base64 directly
      console.log("Returning inline base64 audio (skipping Storage upload for speed)");
      timings.tts = Date.now() - tTtsStart;
      timings.total = Date.now() - tStart;

      // 8. Cache result in Firestore (store base64? No, too large for Firestore limit 1MB per doc)
      // Actually, we should probably NOT cache audio in Firestore if we aren't using Storage.
      // But if we don't cache audio, every request will regenerate it.
      // Trade-off: Regeneration vs Storage latency.
      // Since user wants SPEED, re-generation (expensive) might be faster than Storage fetch?
      // But re-generation costs money.
      // Let's compromise: We WON'T cache audio in Firestore for now, relying on Client-side caching.
      // Client has Room DB and local file storage.
      // If client clears cache, we regenerate.

      try {
        console.log("Caching text-only result in Firestore...");
        await cacheRef.set({
            originalText: text,
            translatedText,
            targetLanguage,
            audioUrl: null, // No public URL
            version: CACHE_VERSION,
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
            createdBy: userId,
        });
      } catch (err) {
        console.error("Cache write error:", err);
      }

      console.log("=== Translation request completed successfully ===");
      console.log("Returning inline audio content length:", wavBase64.length);
      
      return {
        translatedText,
        audioContent: wavBase64, // Base64 string with WAV header
        audioUrl: null,
        cached: false,
        timings
      };

    } catch (err) {
      console.error("❌ TTS/Storage ERROR ❌");
      console.error("Error type:", err.constructor.name);
      console.error("Error message:", err.message);
      console.error("Stack trace:", err.stack);
      console.error("Translated text:", translatedText?.substring(0, 100));
      console.error("Target language:", targetLanguage);
      
      // Translation succeeded but TTS failed — still return translation
      return {
          translatedText,
          audioContent: null,
          audioUrl: null,
          cached: false,
          timings,
          error: `TTS failed: ${err.message}` // Include error for debugging
      };
    }
  });
