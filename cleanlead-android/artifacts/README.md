# CleanLead Artifacts

## CleanLead-v1.0.0-installable.apk

| Field | Value |
|---|---|
| File | `CleanLead-v1.0.0-installable.apk` |
| Size | 829,164 bytes |
| SHA-256 | `53be15642e46061de1120ef4cf04fea18f705563a5f293b2b913683310a2990b` |
| Signing | v2 + v3 (RSA 4096-bit PKCS12) |
| Certificate SHA-256 | `568a969364140254f43eed805d1acbb0cb51751be0278a06a1cb32f2fee6b3a3` |
| Certificate CN | `CN=CleanLead, OU=Cleaning Tracker, O=TechTroyAi, L=Cagayan de Oro, ST=Northern Mindanao, C=PH` |
| Valid until | 2056-09-16 |
| Built | 2026-09-24 |
| Build method | Offline (kotlinc + dx + apktool + apksigner) |

## Verification

```bash
# Check signature
java -jar apksigner.jar verify --verbose --print-certs CleanLead-v1.0.0-installable.apk

# Check SHA-256
sha256sum CleanLead-v1.0.0-installable.apk

# Inspect contents
unzip -l CleanLead-v1.0.0-installable.apk
```
