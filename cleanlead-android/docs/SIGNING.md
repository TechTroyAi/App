# CleanLead Signing

## Certificate details

| Field | Value |
|---|---|
| Common Name | `CN=CleanLead` |
| Org Unit | `Cleaning Tracker` |
| Organization | `TechTroyAi` |
| Location | `Cagayan de Oro, Northern Mindanao, PH` |
| Algorithm | RSA 4096-bit |
| Signature | SHA256withRSA |
| Keystore type | PKCS12 |
| Alias | `cleanlead` |
| Validity | 30 years (2026-09-24 → 2056-09-16) |
| Certificate SHA-256 | `56:8A:96:93:64:14:02:54:F4:3E:ED:80:5D:1A:CB:B0:CB:51:75:1B:E0:27:8A:06:A1:CB:32:F2:FE:E6:B3:A3` |

## Files

| File | Purpose | Committed? |
|---|---|---|
| `.signing/cleanlead-release.p12` | Private keystore | **No** (gitignored) |
| `.signing/release.properties` | Keystore passwords | **No** (gitignored) |
| `.signing/cleanlead-cert.pem` | Public certificate | Safe to commit |

## Backing up

1. Copy `.signing/cleanlead-release.p12` and `.signing/release.properties` to an encrypted backup.
2. Do NOT add them to Git, email, or chat.
3. Optionally store as GitHub repository secrets for CI builds.

## Regenerating

If the key is lost, a new one can be generated but the app must be uninstalled first on all devices (Android rejects updates signed by a different key).

```bash
# Generate new key
keytool -genkeypair \
  -alias cleanlead \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -validity 10950 \
  -dname "CN=CleanLead, OU=Cleaning Tracker, O=TechTroyAi, L=Cagayan de Oro, ST=Northern Mindanao, C=PH" \
  -keystore .signing/cleanlead-release.p12 \
  -storetype PKCS12 \
  -storepass YOUR_PASSWORD \
  -keypass YOUR_PASSWORD
```
