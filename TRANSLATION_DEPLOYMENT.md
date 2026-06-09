# Translation Feature Deployment Guide

## Prerequisites

1. Firebase CLI installed: `npm install -g firebase-tools`
2. Firebase project initialized
3. Google Cloud API key: `AIzaSyCjfsVLyBS29ENDFNdeTrHNK3WukyMEBPU`

## 1. Enable Required APIs

In [Google Cloud Console](https://console.cloud.google.com/), enable:
- **Generative Language API** (for Gemini)
- **Cloud Text-to-Speech API**

## 2. Set API Key as Environment Variable

```bash
cd functions
firebase functions:config:set google.api_key="AIzaSyCjfsVLyBS29ENDFNdeTrHNK3WukyMEBPU"
```

## 3. Deploy Cloud Function

```bash
firebase deploy --only functions:translateMessage
```

## 4. Verify Deployment

The function will be available at:
```
https://us-central1-[PROJECT-ID].cloudfunctions.net/translateMessage
```

## 5. Test from Android App

1. Build and install the debug APK
2. Open a chat with text messages  
3. Tap any text message bubble
4. Tap **🌐 Translate** in the floating toolbar
5. Select target language via **🌍 Language**
6. Tap **🔊 Play** to hear TTS audio

## Security Notes

✅ **API key is secure**: Stored only in Cloud Functions environment  
✅ **No client exposure**: Android app never sees the API key  
✅ **Rate limited**: 20 requests per minute per user  
✅ **Cached**: Duplicate translations served from cache  

## Troubleshooting

- **"API key not configured"**: Check step 2
- **"Translation failed"**: Check Cloud Console logs
- **No audio**: Check TTS API is enabled
- **Rate limited**: Wait 1 minute between requests

## Cost Estimation

- **Gemini API**: ~$0.000125 per request (1K input + output tokens)
- **Cloud TTS**: ~$0.000016 per character (Neural2 voices)  
- **Firebase Storage**: ~$0.026/GB/month
- **Cloud Functions**: ~$0.0000004 per invocation

Example: 100 translations/day = ~$0.50/month