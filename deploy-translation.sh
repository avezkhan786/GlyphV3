#!/bin/bash
# deploy-translation.sh - Deploy translation feature to Firebase
# NOTE: Do NOT hardcode API keys here. Load from functions/.env or pass via environment.

set -e

# The API key is stored in functions/.env (gitignored).
# Firebase CLI reads .env during deploy and sets it as process.env.GOOGLE_CLOUD_API_KEY.
# See functions/.env.example for the required format.

echo "🚀 Deploying Glyph Translation Feature"
echo "======================================"

# Check if Firebase CLI is installed
if ! command -v firebase &> /dev/null; then
    echo "❌ Firebase CLI not found. Install with: npm install -g firebase-tools"
    exit 1
fi

# Check if logged in to Firebase
if ! firebase projects:list &> /dev/null; then
    echo "🔐 Please login to Firebase first:"
    firebase login
fi

echo "📝 Deploying functions (API key loaded from functions/.env)..."
cd functions

echo "📦 Installing dependencies..."
npm install

echo "🚀 Deploying Cloud Function..."
firebase deploy --only functions:translateMessage

echo
echo "✅ Translation feature deployed successfully!"
echo
echo "📱 Next steps:"
echo "  1. Build the Android app: ./gradlew assembleDebug"
echo "  2. Install on device and test by tapping any text message"
echo
echo "🔧 Troubleshooting:"
echo "  - View logs: firebase functions:log"
echo "  - Check config: firebase functions:config:get"
echo