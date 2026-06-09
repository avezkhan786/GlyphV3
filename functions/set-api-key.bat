@echo off
REM set-api-key.bat - Configure Google Cloud API key for Firebase Functions

echo 🔑 Setting Google Cloud API key in Firebase Functions config...
echo.

firebase functions:config:set google.api_key="AIzaSyCF_le4EQUynxvclmyR1zH16pi5D6iTr_Y"

if errorlevel 1 (
    echo.
    echo ❌ Failed to set API key. Make sure you're logged in:
    echo    firebase login
    pause
    exit /b 1
)

echo.
echo ✅ API key configured successfully!
echo.
echo 🔄 Now redeploy the function:
echo    firebase deploy --only functions:translateMessage
echo.
pause
