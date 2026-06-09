# Glyph V3 Feature Overview

This document summarizes the implemented product capabilities of the native Android chat application based on the current project structure, manifest, screens, services, repositories, and supporting modules.

## 1. Core Messaging

### 1. One-to-one chat
Supports direct conversations between users with a dedicated chat screen, persistent chat history, and real-time message delivery.

### 2. Real-time message sync
Uses Firebase-backed repositories and notification services to keep conversations updated across active and background app states.

### 3. Message delivery states
Tracks message progress such as sending, sent, delivered, read, and voice-played states to improve communication clarity.

### 4. Rich text messaging
Allows standard typed conversations with a modern chat composer and responsive message rendering.

### 5. Reply to message
Users can respond to a specific earlier message, preserving context inside busy conversations.

### 6. Swipe-to-reply
Includes gesture-based reply behavior for fast contextual responses directly from message rows.

### 7. Draft handling
Stores draft text so unfinished messages are not lost when users leave and re-enter a chat.

### 8. Typing indicators
Displays live typing activity to make conversations feel more immediate and interactive.

### 9. Expressive typing layer
Extends standard typing with animated and sentiment-aware visuals for a more emotional, premium conversation experience.

### 10. Disappearing messages
Supports timer-based message expiry with configurable durations for more private and clutter-free chats.

### 11. Message details
Provides dedicated message detail and inspection screens for deeper visibility into message context and state.

## 2. Media and Attachments

### 12. Image sharing
Users can send photos in chat with thumbnail generation, preview support, and media storage handling.

### 13. Video sharing
Supports video messaging with preview, playback, and transfer handling.

### 14. Document sharing
Allows sending files and documents with caption support and document-oriented message layouts.

### 15. Contact sharing
Users can send contact cards directly inside chat for quick profile and identity exchange.

### 16. Multi-attachment sharing
Supports sending multiple media items together, including grouped media flows and collage-style layouts.

### 17. Media captions
Lets users add contextual text when sending images, videos, and documents.

### 18. Media compression options
Includes compression selection flows to balance quality, upload speed, and storage use.

### 19. Video note messaging
Supports circular or short-form video note experiences as a distinct message type.

### 20. Voice message recording
Enables in-chat voice recording with waveform feedback, send/cancel flows, and recording lock behavior.

### 21. Voice message playback
Provides custom playback UI with progress, waveform visualization, and message state integration.

### 22. In-app camera
Includes a native camera capture flow for faster media creation without leaving the app.

### 23. Media viewer
Supports full-screen media viewing for images and videos after they are shared.

### 24. Zoomable image viewing
Provides pinch-to-zoom image inspection for higher quality media consumption.

### 25. Thumbnail generation
Generates thumbnails for media and documents to make the chat list and message feed more visual and scannable.

## 3. Calls and Live Communication

### 26. Voice calling
Supports real-time one-to-one audio calls with full-screen incoming and active call flows.

### 27. Video calling
Supports one-to-one video calls with camera controls and foreground service support.

### 28. Group voice and video calls
Enables multi-party calling with participant management and adaptive grid layouts for active sessions.

### 29. Call controls
Includes mute, speaker, camera toggle, camera switch, minimize, and end-call controls.

### 30. Call notifications and lock-screen incoming calls
Uses full-screen and actionable notifications so users can respond quickly even when the device is locked.

### 31. Unanswered call flow
Provides a dedicated experience for missed or unanswered outgoing calls.

### 32. Call history
Stores and presents previous call activity for quick recall and follow-up.

### 33. Favorite call contacts
Lets users pin frequent calling contacts for faster access.

### 34. Live audio sharing
Supports microphone-based live audio broadcasting with settings for audience control and active session shutdown.

### 35. Walkie-talkie mode
Adds a push-to-talk communication experience optimized for instant voice interaction rather than conventional calls.

### 36. Walkie-talkie auto-accept settings
Allows controlled auto-accept behavior with audience scoping, making the feature suitable for trusted contacts and fast-response use cases.

## 4. Status and Social Sharing

### 37. Status updates
Supports story-style status publishing separate from direct chat conversations.

### 38. Text status
Allows lightweight text-first status posts.

### 39. Voice status
Supports audio-based status sharing for more expressive updates.

### 40. Media status
Allows image and media-based story uploads.

### 41. Status privacy controls
Lets users define who can view their status using privacy modes and include/exclude selections.

### 42. Status viewers tracking
Shows who has seen a status update to improve audience insight.

### 43. Status upload worker
Uses background upload handling to improve reliability for larger or slower status uploads.

### 44. Collage status creation
Supports multi-image status composition instead of only single-image story posting.

### 45. Layout-based status editor
Provides reusable layout templates and an editing flow for more designed, creator-style status content.

## 5. Discovery, Sharing, and Contact Flows

### 46. Contact sync and registered-user detection
Reads device contacts, matches them with app users, and prioritizes reachable contacts in sharing and chat flows.

### 47. Share sheet integration
Appears in the Android system share sheet so users can send text, images, video, and documents into Glyph from other apps.

### 48. Multi-recipient share targeting
Supports selecting more than one recipient when sharing external content into the app.

### 49. Link preview generation
Extracts URLs from shared text and resolves preview metadata to create richer shared messages.

### 50. Deep linking into chat
Handles notification and intent-based entry directly into the correct conversation.

## 6. AI and Smart Assistance

### 51. Dedicated AI agent
Includes a standalone AI assistant experience inside the app.

### 52. AI message composer
Provides in-composer assistance to improve outbound messages without leaving the conversation flow.

### 53. Message enhancement
Can refine phrasing for clearer, more polished communication.

### 54. Grammar correction
Improves correctness and readability before sending a message.

### 55. Tone adjustment
Lets users reshape message tone, such as making text friendlier or more appropriate for the situation.

### 56. AI translation shortcut
Allows translation requests to be initiated as part of the same assisted-compose flow.

## 7. Translation and Language Support

### 57. In-chat translation
Supports translating individual messages directly from the chat interface.

### 58. Translation caching
Stores translation results locally to reduce repeated latency and improve reuse.

### 59. Translation audio playback
Can play translated speech audio, adding accessibility and pronunciation support.

### 60. Language picker
Lets users choose the target language used for translation requests.

## 8. Maps, Location, and Spatial Communication

### 61. Live location sharing
Supports real-time location updates between users with foreground-service reliability.

### 62. Map-based chat background
Adds map visualization directly into the chat experience rather than treating location as a separate screen only.

### 63. Interactive map overlay
Allows users to interact with map controls inside chat while maintaining quick conversation access.

### 64. Navigation and routing support
Includes route calculation, routing banners, and navigation notifications for movement-oriented conversations.

### 65. Map video session mode
Supports location-aware live video sessions connected to the map experience.

### 66. Avatar/video switching in map mode
Lets users transition between avatar presence and live video presence while staying in the map context.

### 67. In-map quick reply support
Allows rapid chat responses while interacting with the map overlay.

## 9. Chat Management and Organization

### 68. Chat list with dedicated tabs
Organizes the app into chats, status, calls, and settings using a bottom-navigation plus pager structure.

### 69. Archived chats
Lets users move less active conversations out of the main inbox while preserving access.

### 70. Locked chats
Supports a protected section for more private conversations.

### 71. Hidden locked chats
Allows locked chats to be concealed from normal visibility, adding another privacy layer beyond simple locking.

### 72. Secret code for locked chats
Uses a custom secret code mechanism to reveal or access hidden locked chats through search-based discovery.

### 73. Chat wallpaper selection
Supports per-chat or chat-oriented wallpaper customization with preview flows.

### 74. Chat settings
Provides chat-specific options, including appearance and behavior settings.

### 75. Manage chat storage
Includes dedicated storage management screens for conversation media and related content.

## 10. Profile, Personalization, and UI

### 76. User login and profile setup
Includes authentication, onboarding, and profile completion flows before app use.

### 77. Profile editing
Lets users update personal information and profile media.

### 78. Avatar preview
Provides a focused full-screen avatar viewing experience.

### 79. Theme selection
Supports multiple visual themes, including light, dark, and a pastel-style theme set.

### 80. Wallpaper preview and selection
Offers preview-first wallpaper browsing for better personalization decisions.

### 81. Animated and polished UI styling
Uses Lottie assets, custom drawables, dynamic components, and themed UI tokens to create a more premium feel than a basic utility chat app.

### 82. Emoji picker
Includes a dedicated emoji insertion panel inside chat.

### 83. Sticker-ready asset support
Contains bundled sticker-pack style assets that support richer expressive messaging.

## 11. Privacy, Security, and Controls

### 84. App lock
Protects the app with an authentication layer before entry after a timeout period.

### 85. Biometric unlock
Supports fingerprint or face unlock where the device allows strong biometric authentication.

### 86. PIN-based unlock
Provides a fallback app lock option using a user-defined PIN.

### 87. Auto-lock timeout
Lets users control how quickly the app requires re-authentication.

### 88. Blocked contacts
Supports contact blocking with unblock confirmation flows.

### 89. Privacy settings
Includes privacy-focused screens for account, visibility, and feature-specific access control.

### 90. Advanced chat privacy
Adds deeper privacy controls beyond the default chat settings layer.

### 91. Notification privacy behavior
Pairs app lock with hidden notification content for better privacy when the device is shared or visible.

## 12. Reliability, Background Behavior, and Performance

### 92. Push notifications
Uses Firebase Cloud Messaging for real-time delivery alerts and background communication updates.

### 93. Notification actions
Supports direct actions such as reply and mark-as-read from notifications.

### 94. Presence management
Tracks when users are online and handles connection-aware presence updates.

### 95. Battery optimization guidance
Prompts users to exempt the app from aggressive battery restrictions to improve message reliability.

### 96. Local caching and Room persistence
Uses local storage for chats, messages, translations, statuses, and previews to reduce reload cost.

### 97. WorkManager background jobs
Handles downloads, uploads, and cache maintenance more reliably outside the foreground UI.

### 98. Media preloading and render prefetching
Optimizes the chat experience for smoother opening and reduced visual delay.

### 99. Startup and performance tuning
Includes baseline profiling, startup tracing, resource shrinking, and performance-focused optimizations.

### 100. Network-aware communication stack
Uses connectivity monitoring, TURN/STUN-backed WebRTC configuration, and recovery logic for unstable network conditions.

## Uniqueness of Newer or Standout Features

### AI-assisted composing inside chat
The app does not limit AI to a separate assistant screen. It also embeds enhancement, grammar, tone, and translation help directly into the message composer, which makes the feature feel native to conversation flow instead of bolted on.

### Live audio sharing
This goes beyond standard voice messages or calls by enabling live microphone sharing with audience-level controls, making it useful for ambient communication and lightweight broadcasting scenarios.

### Walkie-talkie with scoped auto-accept
The walkie-talkie system is differentiated by configurable auto-accept behavior and trusted-user scoping, which makes it more operational and utility-focused than a simple push-to-talk novelty.

### Map-integrated communication
The app combines chat, live location, routing, quick reply, and map-linked video modes into one communication layer. That creates a spatial messaging experience rather than a separate map attachment feature.

### Hidden locked chats with secret code
Instead of only locking the app or a chat, Glyph adds concealed chat access through locked-chat hiding plus a user-defined secret code, creating a stronger privacy model for sensitive conversations.

### Creator-style status layout editor
The status system is more advanced than standard story upload because it supports collage templates, overlays, drawing, music-style labels, filters, and custom composition workflows.

### Expressive sentiment-driven typing visuals
The typing layer includes sentiment-aware and animated elements, making conversation feel more alive and emotionally responsive than conventional typing indicators.

### Group call architecture
The group call flow includes adaptive participant tiling, participant add flows, and direct in-session controls, giving it a more fully built communications experience than basic peer-to-peer call support.
