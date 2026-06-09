/**
 * Firebase Cloud Function: analyzeSentiment
 *
 * Wraps Google Cloud Natural Language API for sentiment analysis.
 * Called by the Android app's SentimentAnalysisService.
 *
 * Deploy: firebase deploy --only functions
 *
 * Request: { text: "Hello world" }
 * Response: { score: 0.8, magnitude: 1.2 }
 *   - score: -1.0 (very negative) to 1.0 (very positive)
 *   - magnitude: 0+ (intensity of emotion)
 */
const functions = require("firebase-functions");
const language = require("@google-cloud/language");

const client = new language.LanguageServiceClient();

exports.analyzeSentiment = functions.https.onCall(async (data, context) => {
  // Authentication check (optional but recommended)
  if (!context.auth) {
    throw new functions.https.HttpsError(
      "unauthenticated",
      "Must be authenticated to analyze sentiment."
    );
  }

  const text = data.text;
  if (!text || typeof text !== "string" || text.trim().length < 3) {
    return { score: 0, magnitude: 0 };
  }

  // Truncate very long text to save API quota
  const truncated = text.substring(0, 500);

  try {
    const document = {
      content: truncated,
      type: "PLAIN_TEXT",
    };

    const [result] = await client.analyzeSentiment({ document });
    const sentiment = result.documentSentiment;

    return {
      score: sentiment.score || 0,
      magnitude: sentiment.magnitude || 0,
    };
  } catch (error) {
    console.error("Sentiment analysis error:", error);
    // Return neutral on error rather than failing the call
    return { score: 0, magnitude: 0 };
  }
});
