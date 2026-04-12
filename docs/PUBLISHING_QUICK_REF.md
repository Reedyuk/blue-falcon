# Blue Falcon 3.0 - Quick Publishing Reference

## 🚀 How to Publish a Release

### Option 1: Automated via GitHub (Recommended)

```bash
# 1. Create and push a git tag
git tag v3.0.0
git push origin v3.0.0

# 2. Go to GitHub and create a release from the tag
# GitHub Actions will automatically:
#   - Build all modules
#   - Sign with GPG
#   - Publish to Maven Central
#   - Auto-release (no manual promotion)
```

### Option 2: Manual from Command Line

```bash
cd library

# Publish all modules to Maven Central
./gradlew publishAllPublicationsToMavenCentralRepository

# Or publish specific modules
./gradlew :core:publishAllPublicationsToMavenCentralRepository
./gradlew :legacy:publishAllPublicationsToMavenCentralRepository
```

### Option 3: Test Locally First

```bash
cd library

# Publish to local Maven repository (~/.m2/repository)
./gradlew publishToMavenLocal

# Then test in a sample project
dependencies {
    implementation("dev.bluefalcon:blue-falcon:3.0.0")
}
```

## 📋 Required GitHub Secrets

Configure in: **Settings → Secrets and variables → Actions**

| Secret Name | Description | How to Get |
|------------|-------------|------------|
| `SONATYPEUSERNAME` | Maven Central username | Create account at central.sonatype.com |
| `SONATYPEPASSWORD` | Maven Central password | Your account password |
| `GPG_KEY` | GPG private key (ASCII armored) | `gpg --armor --export-secret-keys YOUR_KEY_ID` |
| `GPG_KEY_PASS` | GPG key passphrase | The passphrase you set when creating the key |

## 🔑 GPG Key Setup (One-Time)

```bash
# 1. Generate GPG key
gpg --gen-key
# Follow prompts: Use your name and email

# 2. List keys to get the key ID
gpg --list-secret-keys --keyid-format=short
# Look for something like: sec   rsa3072/ABCD1234 2024-01-01

# 3. Export private key for GitHub secrets
gpg --armor --export-secret-keys ABCD1234
# Copy the entire output (including BEGIN/END lines)

# 4. Export and upload public key to keyserver
gpg --keyserver keyserver.ubuntu.com --send-keys ABCD1234
```

## 📦 What Gets Published

| Artifact ID | Description |
|------------|-------------|
| `blue-falcon-core` | Core interfaces and plugin system |
| `blue-falcon-engine-android` | Android BLE engine |
| `blue-falcon-engine-ios` | iOS CoreBluetooth engine |
| `blue-falcon-engine-macos` | macOS CoreBluetooth engine |
| `blue-falcon-engine-js` | JavaScript Web Bluetooth engine |
| `blue-falcon-engine-windows` | Windows WinRT engine |
| `blue-falcon-engine-rpi` | Raspberry Pi Blessed engine |
| `blue-falcon-plugin-logging` | Logging plugin |
| `blue-falcon-plugin-retry` | Retry with backoff plugin |
| `blue-falcon-plugin-caching` | Service/characteristic caching plugin |
| `blue-falcon` | **Legacy compatibility module (main artifact)** |

## 🧪 Testing Before Release

```bash
cd library

# 1. Full build
./gradlew build --parallel

# 2. Test publishing locally
./gradlew publishToMavenLocal

# 3. Verify POM files
find ~/.m2/repository/dev/bluefalcon -name "*.pom" -exec cat {} \;

# 4. Check signing (requires GPG configured)
./gradlew signMavenPublication
```

## 🐛 Troubleshooting

### "Unauthorized" error
- Check SONATYPEUSERNAME and SONATYPEPASSWORD are correct
- Verify you have access to `dev.bluefalcon` namespace on Maven Central

### "Signing key not found"
- Ensure GPG_KEY and GPG_KEY_PASS secrets are set
- Verify GPG key is exported correctly (with `--armor` flag)

### "Artifact already exists"
- Maven Central doesn't allow overwriting
- Increment version number and try again

### Build fails in GitHub Actions
- Check Actions logs for detailed error messages
- Verify all secrets are configured correctly
- Test locally first with `./gradlew publishToMavenLocal`

## 📚 Full Documentation

- **Complete Guide:** [docs/PUBLISHING.md](docs/PUBLISHING.md)
- **Setup Summary:** [PUBLISHING_SETUP_COMPLETE.md](PUBLISHING_SETUP_COMPLETE.md)
- **Release Notes:** [docs/RELEASE_NOTES_3.0.0.md](docs/RELEASE_NOTES_3.0.0.md)

## 🔄 Version Progression

```
3.0.0  → Testing with early adopters
3.0.0-beta01   → Feature complete, bug fixes only
3.0.0-rc01     → Release candidate, production ready
3.0.0          → Final stable release
```

## 📝 Pre-Release Checklist

- [ ] All tests pass locally
- [ ] Version numbers updated in `library/gradle.properties`
- [ ] `CHANGELOG.md` updated
- [ ] Release notes finalized
- [ ] Documentation reviewed
- [ ] Examples tested
- [ ] Git tag created
- [ ] GitHub secrets configured
- [ ] Branch is clean (no uncommitted changes)

---

**Quick Start:** Just create a GitHub release and let the automation handle it! 🎉
