@echo off
REM deploy-translation.bat - Deploy translation feature to Firebase (Windows)

set API_KEY=AIzaSyCF_le4EQUynxvclmyR1zH16pi5D6iTr_Y

echo 🚀 Deploying Glyph Translation Feature
echo ======================================

REM Check if Firebase CLI is installed
firebase --version >nul 2>&1
if errorlevel 1 (
    echo ❌ Firebase CLI not found. Install with: npm install -g firebase-tools
    pause
    exit /b 1
)

REM Check if logged in to Firebase
firebase projects:list >nul 2>&1
if errorlevel 1 (
    echo 🔐 Please login to Firebase first:
    firebase login
)

echo 📝 Setting API key configuration...
cd functions
firebase functions:config:set google.api_key="%API_KEY%"

echo 📦 Installing dependencies...
npm install

echo 🚀 Deploying Cloud Function...
firebase deploy --only functions:translateMessage

echo.
echo ✅ Translation feature deployed successfully!
echo.
echo 📱 Next steps:
echo   1. Build the Android app: .\gradlew.bat assembleDebug
echo   2. Install on device and test by tapping any text message
echo.
echo 🔧 Troubleshooting:
echo   - View logs: firebase functions:log
echo   - Check config: firebase functions:config:get
echo.
pause