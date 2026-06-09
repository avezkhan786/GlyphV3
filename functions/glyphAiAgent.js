/**
 * glyphAiAgent – Global AI Agent Cloud Function
 *
 * Stateless callable that receives the full context window from the client
 * and forwards it to the Gemini API. Zero persistent Firebase writes.
 *
 * Modes:
 *   chat   – general conversational AI (multi-turn)
 *   search – chat intelligence: searches user's Firestore messages,
 *            synthesises answer with source citations
 *   app    – app intelligence: answers questions about Glyph features
 *
 * Request shape (from Android AiAgentRepository):
 *   { mode, message, contents: [{ role, text }], options?: { ... } }
 *
 * Response shape:
 *   { reply, mode, sources?: [...], navigationHint?: string, cached: bool }
 */

const functions = require("firebase-functions");
const admin = require("firebase-admin");

// ─── Constants ────────────────────────────────────────────

const API_KEY = "AIzaSyAVZ22mqebWYT3I9QbFXGXfiJV7SkOWmfE";
const GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models";

// Models – cost-optimized per mode
const MODEL_CHAT = "gemini-2.5-flash";          // fast general chat
const MODEL_SEARCH = "gemini-2.5-flash";        // stronger reasoning for search synthesis
const MODEL_APP = "gemini-2.5-flash";           // simple look-ups

const MAX_MESSAGE_LENGTH = 2000;
const MAX_SEARCH_CHATS = 10;        // limit chats queried for search
const MAX_SEARCH_MSGS_PER_CHAT = 50; // messages sampled per chat
const MAX_SEARCH_RESULTS = 15;       // results fed to synthesiser

// Rate-limit: per-user in-memory (resets on cold start, good enough)
const rateLimitMap = new Map();
const RATE_LIMIT_WINDOW_MS = 60_000;
const RATE_LIMIT_MAX = 20; // 20 requests/min

// Per-request timezone — set from client options at the start of each request.
// Defaults to UTC if client doesn't provide one. Reset per invocation.
let _userTimezone = "UTC";
let _userTzLabel = "UTC";

// ─── Safety settings (match enhanceMessage.js) ───────────

const SAFETY_SETTINGS = [
  { category: "HARM_CATEGORY_HARASSMENT", threshold: "BLOCK_NONE" },
  { category: "HARM_CATEGORY_HATE_SPEECH", threshold: "BLOCK_NONE" },
  { category: "HARM_CATEGORY_SEXUALLY_EXPLICIT", threshold: "BLOCK_MEDIUM_AND_ABOVE" },
  { category: "HARM_CATEGORY_DANGEROUS_CONTENT", threshold: "BLOCK_MEDIUM_AND_ABOVE" },
];

// ─── App Knowledge (inline, lightweight) ──────────────────

const APP_KNOWLEDGE = require("./app_knowledge.json");

// ─── Intent detection for smart auto-routing ────────────

// These questions should STAY in Chat mode because the chat context already has the data.
// They should NOT be routed to Search mode where keyword matching fails.
const CHAT_PRIORITY_SIGNALS = [
  // "Who messaged me last?", "When did X message me?", "Did X text me?"
  /\b(who\s+(messaged|texted|sent|contacted|wrote|msg|dm)\s+(me|us))\b/i,
  /\b(when\s+did\s+.+?\s+(message[d]?|text(?:ed)?|send|sent|contact(?:ed)?|writ(?:e|ten)|wrote|msg|dm))\b/i,
  /\b(did\s+.+?\s+(message[d]?|text(?:ed)?|msg|dm|contact(?:ed)?)\s+me)\b/i,
  
  // "My last message to X", "What was my last message to X"
  /\b(my\s+(last|latest|recent)\s+(message|text|msg)\s+(to|for))\b/i,
  /\b(what\s+(was|is)\s+my\s+(last|latest|recent)\s+(message|text|msg))\b/i,
  
  // "What was X's last message?", "What did X last say?"
  /\b(what\s+(was|is)\s+.+?'?s?\s+(last|latest|recent|newest)\s+(message|text|msg))\b/i,
  /\b(last|latest|recent|newest)\s+(message|text|msg)\s+(from|by|of)\b/i,
  
  // "When did X last message?", "X's last message"
  /\b(.+'s\s+(last|latest|recent)\s+(message|text|msg))\b/i,
  /\b(last\s+(time|msg|message|text)\s+(from|by|with))\b/i,
  
  // "How many unread messages?", "Any new messages?"
  /\b(unread|new)\s+(message|text|msg|chat)/i,
  /\b(how\s+many\s+(unread|new|messages|chats|media|image|photo|video|file))\b/i,
  
  // "Who do I talk to most?", "My most active chat?"
  /\b(who\s+do\s+I\s+(talk|chat|message|text))\b/i,
  /\b(most\s+(active|recent|frequent)\s+(chat|conversation|contact))\b/i,
  
  // "What was the last thing X sent?", "Show me X's messages"
  /\b(what\s+(was|is)\s+the\s+(last|latest)\s+(thing|message|text)\s+.+?\s+(sent|said|wrote))\b/i,
  /\b(show\s+me\s+.+?'?s?\s+(message|chat|conversation))\b/i,
  
  // "When did I last talk to X?"
  /\b(when\s+did\s+(I|we)\s+(last|recently)\s+(talk|chat|speak|message))\b/i,
  
  // "What the last message from X" / "What was the last message"
  /\b(what\s+(the|was)\s+(last|latest|recent)\s+(message|text|msg))\b/i,
];

const SEARCH_SIGNALS = [
  // Explicit search commands
  /\b(search|find|look\s*(for|up|through))\b.*\b(chat|conversation|message|history|sent|received|shared|forwarded|file|link|photo|video)/i,
  
  // "What did X say..." (content search)
  /\b(what\s+(did|was)\s+(he|she|they|it|[a-z]+)\s+(say|said|send|sent|wrote|written|message|text))\b(?!\s+(to|about|summary))/i,
  
  // "Find the pdf/link/photo..."
  /\b(find|show|get)\b.*\b(pdf|document|file|link|url|photo|image|picture|video|attachment)/i,
  
  // "Check if X sent..."
  /\b(did\s+(he|she|they|[a-z]+)\s+(send|mention|say|share))\b/i,
  
  // Scoped search
  /\b(in\s+my\s+chats?|in\s+our\s+conversation|from\s+my\s+messages?)/i,

  // Summarization intent -> Route to Search (since it handles deep history fetching reliably)
  /\b(summarize|recap|catch\s*up|overview)\b.*(chat|conversation|with|about)/i,
];

const APP_SIGNALS = [
  /\b(how\s+(do|to|can)\s+I)\b.*\b(in\s+(the\s+)?app|in\s+glyph|setting|feature|theme|wallpaper|notification)/i,
  /\b(where\s+is|where\s+do\s+I\s+find|how\s+to\s+(enable|disable|turn|change|set))/i,
  /\b(app\s+(feature|setting|help)|navigate|navigation)/i,
  /\b(what\s+(does|is)\s+(this|the)\s+(feature|button|setting|option))/i,
  /\b(expressive\s+typing|ai\s+compose|swipe.*reply|buzz|archive)/i,
];

// Casual / conversational messages that should go back to Chat mode
// when the user is currently in Search or App Help
const CASUAL_SIGNALS = [
  /^\s*(thanks|thank\s*you|thx|ty|great|awesome|perfect|nice|cool|ok|okay|got\s*it|understood|alright)\b/i,
  /^\s*(hi|hello|hey|sup|yo|good\s*(morning|afternoon|evening|night))\b/i,
  /^\s*(what'?s?\s*up|how\s+are\s+you|how'?s?\s*it\s*going|wassup)\b/i,
  /^\s*(tell\s+me\s+(about|a)\b|can\s+you|do\s+you|what\s+is\s+(?!this\s+(feature|button|setting)))/i,
  /^\s*(who\s+are\s+you|what\s+can\s+you\s+do|help\s*$)/i,
  /^\s*(lol|haha|hehe|lmao|😂|😊|👍|🙏|❤️)/i,
  /^\s*(yes|no|yeah|nah|nope|yep|sure|definitely|absolutely|exactly)\s*[.!?]*\s*$/i,
];

/**
 * Returns true if the message looks like casual conversation
 * (not a search query or app question).
 */
function isCasualMessage(message) {
  // Very short messages (≤ 4 words, no question mark) are likely casual
  const wordCount = message.trim().split(/\s+/).length;
  if (wordCount <= 3 && !message.includes("?")) {
    // Check it's not a short search like "find certificate"
    for (const re of SEARCH_SIGNALS) {
      if (re.test(message)) return false;
    }
    for (const re of APP_SIGNALS) {
      if (re.test(message)) return false;
    }
    return true;
  }
  for (const re of CASUAL_SIGNALS) {
    if (re.test(message)) return true;
  }
  return false;
}

/**
 * Detects if the user's message intends a different mode than selected.
 * Returns the detected mode or null if no redirect needed.
 */
function detectIntent(message, currentMode) {
  // If user is in Search or App but sends casual/conversational message → route to Chat
  if (currentMode === "search" || currentMode === "app") {
    if (isCasualMessage(message)) return "chat";
    return null;
  }

  // From Chat mode → first check if this is a question that Chat mode handles BETTER
  // (contact-about questions, last message questions, unread checks, etc.)
  // These should NEVER be routed to Search because Chat mode has the structured context.
  for (const re of CHAT_PRIORITY_SIGNALS) {
    if (re.test(message)) {
      console.log(`[INTENT] Chat-priority pattern matched — keeping in chat mode`);
      return null; // Stay in chat mode
    }
  }

  // Then check for search or app intent
  for (const re of SEARCH_SIGNALS) {
    if (re.test(message)) return "search";
  }
  for (const re of APP_SIGNALS) {
    if (re.test(message)) return "app";
  }
  return null;
}

// ─── Rate Limiter ─────────────────────────────────────────

function checkRateLimit(userId) {
  const now = Date.now();
  const entry = rateLimitMap.get(userId);
  if (!entry || now - entry.windowStart > RATE_LIMIT_WINDOW_MS) {
    rateLimitMap.set(userId, { windowStart: now, count: 1 });
    return;
  }
  entry.count++;
  if (entry.count > RATE_LIMIT_MAX) {
    throw new functions.https.HttpsError(
      "resource-exhausted",
      "Rate limit exceeded. Please wait a moment."
    );
  }
}

// ─── Gemini API helpers ───────────────────────────────────

async function callGemini(model, contents, generationConfig = {}) {
  const url = `${GEMINI_BASE}/${model}:generateContent?key=${API_KEY}`;

  const body = {
    contents,
    safetySettings: SAFETY_SETTINGS,
    generationConfig: {
      temperature: 0.7,
      maxOutputTokens: 2048,
      ...generationConfig,
    },
  };

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

  return text;
}

// ─── Media-type label helper ──────────────────────────────

/**
 * Converts a Firestore message document into a human-readable content label.
 * For text messages returns the text; for media returns a descriptive tag
 * like "[Image]", "[Video]", "[Voice message]", etc.
 * This ensures the AI treats every message type equally.
 */
function formatMessageContent(msg) {
  const t = (msg.type || "").toUpperCase();
  const hasText = msg.text && msg.text.trim().length > 0;
  const NON_TEXT_TYPES = new Set(["IMAGE","VIDEO","AUDIO","DOCUMENT","FILE","STICKER","GIF","MEDIA_GROUP","LOCATION","CONTACT","MEME","KLIPY_EMOJI"]);

  // If the type IS text (or unset), just return the text
  if (!NON_TEXT_TYPES.has(t)) {
    return hasText ? msg.text : "[Message]";
  }

  // Non-text type — build a media label, and append caption if present
  let label;
  switch (t) {
    case "IMAGE":       label = "[Image]"; break;
    case "VIDEO":       label = "[Video]"; break;
    case "AUDIO":       label = "[Voice message]"; break;
    case "DOCUMENT":
    case "FILE":        label = msg.fileName ? `[File: ${msg.fileName}]` : "[File]"; break;
    case "STICKER":     label = "[Sticker]"; break;
    case "GIF":         label = "[GIF]"; break;
    case "MEDIA_GROUP": label = "[Media album]"; break;
    case "LOCATION":    label = "[Location]"; break;
    case "CONTACT":     label = "[Shared contact]"; break;
    case "MEME":        label = "[Meme]"; break;
    case "KLIPY_EMOJI": label = "[Klipy emoji]"; break;
    default:            label = "[Media]"; break;
  }
  // Append caption for captioned media (e.g., image with text)
  if (hasText) {
    label += ` caption: "${msg.text.slice(0, 120)}"`;
  }
  return label;
}

/**
 * Robustly converts any Firestore timestamp format to milliseconds epoch.
 * Handles: Firestore Timestamp objects, plain numbers, and {_seconds} maps.
 */
function toMillis(ts) {
  if (!ts) return 0;
  if (typeof ts.toMillis === 'function') return ts.toMillis();
  if (typeof ts === 'number') return ts;
  if (ts._seconds) return ts._seconds * 1000;
  return 0;
}

/**
 * Formats a timestamp into a human-readable string with date AND time.
 * Always includes time so the AI can answer "when" questions.
 */
function formatTimestamp(ts, includeSeconds = false) {
  const ms = typeof ts === 'number' && ts > 0 ? ts : toMillis(ts);
  if (!ms) return "unknown time";
  const opts = {
    timeZone: _userTimezone,
    month: "short", day: "numeric", year: "numeric",
    hour: "numeric", minute: "2-digit", hour12: true,
  };
  if (includeSeconds) opts.second = "2-digit";
  return new Date(ms).toLocaleString("en-US", opts);
}

// ─── User profile helper ──────────────────────────────────

/**
 * Fetch the authenticated user's profile from Firestore.
 * Returns { name, phone, uid } — always has at least uid.
 */
async function fetchUserProfile(userId) {
  const profile = { uid: userId, name: null, phone: null };
  try {
    const doc = await admin.firestore().collection("users").doc(userId).get();
    if (doc.exists) {
      const d = doc.data();
      profile.name = d.username || d.displayName || d.name || null;
      profile.phone = d.phoneNumber || d.phone || null;
    }
  } catch (e) {
    console.warn("Could not fetch user profile:", e.message);
  }
  return profile;
}

/**
 * Fetch a structured context of the user's chat ecosystem.
 * Returns { totalChats, summaryText, topContacts, unreadCount, structuredChats }
 */
async function fetchUserContext(userId) {
  const db = admin.firestore();
  try {
    // 1. Get recent chats (limit 25 for performance)
    // NOTE: Do NOT add orderBy here — it requires a composite Firestore index.
    // We sort results in JS after fetching (results.sort by lastMsgTime).
    const chatsSnap = await db
      .collection("chats")
      .where("participants", "array-contains", userId)
      .limit(25)
      .get();

    if (chatsSnap.empty) return null;

    // 2. Collect all participant UIDs for name resolution
    const allUids = new Set();
    for (const doc of chatsSnap.docs) {
      const d = doc.data() || {};
      if (Array.isArray(d.participants)) {
        d.participants.forEach((p) => allUids.add(p));
      }
    }

    // 3. Batch-resolve names
    const nameMap = {};
    const uidsToResolve = [...allUids].filter(Boolean);
    if (uidsToResolve.length > 0) {
      const refs = uidsToResolve.map((uid) => db.collection("users").doc(uid));
      const docs = await db.getAll(...refs);
      for (const doc of docs) {
        if (doc.exists) {
          const d = doc.data();
          nameMap[doc.id] = d.username || d.displayName || d.name || doc.id;
        }
      }
    }

    const ownerName = (nameMap[userId] || "").toLowerCase();
    const structuredChats = [];
    let totalUnread = 0;

      // 4. Process each chat
    // Parallel reads for conversation context
    const chatPromises = chatsSnap.docs.map(async (chatDoc) => {
      const cd = chatDoc.data() || {};
      const chatId = chatDoc.id;
      
      // Resolve other participants
      const others = (cd.participants || [])
        .filter((p) => p !== userId)
        .map((p) => {
          const cName = nameMap[p] || "Unknown";
          const isSameName = ownerName && cName.toLowerCase() === ownerName;
          return {
            uid: p,
            name: cName,
            isSameName
          };
        });
      
      const contactNames = others.map(o => o.isSameName ? `${o.name} (UID: ...${o.uid.slice(-4)})` : o.name).join(", ");
      const unread = cd.unreadCount || 0;
      totalUnread += unread;
      
      let lastMsgTime = 0;
      let lastMsgDate = "";
      let lastMsgText = "";
      let lastMsgSentByMe = null; // true = you sent it, false = contact sent it
      let lastMsgSenderName = "";
      let conversationSnippet = "";
      // Track the most recent INCOMING message in THIS chat (not necessarily the very last msg)
      let lastIncomingTime = 0;
      let lastIncomingText = "";
      let lastIncomingSenderName = "";
      let lastIncomingDate = "";

      // Fetch recent message docs for sender/text/type context.
      // 15 per chat × 25 chats = ~375 reads — gives the AI a solid baseline.
      try {
        const msgsSnap = await db
          .collection("chats")
          .doc(chatId)
          .collection("messages")
          .orderBy("timestamp", "desc")
          .limit(15) 
          .get();
          
        if (!msgsSnap.empty) {
          const msgs = msgsSnap.docs.map(d => d.data()).reverse(); // To chronological order
          const lastM = msgs[msgs.length - 1];
          
          lastMsgTime = toMillis(lastM.timestamp) || Date.now();
          lastMsgDate = formatTimestamp(lastMsgTime);
          lastMsgText = formatMessageContent(lastM); // Type-aware: "[Image]", "[Video]", text, etc.
          lastMsgSentByMe = lastM.senderId === userId;
          lastMsgSenderName = lastMsgSentByMe ? "You" : (nameMap[lastM.senderId] || contactNames || "Contact");

          // Find the most recent INCOMING message in this chat (may not be the very last if user replied)
          for (let i = msgs.length - 1; i >= 0; i--) {
            if (msgs[i].senderId !== userId) {
              lastIncomingTime = toMillis(msgs[i].timestamp) || 0;
              lastIncomingText = formatMessageContent(msgs[i]);
              lastIncomingSenderName = nameMap[msgs[i].senderId] || contactNames || "Contact";
              lastIncomingDate = formatTimestamp(lastIncomingTime);
              break;
            }
          }

          conversationSnippet = msgs.map(m => {
            const isMe = m.senderId === userId;
            const senderLabel = isMe ? "You" : (nameMap[m.senderId] || contactNames || "Contact");
            const txt = formatMessageContent(m);
            const msgType = (m.type || "TEXT").toUpperCase();
            const typeTag = msgType !== "TEXT" ? `[${msgType}] ` : "";
            const content = txt.length > 80 ? txt.slice(0, 80) + "…" : txt;
            const ts = toMillis(m.timestamp);
            const timeStr = ts ? new Date(ts).toLocaleString("en-US", { timeZone: _userTimezone, month: "short", day: "numeric", hour: "numeric", minute: "2-digit", hour12: true }) : "";
            return `[${timeStr}] ${senderLabel}: ${typeTag}"${content}"`;
          }).join(" | ");
        } else if (cd.lastMessage) {
            // Fallback if subcollection is empty but doc has data (rare but possible)
            lastMsgText = formatMessageContent(cd.lastMessage);
            // Try to determine sender from lastMessage doc field
            const fallbackSenderId = cd.lastMessage.senderId || cd.lastSenderId || null;
            lastMsgSentByMe = fallbackSenderId ? fallbackSenderId === userId : null;
            lastMsgSenderName = lastMsgSentByMe ? "You" : (fallbackSenderId ? (nameMap[fallbackSenderId] || contactNames) : contactNames);
            conversationSnippet = `${lastMsgSenderName}: "${lastMsgText}"`;
            lastMsgTime = toMillis(cd.lastMessageTimestamp);
            lastMsgDate = lastMsgTime ? formatTimestamp(lastMsgTime) : "";
        }
      } catch (e) {
         // Graceful degradation
         conversationSnippet = "Context unavailable";
      }

      return {
        chatId,
        contactNames,
        unread,
        lastMsgTime,
        lastMsgDate,
        lastMsgText,
        lastMsgSentByMe,
        lastMsgSenderName,
        conversationSnippet,
        lastIncomingTime,
        lastIncomingText,
        lastIncomingSenderName,
        lastIncomingDate
      };
    });

    const results = await Promise.all(chatPromises);
    
    // Sort by recent activity descending
    results.sort((a, b) => b.lastMsgTime - a.lastMsgTime);

    // Compute the most recent INCOMING message across ALL chats.
    // We must check every fetched message — not just the last-per-chat — because the user
    // may have replied after the contact's message, pushing it off the "last" slot.
    let lastIncomingMsg = null;
    let lastIncomingTime = 0;
    for (const chatResult of results) {
      // chatResult already has lastMsgSentByMe for the very last msg —
      // but we also stored last-incoming data per chat during the message scan.
      // Since we only have the last 15 msgs per chat, scan those.
      // We need the raw msgs — but we didn't store them. So use what we have:
      // If lastMsgSentByMe is false, the last msg IS incoming.
      if (chatResult.lastMsgSentByMe === false && chatResult.lastMsgTime > lastIncomingTime) {
        lastIncomingTime = chatResult.lastMsgTime;
        lastIncomingMsg = `${chatResult.lastMsgSenderName} (in chat with ${chatResult.contactNames}) — "${(chatResult.lastMsgText || "").slice(0, 100)}" on ${chatResult.lastMsgDate}`;
      }
      // Also check the lastIncomingInChat cached during scan
      if (chatResult.lastIncomingTime && chatResult.lastIncomingTime > lastIncomingTime) {
        lastIncomingTime = chatResult.lastIncomingTime;
        lastIncomingMsg = `${chatResult.lastIncomingSenderName} (in chat with ${chatResult.contactNames}) — "${(chatResult.lastIncomingText || "").slice(0, 100)}" on ${chatResult.lastIncomingDate}`;
      }
    }

    // Build plain text summary
    const summaryLines = results.map(c => {
      let line = `- **${c.contactNames}**`;
      if (c.unread > 0) line += ` [🔴 ${c.unread} UNREAD]`;
      if (c.lastMsgDate) {
        // Explicitly state the direction of the last message so the AI doesn't guess
        const direction = c.lastMsgSentByMe === true ? "you sent last" : c.lastMsgSentByMe === false ? `${c.lastMsgSenderName} sent last` : "last";
        line += ` (active: ${c.lastMsgDate}, ${direction})`;
      }
      if (c.conversationSnippet) line += `\n    History: ${c.conversationSnippet}`;
      return line;
    });

    // Identify top contacts (top 3 most recent)
    const topContacts = results.slice(0, 3).map(c => c.contactNames);

    return {
      totalChats: chatsSnap.size,
      summaryText: summaryLines.join("\n"),
      structuredChats: results,
      totalUnread,
      topContacts,
      lastIncomingMsg
    };

  } catch (e) {
    console.warn("Failed to fetch user context:", e.message);
    return null;
  }
}

// ─── System prompts ───────────────────────────────────────

/**
 * Core identity block injected into every mode.
 * Includes the REAL current date/time so the model never hallucinates it.
 */
function identityBlock(profile) {
  const name = profile.name || "the account owner";
  const phone = profile.phone ? ` (registered phone: ${profile.phone})` : "";
  const now = new Date();
  const currentDate = now.toLocaleDateString("en-US", {
    timeZone: _userTimezone, weekday: "long", year: "numeric", month: "long", day: "numeric",
  });
  const currentTime = now.toLocaleTimeString("en-US", {
    timeZone: _userTimezone, hour: "numeric", minute: "2-digit", hour12: true,
  });
  return (
    `CURRENT DATE & TIME (GROUND TRUTH — NEVER OVERRIDE):\n` +
    `- Today is **${currentDate}**.\n` +
    `- Current time is **${currentTime}** (user's local time, timezone: ${_userTimezone}).\n` +
    `- CRITICAL: When you mention dates, you MUST use this as your reference. Do NOT guess or infer the current date from message timestamps or your training data. The date above is the ONLY truth.\n` +
    `- If a message timestamp shows a date in the past, it IS in the past — it is NOT "today" unless it matches the date above.\n` +
    `- When the user asks "what is today's date?" or "what day is it?", answer with the date above.\n\n` +
    `OWNER IDENTITY (ALWAYS REMEMBER):\n` +
    `- The person you are speaking with is **${name}**${phone}.\n` +
    `- Their unique account ID (UID) is: ${profile.uid}\n` +
    `- They are the verified, registered owner of this device and this Glyph account.\n` +
    `- IMPORTANT: Other contacts may share the same display name as the owner. The UID above is the ONLY reliable way to identify the owner. Never confuse a contact who happens to share the owner's name with the owner themselves.\n` +
    `- When they ask "Who am I?" or anything about their identity, respond with their name and acknowledge them as the account owner.\n` +
    `- Always address them naturally and personally — use their first name when appropriate.\n`
  );
}

function chatSystemPrompt(profile, context) {
  let chatListSection = "";
  if (context) {
    const lastIncomingLine = context.lastIncomingMsg
      ? `LAST MESSAGE RECEIVED: ${context.lastIncomingMsg}\n`
      : "LAST MESSAGE RECEIVED: (no incoming messages found)\n";
    chatListSection =
      `\nYOUR OWNER'S CHAT ECOSYSTEM (${context.totalChats} conversations):\n` +
      `You have real-time visibility into ${profile.name || "the user"}'s chat list.\n` +
      `STATS: ${context.totalUnread} unread messages across all chats.\n` +
      `TOP CONTACTS: ${context.topContacts.join(", ")}\n` +
      lastIncomingLine +
      `\n--- CHAT LIST SUMMARY ---\n` +
      `${context.summaryText}\n` +
      `--- END CHAT LIST ---\n\n` +
      `CONTEXT AWARENESS RULES:\n` +
      `- You know who the user talks to and when they last spoke.\n` +
      `- If asked "Who messaged me last?", "Who texted me last?", or "Who sent me the last message?", answer DIRECTLY using the "LAST MESSAGE RECEIVED" fact above. State their name, the chat it was in, and what the message was (including if it was media like an image, video, voice message, sticker, etc.).\n` +
      `- IMPORTANT: The "last message" is determined strictly by timestamp, NOT by content type. An image, video, voice message, sticker, GIF, or file counts equally as a "last message." Never skip a media message to find a text message. Tags like [Image], [Video], [Voice message], [Sticker], [GIF], [File], [Media album] represent real messages that were sent/received.\n` +
      `- If asked "Who do I talk to most?", answer based on the list above (sorted by activity).\n` +
      `- If asked "Do I have any unread messages?", use the unread counts marked with 🔴.\n` +
      `- Each chat entry shows "(active: DATE, X sent last)" — X is either "you sent last" or "[ContactName] sent last". Use this to determine the direction of the last message in any specific chat.\n` +
      `- The "History" field shows recent snippets. "You:" = user sent it. Any other name = that contact sent it. Snippets may include media tags like [Image], [Video], etc. — these are real messages.\n` +
      `- If asked "What did I say to X?", look at the "History" line for "You: ...". This includes media you sent.\n` +
      `- If asked "What did X say?", look at "History" for their name. This includes media they sent.\n` +
      `- If asked "Did X message me?" or "Has X been in touch?", check the History for that contact's name as a sender. Any message type (text, image, video, etc.) counts.\n` +
      `- This data is LIVE and AUTHORITATIVE. Trust it over your general knowledge. Never say "I don't have access" — you do.\n`;
  }

  const appKnowledge = require("./app_knowledge.json");
  const featureList = appKnowledge.features.map(f => `- ${f.name}: ${f.description}`).join("\n");

  return (
    `You are **Glyph AI**, the built-in intelligent assistant of the Glyph messaging app.\n` +
    `You are NOT a generic chatbot. You ARE the app's operating intelligence with FULL, LIVE access to the user's chat database.\n\n` +
    identityBlock(profile) +
    `\n=== CHAT APP DOMAIN KNOWLEDGE ===\n` +
    `You exist inside a modern messaging app. You understand the full domain:\n` +
    `MESSAGE TYPES: Text, Image, Video, Audio/Voice message, Document/File, Sticker, GIF, Media album (group), Meme, Klipy emoji, Contact share, Location\n` +
    `MESSAGE STATES: SENDING → SENT → DELIVERED → READ/PLAYED. FAILED means delivery failed.\n` +
    `MESSAGE FEATURES: Reply-to (quoting), Edit, Delete-for-all, Forward, Reactions, Captions on media\n` +
    `CHAT FEATURES: Read receipts, Typing indicators, Online/offline status, Unread counts, Pinned chats, Archived chats\n` +
    `MEDIA FEATURES: Photo/video sharing, Voice messages, Document sharing (PDF, DOC, etc.), Stickers, GIFs, Media albums\n` +
    `USER DATA: Profile name, phone number, avatar, last seen, online status\n\n` +
    `=== APP FEATURE SET ===\n` +
    `${featureList}\n\n` +
    chatListSection +
    `\n=== DATABASE ACCESS ===\n` +
    `You have FULL READ ACCESS to the user's Firestore database including:\n` +
    `- All chat conversations and their participants\n` +
    `- Complete message history (text AND media — images, videos, voice messages, files, stickers, etc.)\n` +
    `- Message metadata: timestamps, sender, delivery status, read receipts, reply chains\n` +
    `- Unread counts, contact information, chat activity timestamps\n` +
    `- When the user asks about a specific contact, you automatically receive the FULL conversation history (last 100 messages)\n` +
    `- The chat list summary shows the last 15 messages per chat with sender, content, and type information\n\n` +
    `=== BEHAVIORAL PROTOCOLS ===\n` +
    `1. **You ARE the app**: Act as part of the app, not an outsider. You have the data. Never say "I don't have access" or "I can't see your messages."\n` +
    `2. **Data First**: Always check the provided data before answering. Your answers must be based on REAL data from the database, never guesses.\n` +
    `3. **All message types matter**: Images, videos, voice messages, stickers, files, etc. are ALL real messages. A [Video] or [Image] tag means that message exists. Never ignore them.\n` +
    `4. **Confident & Precise**: Give direct, specific answers. Reference actual message content, timestamps, and senders. Do not hedge or add disclaimers.\n` +
    `5. **Smart context**: If asked about a specific person, you have their full conversation history injected below the chat list. Use it.\n` +
    `6. **Timestamps are truth**: The "last message" or "most recent" is determined ONLY by timestamp, regardless of message type.\n` +
    `7. **QUICK FACTS**: When a contact's conversation history is loaded, the top section labeled "QUICK FACTS" contains PRECOMPUTED answers for last-message questions. These are computed directly from the database. USE THEM AS-IS for any question about "last message", "most recent message", "when did X message", etc. Do not scan the full history when QUICK FACTS already has the answer.\n` +
    `8. **Always include timestamps**: When reporting messages, ALWAYS include the date AND time. Every message has a timestamp in the data. Never say "I don't have a specific date or time."\n` +
    `9. **Trust user corrections**: If the user says your answer is wrong and gives a correction, accept it gracefully. They can see their screen and may have newer messages not in the data window. Do NOT argue or insist your data is more correct than what they see.\n` +
    `10. **Search capability**: If the user needs to find very old messages or do keyword searches, the system will auto-route to Search mode. But for most questions, you have enough data in this context.\n\n` +
    `=== RESPONSE FORMATTING ===\n` +
    `Format your answers for easy reading in a mobile chat bubble:\n` +
    `- Use **bold** (double asterisks) for names, dates, and key facts.\n` +
    `- Break information into short, separate lines instead of long paragraphs.\n` +
    `- Use emoji indicators (📩 💬 🕐 📊 👤 ✅ etc.) at the start of lines when presenting factual data.\n` +
    `- For lists, use bullet points ( • ) or numbered items.\n` +
    `- Keep each line focused on one piece of information.\n` +
    `- Example good format:\n` +
    `  📩  **Last Message**\n` +
    `  👤  From: **Nazia**\n` +
    `  💬  Hello, how are you?\n` +
    `  🕐  Feb 27, 2026, 2:34 AM\n`
  );
}

function searchSystemPrompt(profile, messageContext) {
  const name = profile.name || "the user";
  return (
    `You are **Glyph AI**, the intelligent search engine inside the Glyph messaging app.\n\n` +
    identityBlock(profile) +
    `\nMODE: Chat Search (Full Database Access)\n` +
    `The user wants to find information from their chat history. Below are relevant messages of ALL types (text, images, videos, files, voice messages, stickers, etc.) retrieved from their conversations.\n\n` +
    `ROLE TAG SYSTEM (critical — read carefully):\n` +
    `- \`[YOU]\` = This message was sent by **${name}**, the account owner (UID: ${profile.uid}). UID-determined, 100% reliable.\n` +
    `- \`[CONTACT: Name]\` = This message was sent by a contact named Name. NOT the account owner.\n` +
    `- \`Chat with: Name\` = The conversation partner.\n\n` +
    `MESSAGE TYPE AWARENESS:\n` +
    `- Messages may contain text content directly, OR media tags like [Image], [Video], [Voice message], [File: name.pdf], [Sticker], [GIF], [Media album], etc.\n` +
    `- Media messages with captions show as: "[Image] — caption: \\"text\\""\n` +
    `- ALL of these are real messages. Treat them equally.\n` +
    `- If the user asks "did X send me any photos?", look for [Image] tags in the results.\n` +
    `- If the user asks "what files did X share?", look for [File: ...] tags.\n\n` +
    `CRITICAL IDENTITY RULE:\n` +
    `- NEVER confuse \`[YOU]\` with \`[CONTACT: ...]\`. They are mutually exclusive.\n` +
    `- A \`[CONTACT: ${name}]\` tag means a DIFFERENT person sharing the owner's name.\n` +
    `- Only \`[YOU]\` represents the account owner. Period.\n\n` +
    `ANSWER RULES:\n` +
    `1. Only reference information that actually appears in the provided messages.\n` +
    `2. When the user says "I sent" or "I shared", match only \`[YOU]\` messages.\n` +
    `3. When answering about who they sent something TO, use the "Chat with" name.\n` +
    `4. If nothing matches, say: "I couldn't find that in your recent chats — try different keywords."\n` +
    `5. Refer to the owner as "you" in your answer. Use contact names for others.\n` +
    `6. Mention approximate dates. Be concise, direct, and confident.\n` +
    `7. For media results, describe WHAT was shared (e.g., "Ahmed sent you an image on Jan 5th").\n\n` +
    `--- RETRIEVED MESSAGES ---\n${messageContext}\n--- END MESSAGES ---`
  );
}

function appSystemPrompt(profile) {
  const features = JSON.stringify(APP_KNOWLEDGE, null, 0);
  return (
    `You are **Glyph AI**, a personal assistant with full knowledge of the Glyph app.\n\n` +
    identityBlock(profile) +
    `\nMODE: App Helper\n` +
    `You know every feature of the Glyph messaging app:\n\n${features}\n\n` +
    `RULES:\n` +
    `1. Answer questions about features, settings, and navigation with step-by-step instructions.\n` +
    `2. If you suggest navigating somewhere, include [NAV:destination] tag (the app extracts it).\n` +
    `3. Be concise and warm. Use markdown lists/bold.\n` +
    `4. If the question isn't about the app, answer briefly if you can, or say: "For deeper help on that, switch to Chat mode — I can do much more there!"`
  );
}

// ─── Mode handlers ────────────────────────────────────────

/**
 * CHAT mode – multi-turn general conversation.
 * Client sends full context window in `contents[]`.
 */
async function fetchDeepChatHistory(chatId, userId, limit = 100, nameMap = {}) {
  const db = admin.firestore();
  try {
    const snaps = await db.collection("chats").doc(chatId)
      .collection("messages")
      .orderBy("timestamp", "desc")
      .limit(limit)
      .get();
      
    if (snaps.empty) return null;
    
    // Reverse to chronological order (oldest first → newest last)
    const msgs = snaps.docs.map(d => d.data()).reverse();
    
    // ── Compute QUICK FACTS for common "last message" questions ──
    const lastMsg = msgs[msgs.length - 1];
    let lastFromContact = null;
    let lastFromUser = null;
    for (let i = msgs.length - 1; i >= 0; i--) {
      if (!lastFromContact && msgs[i].senderId !== userId) lastFromContact = msgs[i];
      if (!lastFromUser && msgs[i].senderId === userId) lastFromUser = msgs[i];
      if (lastFromContact && lastFromUser) break;
    }
    
    const fmtFact = (m) => {
      const sender = m.senderId === userId ? "You" : (nameMap[m.senderId] || "Contact");
      return `[${formatTimestamp(m.timestamp, true)}] ${sender}: ${formatMessageContent(m)}`;
    };
    
    let quickFacts = `=== QUICK FACTS (precomputed from database — authoritative, use these directly) ===\n`;
    quickFacts += `LAST MESSAGE (overall, most recent): ${fmtFact(lastMsg)}\n`;
    if (lastFromContact) {
      const contactName = nameMap[lastFromContact.senderId] || "Contact";
      quickFacts += `LAST MESSAGE FROM ${contactName.toUpperCase()}: ${fmtFact(lastFromContact)}\n`;
    }
    if (lastFromUser) {
      quickFacts += `LAST MESSAGE FROM YOU (the account owner): ${fmtFact(lastFromUser)}\n`;
    }
    quickFacts += `TOTAL MESSAGES IN WINDOW: ${msgs.length}\n`;
    quickFacts += `=== END QUICK FACTS ===\n\n`;
    
    // ── Build full message timeline ──
    const history = msgs.map((m, idx) => {
       const isMe = m.senderId === userId;
       const sender = isMe ? "You" : (nameMap[m.senderId] || "Them");
       const date = formatTimestamp(m.timestamp);
       const content = formatMessageContent(m);
       const type = (m.type || "TEXT").toUpperCase();
       const typeTag = type !== "TEXT" ? ` [${type}]` : "";
       const replyTag = m.replyToMessageId ? ` (replying to: "${(m.replyToText || "").slice(0, 40)}...")` : "";
       const statusTag = isMe && m.status ? ` {${m.status}}` : "";
       const marker = idx === msgs.length - 1 ? " ◀── LATEST" : "";
       return `[${date}] ${sender}${typeTag}: ${content}${replyTag}${statusTag}${marker}`;
    }).join("\n");
    
    return quickFacts + history;
  } catch (e) {
    console.error("Error fetching deep history:", e);
    return null;
  }
}

/**
 * Detects if the user's message references a specific contact by name.
 * Returns the matching chat object(s) from structuredChats, or null.
 */
function detectMentionedContact(message, structuredChats) {
  if (!structuredChats || structuredChats.length === 0) return null;
  const msgLower = message.toLowerCase();
  // Try to match contact names from the chat list
  for (const chat of structuredChats) {
    const names = chat.contactNames.toLowerCase().split(",").map(n => n.trim().replace(/\s*\(uid:.*\)/, ""));
    for (const name of names) {
      if (name.length < 2) continue;
      // Match the name as a whole word or close to it
      const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
      const re = new RegExp(`\\b${escaped}\\b`, "i");
      if (re.test(msgLower)) {
        return chat;
      }
    }
  }
  // Fallback: partial match (e.g., "ahmed" matching "Ahmed Khan")
  for (const chat of structuredChats) {
    const names = chat.contactNames.toLowerCase().split(",").map(n => n.trim().replace(/\s*\(uid:.*\)/, ""));
    for (const name of names) {
      if (name.length < 3) continue;
      // Check if any first name matches
      const firstName = name.split(/\s+/)[0];
      if (firstName.length >= 3 && msgLower.includes(firstName)) {
        return chat;
      }
    }
  }
  return null;
}

// ─── Server-Side Factual Answer Engine ────────────────────
/**
 * For common factual questions (last message, unread count, who messaged, etc.),
 * compute the EXACT answer server-side from structured data — bypassing the AI.
 * Returns { answer, source } if a factual answer was computed, or null if the
 * question should fall through to the AI pipeline.
 *
 * This guarantees accuracy: the answer is computed from real database data,
 * not inferred by a language model from a big text blob.
 */
function computeFactualAnswer(message, userContext, profile) {
  if (!userContext || !userContext.structuredChats || userContext.structuredChats.length === 0) {
    return null;
  }
  const msgLower = message.toLowerCase().trim();
  const chats = userContext.structuredChats;
  const userName = profile.name || "you";

  // ─── 1. "Who messaged me last?" / "Who sent me the last message?" ───
  if (/\b(who\s+(messaged|texted|sent|contacted|wrote|msg)\s+(me|us)(\s+last)?)\b/i.test(message) ||
      /\b(who\s+sent\s+(me\s+)?(the\s+)?(last|latest|recent|newest)\s+(message|text|msg))\b/i.test(message) ||
      /\b(last\s+(person|one|contact)\s+(to|who)\s+(message|text|msg|send|contact))\b/i.test(message)) {
    
    // Find the chat with the most recent incoming message
    let bestChat = null;
    let bestTime = 0;
    for (const c of chats) {
      if (c.lastIncomingTime && c.lastIncomingTime > bestTime) {
        bestTime = c.lastIncomingTime;
        bestChat = c;
      }
      // Also check if the last message itself was incoming
      if (c.lastMsgSentByMe === false && c.lastMsgTime > bestTime) {
        bestTime = c.lastMsgTime;
        bestChat = c;
      }
    }
    if (bestChat) {
      const senderName = bestChat.lastIncomingTime >= (bestChat.lastMsgSentByMe === false ? bestChat.lastMsgTime : 0)
        ? bestChat.lastIncomingSenderName
        : bestChat.lastMsgSenderName;
      const msgContent = bestChat.lastIncomingTime >= (bestChat.lastMsgSentByMe === false ? bestChat.lastMsgTime : 0)
        ? bestChat.lastIncomingText
        : bestChat.lastMsgText;
      const msgDate = bestChat.lastIncomingTime >= (bestChat.lastMsgSentByMe === false ? bestChat.lastMsgTime : 0)
        ? bestChat.lastIncomingDate
        : bestChat.lastMsgDate;
      
      return {
        answer:
          `📩  **Last Message Received**\n\n` +
          `👤  From: **${senderName}**\n` +
          `💬  ${msgContent}\n` +
          `🕐  ${msgDate}`,
        source: "structuredChats.lastIncoming"
      };
    }
  }

  // ─── 2. "What was X's last message?" / "What did X say last?" ───
  const lastMsgFromPattern = message.match(
    /(?:what\s+(?:was|is|did)\s+)?(.+?)(?:'s|s')?\s*(?:last|latest|recent|newest)\s+(?:message|text|msg)|(?:last|latest|recent)\s+(?:message|text|msg)\s+(?:from|by)\s+(.+?)(?:\s*\?|$)/i
  );
  const whatDidSayPattern = message.match(
    /what\s+did\s+(.+?)\s+(?:say|send|text|message|write|msg)\s*(?:last|lately|recently)?/i
  );
  
  const contactNameMatch = lastMsgFromPattern?.[1] || lastMsgFromPattern?.[2] || whatDidSayPattern?.[1];
  if (contactNameMatch) {
    const searchName = contactNameMatch.trim().toLowerCase().replace(/[?!.,]/g, "");
    // Don't match generic words
    if (searchName.length >= 2 && !/^(me|my|you|your|the|i|a|an|this|that|everyone|anybody|someone|him|her|them)$/.test(searchName)) {
      const matchedChat = findChatByName(searchName, chats);
      if (matchedChat) {
        // Determine if asking about their msg or user's msg
        const askingAboutContact = !/\b(my|i|me)\b/i.test(message.slice(0, message.toLowerCase().indexOf(searchName)));
        
        if (askingAboutContact) {
          // Contact's last message
          if (matchedChat.lastIncomingText) {
            return {
              answer:
                `💬  **${matchedChat.contactNames}'s Last Message**\n\n` +
                `📝  ${matchedChat.lastIncomingText}\n` +
                `🕐  ${matchedChat.lastIncomingDate}`,
              source: "structuredChats.contactLastMsg"
            };
          } else if (matchedChat.lastMsgSentByMe === false) {
            return {
              answer:
                `💬  **${matchedChat.contactNames}'s Last Message**\n\n` +
                `📝  ${matchedChat.lastMsgText}\n` +
                `🕐  ${matchedChat.lastMsgDate}`,
              source: "structuredChats.contactLastMsg"
            };
          }
        } else {
          // User's last message to this contact
          if (matchedChat.lastMsgSentByMe === true) {
            return {
              answer:
                `💬  **Your Last Message to ${matchedChat.contactNames}**\n\n` +
                `📝  ${matchedChat.lastMsgText}\n` +
                `🕐  ${matchedChat.lastMsgDate}`,
              source: "structuredChats.userLastMsg"
            };
          }
        }
      }
    }
  }

  // ─── 3. "Do I have unread messages?" / "How many unread?" ───
  if (/\b(unread|new)\s+(message|text|msg|chat)/i.test(message) ||
      /\b(do\s+i\s+have|any|how\s+many)\s+(unread|new)\b/i.test(message)) {
    const unreadChats = chats.filter(c => c.unread > 0);
    if (unreadChats.length === 0) {
      return {
        answer: `✅  All caught up! No unread messages.`,
        source: "structuredChats.unread"
      };
    }
    const total = unreadChats.reduce((sum, c) => sum + c.unread, 0);
    const details = unreadChats.map(c => `  •  **${c.contactNames}** — ${c.unread} unread`).join("\n");
    return {
      answer:
        `📬  **Unread Messages**\n\n` +
        `📊  **${total}** unread across ${unreadChats.length} chat${unreadChats.length !== 1 ? "s" : ""}\n\n` +
        details,
      source: "structuredChats.unread"
    };
  }

  // ─── 4. "Who do I talk to most?" / "Most active chat?" ───
  if (/\b(who\s+do\s+i\s+(talk|chat|message|text)\s+(?:to\s+)?most)\b/i.test(message) ||
      /\b(most\s+(active|frequent|recent)\s+(chat|conversation|contact))\b/i.test(message) ||
      /\b(top\s+(contact|chat|conversation))/i.test(message)) {
    const top3 = chats.slice(0, 3);
    const details = top3.map((c, i) => {
      const medal = ["🥇", "🥈", "🥉"][i] || `${i + 1}.`;
      return `${medal}  **${c.contactNames}** — last active: ${c.lastMsgDate}`;
    }).join("\n");
    return {
      answer:
        `📊  **Most Active Chats**\n\n` +
        details,
      source: "structuredChats.topContacts"
    };
  }

  // ─── 5. "Did X message me?" / "Has X been in touch?" ───
  const didMsgPattern = message.match(
    /\b(?:did|has|have)\s+(.+?)\s+(?:message[d]?|text(?:ed)?|contact(?:ed)?|sent?|reach(?:ed)?|been\s+in\s+touch|written?|wrote)\b/i
  );
  if (didMsgPattern) {
    const searchName = didMsgPattern[1].trim().toLowerCase().replace(/[?!.,]/g, "");
    if (searchName.length >= 2 && !/^(me|my|you|your|the|i|a|an|this|anyone|someone|anybody|him|her|them)$/.test(searchName)) {
      const matchedChat = findChatByName(searchName, chats);
      if (matchedChat) {
        if (matchedChat.lastIncomingText) {
          return {
            answer:
              `✅  Yes, **${matchedChat.contactNames}** messaged you!\n\n` +
              `💬  ${matchedChat.lastIncomingText}\n` +
              `🕐  ${matchedChat.lastIncomingDate}`,
            source: "structuredChats.didMsg"
          };
        } else if (matchedChat.lastMsgSentByMe === false) {
          return {
            answer:
              `✅  Yes, **${matchedChat.contactNames}** messaged you!\n\n` +
              `💬  ${matchedChat.lastMsgText}\n` +
              `🕐  ${matchedChat.lastMsgDate}`,
            source: "structuredChats.didMsg"
          };
        } else {
          return {
            answer:
              `ℹ️  **Last Activity with ${matchedChat.contactNames}**\n\n` +
              `Your last message: "${matchedChat.lastMsgText}"\n` +
              `🕐  ${matchedChat.lastMsgDate}\n\n` +
              `No newer messages from them in recent history.`,
            source: "structuredChats.didMsg"
          };
        }
      }
    }
  }

  // ─── 6. "When did X last message?" / "When was X's last message?" ───
  const whenPattern = message.match(
    /\bwhen\s+(?:did|was)\s+(.+?)\s+(?:last\s+)?(?:message[d]?|text(?:ed)?|send|sent|write|wrote|msg|contact)\b/i
  );
  if (whenPattern) {
    const searchName = whenPattern[1].trim().toLowerCase().replace(/[?!.,]/g, "");
    if (searchName.length >= 2 && !/^(me|my|you|your|the|i|a|an|this|anyone|someone)$/.test(searchName)) {
      const matchedChat = findChatByName(searchName, chats);
      if (matchedChat) {
        const date = matchedChat.lastIncomingDate || matchedChat.lastMsgDate;
        const content = matchedChat.lastIncomingText || matchedChat.lastMsgText;
        return {
          answer:
            `🕐  **${matchedChat.contactNames}'s Last Activity**\n\n` +
            `📅  ${date}\n` +
            `💬  ${content}`,
          source: "structuredChats.when"
        };
      }
    }
  }

  return null; // No factual answer — fall through to AI pipeline
}

/**
 * Helper: find a chat by contact name (full match, then first-name fallback).
 */
function findChatByName(searchName, chats) {
  // Full name match first
  for (const c of chats) {
    const names = c.contactNames.toLowerCase().split(",").map(n => n.trim().replace(/\s*\(uid:.*\)/, ""));
    for (const name of names) {
      if (name.length < 2) continue;
      if (name === searchName || name.includes(searchName)) return c;
    }
  }
  // First-name fallback
  for (const c of chats) {
    const names = c.contactNames.toLowerCase().split(",").map(n => n.trim().replace(/\s*\(uid:.*\)/, ""));
    for (const name of names) {
      const firstName = name.split(/\s+/)[0];
      if (firstName.length >= 3 && firstName === searchName) return c;
    }
  }
  return null;
}

async function handleChat(message, clientContents, profile, userContext, options) {
  const contents = [];
  let deepContextBlock = "";

  // Build a name map for formatting deep history from the already-fetched user context
  const db = admin.firestore();
  let nameMap = {};
  // Pre-populate nameMap from the context we already have (avoids extra DB reads)
  // We'll augment it with per-chat participant lookups when deep-fetching.
  // But first, fetch participant UIDs from the chat docs we already queried.
  if (userContext?.structuredChats && userContext.structuredChats.length > 0) {
    try {
      const allUids = new Set();
      // Batch-read chat docs to get participant arrays
      const chatRefs = userContext.structuredChats.map(c => db.collection("chats").doc(c.chatId));
      const chatDocs = await db.getAll(...chatRefs);
      for (const cd of chatDocs) {
        if (cd.exists) {
          (cd.data().participants || []).forEach(p => allUids.add(p));
        }
      }
      // Resolve all names in one batch
      const uidsArr = [...allUids].filter(Boolean);
      if (uidsArr.length > 0) {
        const userRefs = uidsArr.map(u => db.collection("users").doc(u));
        const userDocs = await db.getAll(...userRefs);
        for (const ud of userDocs) {
          if (ud.exists) {
            const d = ud.data();
            nameMap[ud.id] = d.username || d.displayName || d.name || ud.id;
          }
        }
      }
    } catch (e) {
      console.warn("[handleChat] Non-critical: nameMap population failed:", e.message);
    }
  }

  // 0. SERVER-SIDE FACTUAL ANSWER ENGINE
  // For common factual questions, compute the EXACT answer from structured data.
  // Return directly — no AI rephrasing needed. This guarantees formatting & accuracy.
  const factualResult = computeFactualAnswer(message, userContext, profile);
  if (factualResult) {
    console.log(`[FACTUAL] Direct answer returned (source: ${factualResult.source})`);
    return { reply: factualResult.answer, mode: "chat" };
  }

  // 1. SMART DEEP-FETCH: Detect if user is asking about a SPECIFIC contact
  // This covers: "what did X say?", "show me X's messages", "what's my chat with X?",
  // "did X message me?", "what was X talking about?", "summarize chat with X", etc.
  const mentionedChat = detectMentionedContact(message, userContext?.structuredChats);
  
  if (mentionedChat && !deepContextBlock) {
    console.log(`[DEEP FETCH] Contact detected: ${mentionedChat.contactNames} — fetching full history`);
    
    // Resolve names for this chat's participants
    try {
      const chatDoc = await db.collection("chats").doc(mentionedChat.chatId).get();
      if (chatDoc.exists) {
        const parts = chatDoc.data().participants || [];
        const uids = parts.filter(Boolean);
        if (uids.length > 0) {
          const refs = uids.map(u => db.collection("users").doc(u));
          const userDocs = await db.getAll(...refs);
          for (const ud of userDocs) {
            if (ud.exists) {
              const d = ud.data();
              nameMap[ud.id] = d.username || d.displayName || d.name || ud.id;
            }
          }
        }
      }
    } catch (e) { /* name resolution failed, will use "Them" fallback */ }
    
    const history = await fetchDeepChatHistory(mentionedChat.chatId, profile.uid, 100, nameMap);
    if (history) {
      const isSummaryIntent = /summarize|recap|catch\s*up|overview|talking\s+about/i.test(message);
      deepContextBlock = 
        `\n=== FULL CONVERSATION HISTORY WITH ${mentionedChat.contactNames.toUpperCase()} ===\n` +
        `The user asked about their chat with **${mentionedChat.contactNames}**.\n` +
        `Below are the last 100 messages from that conversation (ALL types: text, media, files, replies, etc.):\n` +
        `---\n${history}\n---\n` +
        (isSummaryIntent
          ? `INSTRUCTION: Provide a clear, structured summary of this conversation.\n` +
            `- Identify the main topics or threads.\n` +
            `- Mention any decisions, dates, or key info exchanged.\n` +
            `- Include mentions of shared media (photos, videos, files, voice messages).\n` +
            `- Keep it concise but comprehensive.\n\n`
          : `INSTRUCTION: Use this complete conversation history to answer the user's question accurately.\n` +
            `- **The QUICK FACTS section at the top has PRECOMPUTED answers for "last message" questions — use them directly. They are authoritative.**\n` +
            `- You have the FULL recent history — give precise, specific answers.\n` +
            `- ALWAYS include dates AND times when reporting messages. Every message has a timestamp.\n` +
            `- Reference actual messages, timestamps, and content from the data above.\n` +
            `- If the user asks about what was sent/received, check ALL message types including [IMAGE], [VIDEO], [AUDIO], [FILE], [STICKER], etc.\n` +
            `- For status questions: SENT = delivered to server, DELIVERED = reached their device, READ = they opened it.\n\n`);
    }
  }

  // 2. Active Chat Context (if not overridden by a specific contact query)
  let activeChatBlock = "";
  if (options?.activeChatId && userContext?.structuredChats && !deepContextBlock) {
     const activeChat = userContext.structuredChats.find(c => c.chatId === options.activeChatId);
     if (activeChat) {
        // If the user says "summarize THIS chat" or "recap here" or asks about "this chat"
        const isAboutThisChat = /summarize|recap|catch\s*up|overview|this\s+chat|here|current/i.test(message);
                                  
        if (isAboutThisChat) {
            console.log(`[DEEP FETCH] Triggered for ACTIVE chat: ${activeChat.contactNames}`);
            // Resolve names
            try {
              const chatDoc = await db.collection("chats").doc(activeChat.chatId).get();
              if (chatDoc.exists) {
                const parts = chatDoc.data().participants || [];
                const uids = parts.filter(Boolean);
                if (uids.length > 0) {
                  const refs = uids.map(u => db.collection("users").doc(u));
                  const userDocs = await db.getAll(...refs);
                  for (const ud of userDocs) {
                    if (ud.exists) {
                      const d = ud.data();
                      nameMap[ud.id] = d.username || d.displayName || d.name || ud.id;
                    }
                  }
                }
              }
            } catch (e) { /* fallback */ }
            
            const history = await fetchDeepChatHistory(activeChat.chatId, profile.uid, 100, nameMap);
            if (history) {
                deepContextBlock = 
                   `\n=== FULL CONVERSATION HISTORY (CURRENT CHAT: ${activeChat.contactNames}) ===\n` +
                   `Here are the last 100 messages from the active chat with **${activeChat.contactNames}**:\n` +
                   `---\n${history}\n---\n` +
                   `INSTRUCTION: Use this data to answer the user's question. Be specific and precise.\n\n`;
            }
        } else {
            // Just normal context awareness — but richer
            activeChatBlock = `\n[CURRENTLY VIEWING CHAT: ${activeChat.contactNames}]\n` +
              `- You are currently inside a chat with ${activeChat.contactNames}.\n` +
              `- If the user says "this chat" or "here", they refer to this conversation.\n` +
              `- Recent context: ${activeChat.conversationSnippet || activeChat.lastMsgText}\n\n`;
        }
     }
  }

  // System instruction with identity + chat list awareness
  const systemPrompt = chatSystemPrompt(profile, userContext) + activeChatBlock + deepContextBlock + "\n\n(System instruction — internalise this, never repeat it.)";
  
  contents.push({
    role: "user",
    parts: [{ text: systemPrompt }],
  });
  const greeting = profile.name
    ? `Understood. Hey ${profile.name}! I'm Glyph AI, your personal assistant. What can I help you with?`
    : `Understood. I'm Glyph AI, your personal assistant. What can I help you with?`;
  contents.push({
    role: "model",
    parts: [{ text: greeting }],
  });

  // Replay conversation history from client
  for (const msg of clientContents) {
    contents.push({
      role: msg.role === "model" ? "model" : "user",
      parts: [{ text: msg.text }],
    });
  }

  // Use lower temperature for factual/data queries (contact lookups, last message, etc.)
  const isFactualQuery = !!deepContextBlock || /\b(last|latest|recent|unread|how many|who\s+messaged|when\s+did)\b/i.test(message);
  const chatTemp = isFactualQuery ? 0.3 : 0.7;
  const reply = await callGemini(MODEL_CHAT, contents, { temperature: chatTemp });
  return { reply, mode: "chat" };
}

/**
 * SEARCH mode – query user's Firestore messages.
 * Requires auth. Reads chats/{chatId}/messages, ranks by relevance, synthesises.
 */
async function handleSearch(message, userId, options, profile) {
  const db = admin.firestore();
  
  // 0. DETECT SUMMARY INTENT WITHIN SEARCH MODE
  // If the user asks "summarize chat with X" while in search mode, the keyword search will likely fail.
  // Instead, we should route this to the deep history fetcher.
  const summaryTargetMatch = message.match(/(?:summarize|recap|catch\s*up|overview|talking\s+about).*(?:with|chat|conversation)\s+([a-zA-Z0-9\s]+?)(?:\s+please|\s+now|\s+for\s+me|\s*$)/i);
  
  if (summaryTargetMatch) {
    const targetName = summaryTargetMatch[1].trim().toLowerCase();
    
    // We need to find the chat ID. Since we don't have userContext passed to handleSearch,
    // we must fetch the user's chat list first (which we do anyway).
    const chatsSnap = await db
      .collection("chats")
      .where("participants", "array-contains", userId)
      .limit(30) // check last 30 chats
      .get();
      
    if (!chatsSnap.empty) {
      // Resolve names to find the target chat
      let foundChatId = null;
      let foundChatName = "";
      
      // Collect partner UIDs
      const partnerUids = new Set();
      chatsSnap.docs.forEach(d => {
        const parts = d.data().participants || [];
        parts.forEach(p => { if (p !== userId) partnerUids.add(p); });
      });
      
      // Fetch user docs for names
      const uidNameMap = {};
      if (partnerUids.size > 0) {
        const uids = [...partnerUids];
        // Split into chunks of 10 for getAll
        for (let i = 0; i < uids.length; i += 10) {
           const chunk = uids.slice(i, i+10);
           const refs = chunk.map(u => db.collection("users").doc(u));
           const snaps = await db.getAll(...refs);
           snaps.forEach(s => {
             if (s.exists) {
               const d = s.data();
               const n = d.username || d.displayName || d.name || "";
               uidNameMap[s.id] = n;
             }
           });
        }
      }
      
      // Find matching chat
      for (const d of chatsSnap.docs) {
        const parts = d.data().participants || [];
        const partnerId = parts.find(p => p !== userId);
        const name = uidNameMap[partnerId] || "Unknown";
        if (name.toLowerCase().includes(targetName)) {
           foundChatId = d.id;
           foundChatName = name;
           break;
        }
      }
      
      if (foundChatId) {
        console.log(`[SEARCH MODE] Summary intent detected for ${foundChatName}, fetching deep history...`);
        // Fetch last 100 messages with name resolution
        const deepHistory = await fetchDeepChatHistory(foundChatId, userId, 100, uidNameMap);
        
        if (deepHistory) {
           // Synthesize summary
           const contents = [{
             role: "user",
             parts: [{
               text: `You are Glyph AI. The user asked to SEARCH and SUMMARIZE the chat with **${foundChatName}**.\n\n` +
                     `Here is the conversation history:\n---\n${deepHistory}\n---\n\n` +
                     `Please provide a detailed summary of this conversation. Highlight key topics, decisions, and dates.`
             }]
           }];
           
           const reply = await callGemini(MODEL_SEARCH, contents, { temperature: 0.4 });
           return { reply, mode: "search", sources: [] };
        }
      }
    }
  }

  // STANDARD KEYWORD SEARCH LOGIC STARTS HERE
  // But first: if the user's question is about a SPECIFIC CONTACT (not keyword search),
  // use deep-fetch instead of keyword matching — keyword matching fails for this.
  const contactPattern = message.match(/\b(?:from|with|by|to)\s+([a-zA-Z]+(?:\s+[a-zA-Z]+){0,2})/i)
    || message.match(/\b([a-zA-Z][a-zA-Z]{2,}(?:\s+[a-zA-Z]+)?)(?:'s|\s+(?:message|text|chat|sent|said|wrote|last|msg))/i);
  
  if (contactPattern) {
    const possibleName = (contactPattern[1] || "").trim().toLowerCase();
    if (possibleName.length >= 3) {
      // Try to find this contact in the user's chats
      const nameCheckSnap = await db
        .collection("chats")
        .where("participants", "array-contains", userId)
        .limit(30)
        .get();
      
      if (!nameCheckSnap.empty) {
        // Resolve all partner names
        const allPartnerUids = new Set();
        nameCheckSnap.docs.forEach(d => {
          (d.data().participants || []).forEach(p => { if (p !== userId) allPartnerUids.add(p); });
        });
        const searchNameMap = {};
        if (allPartnerUids.size > 0) {
          const uids = [...allPartnerUids];
          for (let i = 0; i < uids.length; i += 10) {
            const chunk = uids.slice(i, i + 10);
            const refs = chunk.map(u => db.collection("users").doc(u));
            const snaps = await db.getAll(...refs);
            snaps.forEach(s => {
              if (s.exists) {
                const d = s.data();
                searchNameMap[s.id] = d.username || d.displayName || d.name || "";
              }
            });
          }
        }
        
        // Find the matching chat
        let targetChatId = null;
        let targetChatName = "";
        for (const d of nameCheckSnap.docs) {
          const parts = d.data().participants || [];
          const partnerId = parts.find(p => p !== userId);
          const name = searchNameMap[partnerId] || "";
          if (name.toLowerCase().includes(possibleName)) {
            targetChatId = d.id;
            targetChatName = name;
            break;
          }
        }
        
        if (targetChatId) {
          console.log(`[SEARCH] Contact-specific query detected for ${targetChatName}, using deep-fetch instead of keyword search`);
          const deepHistory = await fetchDeepChatHistory(targetChatId, userId, 100, searchNameMap);
          
          if (deepHistory) {
            const contents = [{
              role: "user",
              parts: [{
                text: `You are Glyph AI, the intelligent assistant inside the Glyph messaging app.\n\n` +
                  identityBlock(profile) +
                  `\nThe user asked: "${message}"\n\n` +
                  `Here is the COMPLETE recent conversation history with **${targetChatName}** (last 100 messages, ALL types including text, images, videos, files, voice messages, etc.):\n` +
                  `---\n${deepHistory}\n---\n\n` +
                  `INSTRUCTIONS:\n` +
                  `- **The QUICK FACTS section at the top has PRECOMPUTED answers for "last message" questions — use them directly.**\n` +
                  `- Answer the user's question using ONLY the data above.\n` +
                  `- Messages tagged "You:" were sent by the account owner. Messages tagged "${targetChatName}:" were sent by the contact.\n` +
                  `- [IMAGE], [VIDEO], [AUDIO], [FILE], [STICKER], [GIF] tags represent REAL messages of that type.\n` +
                  `- "Last message" means the one with the most recent timestamp, regardless of type.\n` +
                  `- ALWAYS include dates AND times. Every message has a timestamp — never say you don't have one.\n` +
                  `- Be specific: mention dates, times, content, and message types.\n` +
                  `- If a message is just random characters/gibberish, still report it accurately — that IS what was sent.\n`
              }]
            }];
            
            const reply = await callGemini(MODEL_SEARCH, contents, { temperature: 0.3 });
            return { reply, mode: "search", sources: [] };
          }
        }
      }
    }
  }

  const currentUserName = profile.name || "You";

  // 1. Get user's most recent chats
  // NOTE: Do NOT add orderBy here — it requires a composite Firestore index.
  // We score and rank results in JS, so ordering at query level is unnecessary.
  const chatsSnap = await db
    .collection("chats")
    .where("participants", "array-contains", userId)
    .limit(MAX_SEARCH_CHATS)
    .get();

  if (chatsSnap.empty) {
    return {
      reply: "You don't have any chats yet, so there's nothing to search.",
      mode: "search",
      sources: [],
    };
  }

  // 2. Query messages from each chat in parallel
  const queryLower = message.toLowerCase();
  // Filter out common stop words so keyword matching is more precise
  const STOP_WORDS = new Set([
    "the", "a", "an", "is", "was", "are", "were", "be", "been", "being",
    "have", "has", "had", "do", "does", "did", "will", "would", "could",
    "should", "may", "might", "can", "shall", "to", "of", "in", "for",
    "on", "with", "at", "by", "from", "up", "about", "into", "through",
    "and", "but", "or", "nor", "not", "so", "yet", "both", "either",
    "that", "this", "these", "those", "it", "its",
    "what", "when", "where", "who", "whom", "which", "how", "why",
    "all", "each", "every", "any", "few", "more", "most", "some",
    "me", "my", "mine", "you", "your", "yours", "he", "him", "his",
    "she", "her", "hers", "they", "them", "their", "theirs", "we", "us", "our",
    "last", "first", "latest", "recent", "message", "messages", "text", "texts",
    "sent", "send", "said", "wrote", "messaged", "texted", "chat", "chats",
  ]);
  const queryWords = queryLower
    .split(/\s+/)
    .filter((w) => w.length > 2 && !STOP_WORDS.has(w))
    .slice(0, 8); // top 8 meaningful words

  // Pre-resolve all participant names across all chats
  const allParticipantIds = new Set();
  for (const chatDoc of chatsSnap.docs) {
    const cd = chatDoc.data() || {};
    if (Array.isArray(cd.participants)) {
      cd.participants.forEach((p) => allParticipantIds.add(p));
    }
  }
  // Batch-fetch all user profiles in one go
  const nameMap = {};
  const idsToResolve = [...allParticipantIds].filter(Boolean);
  if (idsToResolve.length > 0) {
    try {
      const userRefs = idsToResolve.map((uid) => db.collection("users").doc(uid));
      const userDocs = await db.getAll(...userRefs);
      for (const doc of userDocs) {
        if (doc.exists) {
          const data = doc.data();
          nameMap[doc.id] = data.username || data.displayName || data.name || doc.id;
        }
      }
    } catch (e) {
      console.warn("Failed to resolve user names:", e.message);
    }
  }

  const chatPromises = chatsSnap.docs.map(async (chatDoc) => {
    const chatId = chatDoc.id;
    const chatData = chatDoc.data() || {};
    // Determine the conversation partner's name for this chat
    const otherParticipants = (chatData.participants || [])
      .filter((p) => p !== userId)
      .map((p) => nameMap[p] || p);
    const conversationWith = otherParticipants.join(", ") || "Unknown";

    try {
      // Get recent messages from this chat — ALL types, not just text
      const msgsSnap = await db
        .collection("chats")
        .doc(chatId)
        .collection("messages")
        .orderBy("timestamp", "desc")
        .limit(MAX_SEARCH_MSGS_PER_CHAT)
        .get();

      // Media type keywords for matching queries like "photos", "videos", etc.
      const MEDIA_KEYWORDS = {
        "photo": "IMAGE", "photos": "IMAGE", "image": "IMAGE", "images": "IMAGE", "picture": "IMAGE", "pictures": "IMAGE", "pic": "IMAGE", "pics": "IMAGE",
        "video": "VIDEO", "videos": "VIDEO", "clip": "VIDEO", "clips": "VIDEO",
        "audio": "AUDIO", "voice": "AUDIO", "voicenote": "AUDIO", "voice note": "AUDIO", "voice message": "AUDIO",
        "file": "DOCUMENT", "files": "DOCUMENT", "document": "DOCUMENT", "documents": "DOCUMENT", "pdf": "DOCUMENT", "doc": "DOCUMENT",
        "sticker": "STICKER", "stickers": "STICKER",
        "gif": "GIF", "gifs": "GIF",
        "media": "_ANY_MEDIA", "attachment": "_ANY_MEDIA", "attachments": "_ANY_MEDIA",
      };
      const ANY_MEDIA_TYPES = new Set(["IMAGE", "VIDEO", "AUDIO", "DOCUMENT", "FILE", "GIF", "STICKER", "MEDIA_GROUP", "MEME", "KLIPY_EMOJI"]);

      // Determine if the query is looking for a specific media type
      let queryMediaType = null;
      for (const w of queryWords) {
        if (MEDIA_KEYWORDS[w]) {
          queryMediaType = MEDIA_KEYWORDS[w];
          break;
        }
      }

      const results = [];
      for (const msgDoc of msgsSnap.docs) {
        const d = msgDoc.data();
        if (d.isDeletedForAll) continue;

        const text = d.text || "";
        const msgType = (d.type || "TEXT").toUpperCase();
        const contentLabel = formatMessageContent(d);
        const fileName = (d.fileName || "").toLowerCase();

        // Score calculation — considers text, media type, and file names
        const textLower = text.toLowerCase();
        const contentLabelLower = contentLabel.toLowerCase();
        let score = 0;

        // Text content matching
        if (text.length >= 3) {
          for (const w of queryWords) {
            if (textLower.includes(w)) score++;
          }
          if (textLower.includes(queryLower)) score += 3;
        }

        // File name matching
        if (fileName) {
          for (const w of queryWords) {
            if (fileName.includes(w)) score += 2;
          }
        }

        // Media type matching — if user asks for "photos", match IMAGE messages
        if (queryMediaType) {
          if (queryMediaType === "_ANY_MEDIA" && ANY_MEDIA_TYPES.has(msgType)) {
            score += 3;
          } else if (msgType === queryMediaType) {
            score += 4; // Strong boost for exact type match
          }
        }

        if (score > 0) {
          const sid = d.senderId || "";
          const isCurrentUser = sid === userId;
          const roleTag = isCurrentUser
            ? "[YOU]"
            : `[CONTACT: ${nameMap[sid] || "Unknown"}]`;
          
          // Build a rich display text that includes both content and type
          let displayText = contentLabel;
          if (text && msgType !== "TEXT") {
            displayText = `${contentLabel} — caption: "${text.slice(0, 150)}"`;
          }
          if (d.fileName && msgType !== "TEXT") {
            displayText = `${contentLabel} (${d.fileName})`;
          }
          
          results.push({
            chatId,
            msgId: msgDoc.id,
            text: displayText.length > 300 ? displayText.slice(0, 300) + "…" : displayText,
            timestamp: d.timestamp?.toMillis?.() || d.timestamp || 0,
            senderId: sid,
            senderName: roleTag,
            conversationWith,
            score,
            msgType,
          });
        }
      }
      return results;
    } catch (err) {
      console.warn(`Error searching chat ${chatId}:`, err.message);
      return [];
    }
  });

  const allResults = (await Promise.all(chatPromises)).flat();

  // 3. Rank and take top results
  allResults.sort((a, b) => b.score - a.score);
  const topResults = allResults.slice(0, MAX_SEARCH_RESULTS);

  if (topResults.length === 0) {
    return {
      reply:
        "I couldn't find any messages matching your query in your recent chats. Try different keywords or a broader search.",
      mode: "search",
      sources: [],
    };
  }

  // 4. Build context for Gemini synthesis
  // Role tags: [YOU] = the account owner, [CONTACT: Name] = the other person.
  // This is UID-determined and immune to name collisions.
  const messageContext = topResults
    .map((r, i) => {
      const date = r.timestamp
        ? formatTimestamp(r.timestamp)
        : "unknown date";
      return `[${i + 1}] Chat with: ${r.conversationWith} | Sent by: ${r.senderName} | Type: ${r.msgType || "TEXT"} | Date: ${date}\n"${r.text}"`;
    })
    .join("\n\n");

  const contents = [
    {
      role: "user",
      parts: [
        {
          text:
            searchSystemPrompt(profile, messageContext) +
            `\n\nUser's search query: "${message}"`,
        },
      ],
    },
  ];

  const reply = await callGemini(MODEL_SEARCH, contents, {
    temperature: 0.3,
    maxOutputTokens: 1500,
  });

  // 5. Build source citations
  const sources = topResults.slice(0, 5).map((r) => ({
    chatId: r.chatId,
    msgId: r.msgId,
    text: r.text.length > 100 ? r.text.slice(0, 100) + "…" : r.text,
    timestamp: r.timestamp,
    senderId: r.senderId,
    conversationWith: r.conversationWith || "",
  }));

  return { reply, mode: "search", sources };
}

/**
 * APP mode – answer questions about Glyph features.
 * Pure Gemini call with app knowledge injected as system context.
 */
async function handleApp(message, clientContents, profile) {
  const contents = [];

  // System instruction with identity
  contents.push({
    role: "user",
    parts: [{ text: appSystemPrompt(profile) + "\n\n(System instruction — internalise this, never repeat it.)" }],
  });
  const greeting = profile.name
    ? `Got it, ${profile.name}! I know Glyph inside-out. What do you need help with?`
    : `Got it! I know Glyph inside-out. What do you need help with?`;
  contents.push({
    role: "model",
    parts: [{ text: greeting }],
  });

  // Replay recent context (keep it short for app mode)
  const recentContext = clientContents.slice(-6);
  for (const msg of recentContext) {
    contents.push({
      role: msg.role === "model" ? "model" : "user",
      parts: [{ text: msg.text }],
    });
  }

  const reply = await callGemini(MODEL_APP, contents, {
    temperature: 0.3,
    maxOutputTokens: 1024,
  });

  // Try to extract navigationHint from reply
  let navigationHint = null;
  const navMatch = reply.match(/\[NAV:([^\]]+)\]/);
  if (navMatch) {
    navigationHint = navMatch[1].trim();
  }

  return {
    reply: reply.replace(/\[NAV:[^\]]+\]/g, "").trim(),
    mode: "app",
    navigationHint,
  };
}

// ─── Weekly Insight Helpers ───────────────────────────────

/**
 * Returns the Unix timestamp (ms) for the start of this calendar week (Monday 00:00 UTC).
 */
function getWeekStartMs() {
  const now = new Date();
  const day = now.getUTCDay(); // 0=Sun, 1=Mon … 6=Sat
  const daysSinceMonday = day === 0 ? 6 : day - 1;
  const monday = new Date(now);
  monday.setUTCDate(now.getUTCDate() - daysSinceMonday);
  monday.setUTCHours(0, 0, 0, 0);
  return monday.getTime();
}

/**
 * Fetch all chats for a user and batch-resolve participant names.
 * Returns { chatsSnap, nameMap, partnerMap }
 */
async function fetchUserChatsWithNames(userId) {
  const db = admin.firestore();
  const chatsSnap = await db
    .collection("chats")
    .where("participants", "array-contains", userId)
    .limit(50)
    .get();

  if (chatsSnap.empty) return { chatsSnap, nameMap: {}, partnerMap: {} };

  const allUids = new Set();
  for (const doc of chatsSnap.docs) {
    const d = doc.data() || {};
    if (Array.isArray(d.participants)) d.participants.forEach((p) => allUids.add(p));
  }

  const nameMap = {};
  const uids = [...allUids].filter(Boolean);
  if (uids.length > 0) {
    const refs = uids.map((uid) => db.collection("users").doc(uid));
    const docs = await db.getAll(...refs);
    for (const doc of docs) {
      if (doc.exists) {
        const d = doc.data();
        nameMap[doc.id] = d.username || d.displayName || d.name || doc.id;
      }
    }
  }

  const partnerMap = {};
  for (const chatDoc of chatsSnap.docs) {
    const d = chatDoc.data() || {};
    const partners = (d.participants || [])
      .filter((p) => p !== userId)
      .map((p) => nameMap[p] || "Unknown");
    partnerMap[chatDoc.id] = partners.join(", ");
  }

  return { chatsSnap, nameMap, partnerMap };
}

/**
 * SHORTCUT: "Summarize my chats this week"
 * Fetches all text messages from this week across all chats, then asks Gemini
 * to produce a structured, grouped summary.
 */
async function handleWeeklySummary(userId, profile) {
  const db = admin.firestore();
  const weekStart = getWeekStartMs();
  const weekStartDate = new Date(weekStart).toLocaleDateString("en-US", {
    timeZone: _userTimezone, weekday: "long", month: "short", day: "numeric",
  });

  const { chatsSnap, partnerMap } = await fetchUserChatsWithNames(userId);
  if (chatsSnap.empty) {
    return { reply: "You don't have any chats yet.", mode: "search", sources: [] };
  }

  const chatPromises = chatsSnap.docs.map(async (chatDoc) => {
    const chatId = chatDoc.id;
    const partnerName = partnerMap[chatId] || "Unknown";
    try {
      const msgsSnap = await db
        .collection("chats").doc(chatId).collection("messages")
        .orderBy("timestamp", "desc").limit(120)
        .get();

      const weekMsgs = msgsSnap.docs
        .map((d) => d.data())
        .filter((d) => {
          const ts = d.timestamp?.toMillis ? d.timestamp.toMillis() : (d.timestamp || 0);
          return ts >= weekStart && !d.isDeletedForAll;
        });

      if (weekMsgs.length === 0) return null;

      const lines = weekMsgs.reverse().map((m) => {
        const label = m.senderId === userId ? "You" : partnerName;
        const date = m.timestamp?.toMillis
          ? new Date(m.timestamp.toMillis()).toLocaleDateString("en-US", {
              timeZone: _userTimezone, weekday: "short", month: "short", day: "numeric",
            })
          : "";
        const content = formatMessageContent(m);
        const txt = content.length > 120 ? content.slice(0, 120) + "…" : content;
        return `[${date}] ${label}: "${txt}"`;
      });

      return { partnerName, lines, count: weekMsgs.length };
    } catch (e) {
      console.warn(`WeeklySummary: error fetching ${chatId}:`, e.message);
      return null;
    }
  });

  const results = (await Promise.all(chatPromises)).filter(Boolean);

  if (results.length === 0) {
    return {
      reply: `No messages found this week (since ${weekStartDate}). Your chats have been quiet! 🤫`,
      mode: "search",
      sources: [],
    };
  }

  const totalMessages = results.reduce((sum, r) => sum + r.count, 0);
  const contextBlocks = results
    .map((r) => `=== Chat with ${r.partnerName} (${r.count} messages) ===\n${r.lines.join("\n")}`)
    .join("\n\n");

  const weekLabel = new Date(weekStart).toLocaleDateString("en-US", { timeZone: _userTimezone, month: "short", day: "numeric" });
  const todayLabel = new Date().toLocaleDateString("en-US", { timeZone: _userTimezone, month: "short", day: "numeric" });

  const prompt =
    `You are Glyph AI. The user (${profile.name || "the account owner"}) asked for a WEEKLY CHAT SUMMARY.\n\n` +
    `Data: ${results.length} active chats, ${totalMessages} total messages, week starting ${weekStartDate}.\n\n` +
    `${contextBlocks}\n\n` +
    `OUTPUT RULES — READ CAREFULLY:\n` +
    `1. Write in PLAIN TEXT ONLY. No markdown. No #, *, **, --, >, or any symbols used for formatting.\n` +
    `2. Use ONLY these characters for structure: emoji, newlines, and Unicode line: ─────────────────────\n` +
    `3. Structure your response EXACTLY using this template (copy the layout precisely):\n\n` +
    `💬  WEEKLY CHAT SUMMARY\n` +
    `${weekLabel} — ${todayLabel}\n` +
    `─────────────────────\n` +
    `📊  [X] conversations  ·  [Y] messages\n\n` +
    `Then for EACH conversation in order of activity (most messages first):\n\n` +
    `👤  [Contact Name]\n` +
    `[X] messages\n` +
    `→  [One-sentence topic summary or mood of the chat]\n` +
    `◆  [Key point 1 — max 12 words]\n` +
    `◆  [Key point 2 — max 12 words]\n` +
    `◆  [Any plan, decision, or important detail — max 12 words]\n` +
    `(Skip bullets if only 1–2 messages — use just the → line instead)\n\n` +
    `After the last contact, add a single divider ─────────────────────\n` +
    `Then end with ONE closing insight line, e.g.: 💡  Most active chat: [Name] with [X] messages.\n\n` +
    `4. Replace ALL placeholders [X], [Y], [Contact Name], etc. with real values from the data.\n` +
    `5. Never quote raw messages verbatim. Summarise only.\n` +
    `6. Use "you" for the owner, the contact's name for the other person.\n` +
    `7. Keep the total response under 400 words.\n`;

  const reply = await callGemini(MODEL_SEARCH, [{ role: "user", parts: [{ text: prompt }] }], {
    temperature: 0.2,
    maxOutputTokens: 2000,
  });

  return { reply, mode: "search", sources: [] };
}

/**
 * SHORTCUT: "Documents sent and received this week"
 * Queries DOCUMENT-type messages across all chats this week and formats
 * a structured, grouped report — no Gemini needed, pure data display.
 */
async function handleWeeklyDocuments(userId, profile) {
  const db = admin.firestore();
  const weekStart = getWeekStartMs();
  const weekStartDate = new Date(weekStart).toLocaleDateString("en-US", {
    timeZone: _userTimezone, weekday: "long", month: "short", day: "numeric",
  });

  const { chatsSnap, partnerMap } = await fetchUserChatsWithNames(userId);
  if (chatsSnap.empty) {
    return { reply: "You don't have any chats yet.", mode: "search", sources: [] };
  }

  const DOCUMENT_TYPES = new Set(["DOCUMENT", "document", "file", "FILE"]);

  const chatPromises = chatsSnap.docs.map(async (chatDoc) => {
    const chatId = chatDoc.id;
    const partnerName = partnerMap[chatId] || "Unknown";
    try {
      const msgsSnap = await db
        .collection("chats").doc(chatId).collection("messages")
        .orderBy("timestamp", "desc").limit(120)
        .get();

      const docs = msgsSnap.docs
        .map((d) => d.data())
        .filter((d) => {
          const ts = d.timestamp?.toMillis ? d.timestamp.toMillis() : (d.timestamp || 0);
          return ts >= weekStart && DOCUMENT_TYPES.has(d.type) && !d.isDeletedForAll;
        });

      if (docs.length === 0) return null;

      return {
        partnerName,
        items: docs.reverse().map((d) => ({
          isMe: d.senderId === userId,
          fileName: d.fileName || d.text || "Unknown file",
          fileType: d.mimeType || d.fileType || "",
          date: d.timestamp?.toMillis
            ? new Date(d.timestamp.toMillis()).toLocaleDateString("en-US", {
                timeZone: _userTimezone, weekday: "short", month: "short", day: "numeric",
              })
            : "",
        })),
      };
    } catch (e) {
      return null;
    }
  });

  const results = (await Promise.all(chatPromises)).filter(Boolean);

  if (results.length === 0) {
    return {
      reply: `📄 No documents were sent or received this week (since ${weekStartDate}).`,
      mode: "search",
      sources: [],
    };
  }

  const totalSent     = results.reduce((acc, r) => acc + r.items.filter((i) => i.isMe).length, 0);
  const totalReceived = results.reduce((acc, r) => acc + r.items.filter((i) => !i.isMe).length, 0);
  const totalDocs     = totalSent + totalReceived;

  // Helper: derive a clean file-type label from mimeType or extension
  function docTypeIcon(fileType, fileName) {
    const raw = (fileType || fileName || "").toLowerCase();
    if (raw.includes("pdf"))                          return "📕 PDF";
    if (raw.includes("word") || raw.match(/\.docx?/)) return "📘 Word";
    if (raw.includes("sheet") || raw.match(/\.xlsx?/))return "📗 Excel";
    if (raw.includes("presentation") || raw.match(/\.pptx?/)) return "📙 PPT";
    if (raw.includes("zip") || raw.includes("rar") || raw.includes("7z")) return "🗜️ Archive";
    if (raw.includes("text") || raw.match(/\.txt$/)) return "📄 Text";
    if (raw.includes("image") || raw.match(/\.(jpg|jpeg|png|gif|webp)$/)) return "🖼️ Image";
    return "📎 File";
  }

  const sections = results.map((r) => {
    const sent     = r.items.filter((i) => i.isMe);
    const received = r.items.filter((i) => !i.isMe);
    const lines = [
      `👤  ${r.partnerName}`,
      `${r.items.length} document${r.items.length > 1 ? "s" : ""}  ·  📤 ${sent.length} sent  ·  📥 ${received.length} received`,
    ];
    if (sent.length > 0) {
      lines.push("");
      lines.push("  📤  Sent");
      sent.forEach((d) => {
        const icon = docTypeIcon(d.fileType, d.fileName);
        const datePart = d.date ? `  ·  ${d.date}` : "";
        lines.push(`       ${icon}  ${d.fileName}${datePart}`);
      });
    }
    if (received.length > 0) {
      lines.push("");
      lines.push("  📥  Received");
      received.forEach((d) => {
        const icon = docTypeIcon(d.fileType, d.fileName);
        const datePart = d.date ? `  ·  ${d.date}` : "";
        lines.push(`       ${icon}  ${d.fileName}${datePart}`);
      });
    }
    return lines.join("\n");
  });

  const weekLabel = new Date(weekStart).toLocaleDateString("en-US", { timeZone: _userTimezone, month: "short", day: "numeric" });
  const todayLabel = new Date().toLocaleDateString("en-US", { timeZone: _userTimezone, month: "short", day: "numeric" });

  const header = [
    `📁  DOCUMENTS THIS WEEK`,
    `${weekLabel} — ${todayLabel}`,
    `─────────────────────`,
    `📊  ${totalDocs} document${totalDocs !== 1 ? "s" : ""}  across  ${results.length} chat${results.length !== 1 ? "s" : ""}`,
    `     📤 ${totalSent} sent   ·   📥 ${totalReceived} received`,
    ``,
  ].join("\n");

  const divider = "\n─────────────────────\n";
  return { reply: header + sections.join(divider), mode: "search", sources: [] };
}

/**
 * SHORTCUT: "Media sent and received this week"
 * Queries IMAGE / VIDEO / MEDIA_GROUP / GIF / AUDIO messages this week
 * and returns a contact-grouped stats summary.
 */
async function handleWeeklyMedia(userId, profile) {
  const db = admin.firestore();
  const weekStart = getWeekStartMs();
  const weekStartDate = new Date(weekStart).toLocaleDateString("en-US", {
    timeZone: _userTimezone, weekday: "long", month: "short", day: "numeric",
  });

  const { chatsSnap, partnerMap } = await fetchUserChatsWithNames(userId);
  if (chatsSnap.empty) {
    return { reply: "You don't have any chats yet.", mode: "search", sources: [] };
  }

  const MEDIA_TYPES = new Set([
    "IMAGE", "image", "VIDEO", "video",
    "MEDIA_GROUP", "media_group",
    "GIF", "gif", "AUDIO", "audio",
  ]);

  function mediaLabel(type) {
    const t = (type || "").toUpperCase();
    if (t === "IMAGE") return "📷 Photos";
    if (t === "VIDEO") return "🎥 Videos";
    if (t === "GIF")   return "🎭 GIFs";
    if (t === "AUDIO") return "🎵 Audio";
    if (t === "MEDIA_GROUP") return "📸 Media groups";
    return "📎 Media";
  }

  const chatPromises = chatsSnap.docs.map(async (chatDoc) => {
    const chatId = chatDoc.id;
    const partnerName = partnerMap[chatId] || "Unknown";
    try {
      const msgsSnap = await db
        .collection("chats").doc(chatId).collection("messages")
        .orderBy("timestamp", "desc").limit(120)
        .get();

      const mediaItems = msgsSnap.docs
        .map((d) => d.data())
        .filter((d) => {
          const ts = d.timestamp?.toMillis ? d.timestamp.toMillis() : (d.timestamp || 0);
          return ts >= weekStart && MEDIA_TYPES.has(d.type) && !d.isDeletedForAll;
        });

      if (mediaItems.length === 0) return null;

      const typeCounts = {};
      mediaItems.forEach((m) => {
        const lbl = mediaLabel(m.type);
        typeCounts[lbl] = (typeCounts[lbl] || 0) + 1;
      });

      const sent     = mediaItems.filter((m) => m.senderId === userId).length;
      const received = mediaItems.length - sent;

      const timestamps = mediaItems
        .map((m) => (m.timestamp?.toMillis ? m.timestamp.toMillis() : 0))
        .filter((t) => t > 0)
        .sort((a, b) => a - b);

      const fmt = (ts) =>
        new Date(ts).toLocaleDateString("en-US", {
          timeZone: _userTimezone, weekday: "short", month: "short", day: "numeric",
        });
      const dateRange =
        timestamps.length > 1
          ? `${fmt(timestamps[0])} – ${fmt(timestamps[timestamps.length - 1])}`
          : timestamps.length === 1 ? fmt(timestamps[0]) : "";

      return { partnerName, total: mediaItems.length, sent, received, typeCounts, dateRange };
    } catch (e) {
      return null;
    }
  });

  const results = (await Promise.all(chatPromises)).filter(Boolean);

  if (results.length === 0) {
    return {
      reply: `🖼️ No media was shared this week (since ${weekStartDate}).`,
      mode: "search",
      sources: [],
    };
  }

  const totalMedia    = results.reduce((acc, r) => acc + r.total, 0);
  const totalSent     = results.reduce((acc, r) => acc + r.sent, 0);
  const totalReceived = results.reduce((acc, r) => acc + r.received, 0);

  // Build a global type breakdown across all chats
  const globalTypeCounts = {};
  results.forEach((r) => {
    Object.entries(r.typeCounts).forEach(([lbl, cnt]) => {
      globalTypeCounts[lbl] = (globalTypeCounts[lbl] || 0) + cnt;
    });
  });
  const globalTypeBar = Object.entries(globalTypeCounts)
    .sort((a, b) => b[1] - a[1])
    .map(([lbl, cnt]) => `${lbl} ${cnt}`)
    .join("  ·  ");

  const sections = results.map((r) => {
    const typeLines = Object.entries(r.typeCounts)
      .sort((a, b) => b[1] - a[1])
      .map(([lbl, cnt]) => `       ${lbl}  ${cnt}`);
    const lines = [
      `👤  ${r.partnerName}`,
      `${r.total} item${r.total > 1 ? "s" : ""}${r.dateRange ? "   ·   " + r.dateRange : ""}`,
      ``,
      `   📤 Sent       ${r.sent}`,
      `   📥 Received   ${r.received}`,
      ``,
      `   Breakdown:`,
      ...typeLines,
    ];
    return lines.join("\n");
  });

  const weekLabel  = new Date(weekStart).toLocaleDateString("en-US", { timeZone: _userTimezone, month: "short", day: "numeric" });
  const todayLabel = new Date().toLocaleDateString("en-US", { timeZone: _userTimezone, month: "short", day: "numeric" });

  const header = [
    `🖼️  MEDIA THIS WEEK`,
    `${weekLabel} — ${todayLabel}`,
    `─────────────────────`,
    `📊  ${totalMedia} item${totalMedia !== 1 ? "s" : ""}  across  ${results.length} chat${results.length !== 1 ? "s" : ""}`,
    `     📤 ${totalSent} sent   ·   📥 ${totalReceived} received`,
    globalTypeBar ? `     ${globalTypeBar}` : "",
    ``,
  ].filter((l) => l !== "").join("\n");

  const divider = "\n─────────────────────\n";
  return { reply: header + divider + sections.join(divider), mode: "search", sources: [] };
}

// ─── Main Cloud Function ──────────────────────────────────

exports.glyphAiAgent = functions
  .runWith({
    timeoutSeconds: 60,
    memory: "512MB",
  })
  .https.onCall(async (data, context) => {
    const tStart = Date.now();
    console.log("=== glyphAiAgent request ===");

    // 1. Auth (optional for chat/app, required for search)
    const userId =
      context.auth?.uid || `anon_${context.rawRequest?.ip || "unknown"}`;

    // 2. Parse input
    const { mode, message, contents: clientContents = [], options } = data;

    // Set user timezone from client options (IANA timezone ID, e.g., "Asia/Kolkata")
    _userTimezone = "UTC";
    _userTzLabel = "UTC";
    if (options?.timezone) {
      try {
        // Validate the timezone by trying to use it
        new Date().toLocaleString("en-US", { timeZone: options.timezone });
        _userTimezone = options.timezone;
        _userTzLabel = options.timezone;
        console.log(`[TZ] Using client timezone: ${_userTimezone}`);
      } catch (e) {
        console.warn(`[TZ] Invalid timezone "${options.timezone}", falling back to UTC`);
      }
    }

    // 3. Warmup handler
    if (mode === "warmup") {
      console.log("Warmup ping acknowledged");
      return { reply: "ready", mode: "warmup", cached: false };
    }

    // 4. Validate
    if (!mode || !["chat", "search", "app"].includes(mode)) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        'Invalid mode. Must be "chat", "search", or "app".'
      );
    }

    if (!message || typeof message !== "string" || message.trim().length === 0) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        "Message is required."
      );
    }

    if (message.length > MAX_MESSAGE_LENGTH) {
      throw new functions.https.HttpsError(
        "invalid-argument",
        `Message too long. Maximum ${MAX_MESSAGE_LENGTH} characters.`
      );
    }

    // 5. Search mode requires auth
    if (mode === "search" && !context.auth?.uid) {
      throw new functions.https.HttpsError(
        "permission-denied",
        "Authentication required for chat search."
      );
    }

    // 6. Rate limit
    checkRateLimit(userId);

    // 7. Fetch user profile (all modes need identity awareness)
    const profile = context.auth?.uid
      ? await fetchUserProfile(context.auth.uid)
      : { uid: userId, name: null, phone: null };

    // 7b. Fetch extended user context (chats, contacts, unread)
    let userContext = null;
    if (context.auth?.uid) {
      userContext = await fetchUserContext(context.auth.uid);
    }

    // 8. Smart intent detection — auto-route from chat to correct mode
    // Skip for shortcut queries: the client already signals the exact handler to use.
    const isShortcutQuery = options?.shortcutType != null;
    let effectiveMode = mode;
    let autoRouted = false;
    if (context.auth?.uid && !isShortcutQuery) {
      const detectedMode = detectIntent(message, mode);
      if (detectedMode) {
        console.log(`Intent detected: user in [${mode}] but message wants [${detectedMode}] — auto-routing`);
        effectiveMode = detectedMode;
        autoRouted = true;
      }
    }

    // 9. Route to handler
    let result;
    try {
      switch (effectiveMode) {
        case "chat":
          result = await handleChat(message, clientContents, profile, userContext, options);
          break;
        case "search": {
          const shortcutType = options?.shortcutType;
          if (shortcutType === "weekly_summary") {
            result = await handleWeeklySummary(context.auth.uid, profile);
          } else if (shortcutType === "weekly_documents") {
            result = await handleWeeklyDocuments(context.auth.uid, profile);
          } else if (shortcutType === "weekly_media") {
            result = await handleWeeklyMedia(context.auth.uid, profile);
          } else {
            result = await handleSearch(message, context.auth.uid, options, profile);
          }
          break;
        }
        case "app":
          result = await handleApp(message, clientContents, profile);
          break;
      }
    } catch (err) {
      if (err instanceof functions.https.HttpsError) throw err;
      console.error(`glyphAiAgent [${effectiveMode}] error:`, err.message);
      throw new functions.https.HttpsError(
        "internal",
        "AI Agent request failed. Please try again."
      );
    }

    const totalMs = Date.now() - tStart;
    console.log(`=== glyphAiAgent [${effectiveMode}] done in ${totalMs}ms ===${autoRouted ? ` (auto-routed from ${mode})` : ""}`);

    return {
      ...result,
      cached: false,
      // Tell client which mode was actually used so it can switch tabs
      suggestedMode: autoRouted ? effectiveMode : undefined,
    };
  });
