#!/bin/bash
# deploy-translation.sh - Deploy translation feature to Firebase

set -e

API_KEY="AIzaSyCjfsVLyBS29ENDFNdeTrHNK3WukyMEBPU"

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

echo "📝 Setting API key configuration..."
cd functions
firebase functions:config:set google.api_key="$API_KEY"

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