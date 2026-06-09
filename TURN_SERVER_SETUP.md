# TURN Server Setup for Cross-Network Video Calls

## Problem Solved
This fix enables video calls to work seamlessly across different mobile networks (4G/5G/WiFi). Without TURN servers, calls fail when both users are behind restrictive NAT or firewalls (common on mobile networks).

## Current Configuration
The app now supports two TURN configuration modes:

1. Static TURN URLs and credentials from `local.properties`
2. A runtime TURN credential endpoint via `TURN_CREDENTIALS_URL`

The runtime endpoint is the preferred option for providers like Metered that return an `iceServers` JSON payload per account or API key.

## For Immediate Testing
Add one of the following before testing cross-network video calls:

```properties
# Option A: Runtime credential endpoint (recommended for Metered and similar providers)
TURN_CREDENTIALS_URL=https://yourapp.metered.live/api/v1/turn/credentials?apiKey=YOUR_API_KEY

# Optional header for providers that require Authorization instead of a query parameter
TURN_CREDENTIALS_AUTH_HEADER=Bearer YOUR_TOKEN
```

or:

```properties
# Option B: Static TURN credentials
TURN_SERVER_URL1=turn:your-turn-server.com:3478?transport=udp
TURN_SERVER_USERNAME1=your-username
TURN_SERVER_PASSWORD1=your-password

TURN_SERVER_URL2=turn:your-turn-server.com:3478?transport=tcp
TURN_SERVER_USERNAME2=your-username
TURN_SERVER_PASSWORD2=your-password

TURN_SERVER_URL3=turns:your-turn-server.com:5349
TURN_SERVER_USERNAME3=your-username
TURN_SERVER_PASSWORD3=your-password
```

Then test cross-network calls:
1. Connect one device to WiFi, another to mobile data
2. Make a video call between them
3. Verify video/audio streams work properly

## For Production Deployment

### Option 1: Self-Hosted TURN Server (Recommended)
Set up your own TURN server using **coturn** (open-source, reliable):

1. **Deploy coturn on a server** (DigitalOcean, AWS, Azure, etc.):
   ```bash
   # Ubuntu/Debian
   sudo apt-get update
   sudo apt-get install coturn
   ```

2. **Configure `/etc/turnserver.conf`**:
   ```conf
   listening-port=3478
   tls-listening-port=5349
   listening-ip=YOUR_SERVER_IP
   external-ip=YOUR_SERVER_IP
   relay-ip=YOUR_SERVER_IP
   
   # Authentication
   use-auth-secret
   static-auth-secret=YOUR_RANDOM_SECRET_KEY_HERE
   realm=yourdomain.com
   
   # Security
   no-stdout-log
   log-file=/var/log/turnserver.log
   simple-log
   
   # Performance
   max-bps=1000000
   bps-capacity=0
   stale-nonce=600
   
   # TLS (optional but recommended)
   cert=/etc/letsencrypt/live/yourdomain.com/fullchain.pem
   pkey=/etc/letsencrypt/live/yourdomain.com/privkey.pem
   ```

3. **Start coturn**:
   ```bash
   sudo systemctl enable coturn
   sudo systemctl start coturn
   ```

4. **Open firewall ports**:
   - UDP/TCP 3478 (TURN)
   - UDP/TCP 5349 (TURN over TLS)
   - UDP 49152-65535 (relay ports)

5. **Update `local.properties`**:
   ```properties
   TURN_SERVER_URL1=turn:your-server.com:3478
   TURN_SERVER_USERNAME1=username
   TURN_SERVER_PASSWORD1=password
   
   TURN_SERVER_URL2=turn:your-server.com:5349
   TURN_SERVER_USERNAME2=username
   TURN_SERVER_PASSWORD2=password
   
   TURN_SERVER_URL3=turn:your-server.com:3478?transport=tcp
   TURN_SERVER_USERNAME3=username
   TURN_SERVER_PASSWORD3=password
   ```

### Option 2: Managed TURN Service (Easier)
Use a managed service for hassle-free operation:

#### **Twilio STUN/TURN (Most Popular)**
- Sign up at https://www.twilio.com/stun-turn
- Get credentials from Console → Video → TURN Credentials
- Free tier: 60 minutes/month, $0.0004/min after
- Update `local.properties`:
  ```properties
  TURN_SERVER_URL1=turn:global.turn.twilio.com:3478?transport=udp
  TURN_SERVER_USERNAME1=<from-twilio-console>
  TURN_SERVER_PASSWORD1=<from-twilio-console>
  ```

#### **Xirsys (Simple Pricing)**
- Sign up at https://xirsys.com
- Free tier: 50GB transfer/month
- Get credentials from Dashboard → TURN Servers
- Update `local.properties` with provided URLs/credentials

#### **Metered TURN Servers (Pay-as-you-go)**
- Sign up at https://www.metered.ca/tools/openrelay/
- Free tier: 50GB transfer/month
- Use the provider endpoint in `TURN_CREDENTIALS_URL` so the app can fetch the returned `iceServers` array at runtime
- More reliable than public shared relay credentials

### Option 3: Google's Free STUN (Limited)
Google provides free STUN servers but **NO TURN**:
```
stun:stun.l.google.com:19302
stun:stun1.l.google.com:19302
```
⚠️ STUN alone only works for ~85% of connections. You still need TURN for cross-network calls.

## Configuration File Structure

The TURN servers are configured in `app/build.gradle.kts` and read from:
1. **`local.properties`** (recommended for production)
2. **Gradle properties** (from command line)
3. **Runtime TURN credential endpoint** via `TURN_CREDENTIALS_URL`

### Priority Order:
1. If `TURN_CREDENTIALS_URL` is present, fetch dynamic `iceServers` at runtime and cache them
2. Otherwise use static `TURN_SERVER_URLn` / `TURN_SERVER_USERNAMEn` / `TURN_SERVER_PASSWORDn` values
3. If neither is configured, only STUN is available and restrictive cross-network connections may fail

## Testing Cross-Network Calls

After deployment, test these scenarios:
1. ✅ WiFi → WiFi (same network)
2. ✅ WiFi → Mobile Data (different networks)
3. ✅ Mobile Data → Mobile Data (different carriers)
4. ✅ 4G → 5G (different network types)
5. ✅ Behind corporate firewall → Public network

All should work reliably with proper TURN configuration.

## Monitoring & Troubleshooting

### Check if TURN is being used:
Enable WebRTC logging in `WebRtcCallClient.kt`:
```kotlin
override fun onIceCandidate(candidate: IceCandidate) {
    Log.d(TAG, "ICE candidate: ${candidate.sdpMid} type=${candidate.sdp}")
    // Look for "relay" in the candidate type - indicates TURN is used
    _onIceCandidate.tryEmit(candidate)
}
```

### Common Issues:
- **Calls fail on different networks**: TURN servers not accessible or configured wrong
- **High latency**: TURN server too far from users (use geographically closer servers)
- **Connection timeout**: Firewall blocking TURN ports (check UDP 3478, TCP 443)

### Bandwidth Estimation:
- Audio call: ~50 KB/s per user (~3 MB/min)
- Video call (SD): ~200-500 KB/s per user (~12-30 MB/min)
- Video call (HD): ~1-2 MB/s per user (~60-120 MB/min)

## Cost Estimation (Production)

### Self-Hosted (coturn on VPS):
- VPS cost: $5-20/month (DigitalOcean, Linode, AWS Lightsail)
- Bandwidth: Usually 1-5TB/month included
- Estimated: ~1000-5000 call-minutes/month before overage

### Managed Services:
- Twilio: $0.0004/minute (~$24 for 60,000 minutes)
- Xirsys: $0.50/GB (~$25 for 50GB)  
- Metered: $0.50/GB (~$25 for 50GB)

## Security Notes

1. **Never commit TURN credentials to version control**
   - Use `local.properties` (already in `.gitignore`)
   - Or use environment variables in CI/CD

2. **Rotate credentials regularly**
   - Change TURN passwords every 3-6 months
   - Use time-limited credentials when possible

3. **Monitor usage**
   - Track TURN server bandwidth
   - Set up alerts for unusual traffic

4. **Use TLS for TURN**
   - Prefer `turns://` (TLS) over `turn://` for port 5349
   - Encrypts relay traffic end-to-end

## Further Reading
- [WebRTC NAT Traversal](https://webrtc.org/getting-started/turn-server)
- [coturn GitHub](https://github.com/coturn/coturn)
- [Twilio STUN/TURN Docs](https://www.twilio.com/docs/stun-turn)
