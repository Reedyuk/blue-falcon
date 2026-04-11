# Publishing Blue Falcon 3.0

This guide explains how to publish Blue Falcon 3.0 modules to Maven Central.

## Prerequisites

### 1. Sonatype Account

You need a Sonatype account with access to the `dev.bluefalcon` group:

1. Create account at https://central.sonatype.com/
2. Request access to `dev.bluefalcon` namespace
3. Wait for approval (usually 1-2 days)

### 2. GPG Key

Generate a GPG key for signing artifacts:

```bash
# Generate key
gpg --gen-key

# List keys to get the key ID
gpg --list-secret-keys --keyid-format=short

# Export the private key (for GitHub secrets)
gpg --armor --export-secret-keys YOUR_KEY_ID

# Export the public key and upload to keyserver
gpg --armor --export YOUR_KEY_ID | pbcopy
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
```

### 3. Configure Secrets

#### GitHub Secrets (for CI/CD)

Add these secrets to your GitHub repository (Settings → Secrets and variables → Actions):

- `SONATYPEUSERNAME`: Your Sonatype username
- `SONATYPEPASSWORD`: Your Sonatype password
- `GPG_KEY`: Your GPG private key (full ASCII-armored output from export command)
- `GPG_KEY_PASS`: Your GPG key passphrase

#### Local Properties (for local publishing)

Create/update `library/local.properties`:

```properties
# Maven Central credentials
mavenCentralUsername=your_username
mavenCentralPassword=your_password

# GPG signing
signingInMemoryKey=-----BEGIN PGP PRIVATE KEY BLOCK-----\n...\n-----END PGP PRIVATE KEY BLOCK-----
signingInMemoryKeyPassword=your_gpg_passphrase
```

**⚠️ Never commit local.properties to version control!**

## Publishing Process

### Automated (via GitHub Release)

The easiest way to publish is through GitHub releases:

1. **Create a git tag:**
   ```bash
   git tag v3.0.0-alpha01
   git push origin v3.0.0-alpha01
   ```

2. **Create GitHub Release:**
   - Go to GitHub → Releases → "Draft a new release"
   - Select your tag
   - Title: "Blue Falcon 3.0.0-alpha01"
   - Description: Copy from `docs/RELEASE_NOTES_3.0.0.md`
   - Click "Publish release"

3. **GitHub Actions will:**
   - Build all modules
   - Run tests
   - Sign artifacts
   - Publish to Maven Central
   - Automatically release (no manual promotion needed)

### Manual (local)

For testing or manual releases:

```bash
cd library

# Publish to local Maven repository (for testing)
./gradlew publishToMavenLocal

# Publish to Maven Central
./gradlew publishAllPublicationsToMavenCentralRepository

# Or publish specific modules
./gradlew :core:publishAllPublicationsToMavenCentralRepository
./gradlew :engines:android:publishAllPublicationsToMavenCentralRepository
./gradlew :legacy:publishAllPublicationsToMavenCentralRepository
```

## Published Artifacts

Blue Falcon 3.0 publishes **11 modules**:

### Core
- `dev.bluefalcon:blue-falcon-core:3.0.0-alpha01`
  - Platform-independent core interfaces and plugin system

### Engines
- `dev.bluefalcon:blue-falcon-engine-android:3.0.0-alpha01`
- `dev.bluefalcon:blue-falcon-engine-ios:3.0.0-alpha01`
- `dev.bluefalcon:blue-falcon-engine-macos:3.0.0-alpha01`
- `dev.bluefalcon:blue-falcon-engine-js:3.0.0-alpha01`
- `dev.bluefalcon:blue-falcon-engine-windows:3.0.0-alpha01`
- `dev.bluefalcon:blue-falcon-engine-rpi:3.0.0-alpha01`

### Plugins
- `dev.bluefalcon:blue-falcon-plugin-logging:3.0.0-alpha01`
- `dev.bluefalcon:blue-falcon-plugin-retry:3.0.0-alpha01`
- `dev.bluefalcon:blue-falcon-plugin-caching:3.0.0-alpha01`

### Legacy Compatibility
- `dev.bluefalcon:blue-falcon:3.0.0-alpha01`
  - Drop-in replacement for 2.x users
  - Includes all engines and backward compatibility layer

## Version Management

Versions are centralized in `library/gradle.properties`:

```properties
version=3.0.0-alpha01
versionCore=3.0.0-alpha01
versionEngines=3.0.0-alpha01
versionPlugins=3.0.0-alpha01
versionLegacy=3.0.0-alpha01
```

**Version progression:**
- `3.0.0-alpha01` → Testing with early adopters
- `3.0.0-beta01` → Feature complete, bug fixes only
- `3.0.0-rc01` → Release candidate, production ready
- `3.0.0` → Final release

## Verification

After publishing, verify artifacts are available:

### Check Maven Central

Search: https://central.sonatype.com/search?q=dev.bluefalcon

Or use direct links:
- Core: https://central.sonatype.com/artifact/dev.bluefalcon/blue-falcon-core
- Legacy: https://central.sonatype.com/artifact/dev.bluefalcon/blue-falcon

### Test in Project

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.bluefalcon:blue-falcon:3.0.0-alpha01")
    // Should download successfully
}
```

### Verify Signatures

```bash
# Download artifact
curl -O https://repo1.maven.org/maven2/dev/bluefalcon/blue-falcon-core/3.0.0-alpha01/blue-falcon-core-3.0.0-alpha01.jar
curl -O https://repo1.maven.org/maven2/dev/bluefalcon/blue-falcon-core/3.0.0-alpha01/blue-falcon-core-3.0.0-alpha01.jar.asc

# Verify signature
gpg --verify blue-falcon-core-3.0.0-alpha01.jar.asc blue-falcon-core-3.0.0-alpha01.jar
```

## Troubleshooting

### "Signing key not found"

**Problem:** Build fails with signing errors

**Solution:** Ensure secrets are set correctly:
```bash
# Check environment variables
echo $ORG_GRADLE_PROJECT_signingInMemoryKey
echo $ORG_GRADLE_PROJECT_signingInMemoryKeyPassword

# For local builds, check local.properties exists
cat library/local.properties
```

### "Unauthorized" error

**Problem:** 401 Unauthorized when publishing

**Solution:** Check Maven Central credentials:
- Verify username/password in secrets
- Ensure you have access to `dev.bluefalcon` namespace
- Try logging in to https://central.sonatype.com/

### "Artifact already exists"

**Problem:** Cannot republish same version

**Solution:** Maven Central doesn't allow overwriting. You must:
- Increment version number
- Delete and recreate git tag if needed

### Artifact not appearing

**Problem:** Published but not showing on Maven Central

**Solution:** 
- **With automaticRelease = true**: Should appear within 15-30 minutes
- Check https://central.sonatype.com/publishing for status
- Verify the release wasn't dropped due to validation errors

## Release Checklist

Before publishing a release:

- [ ] All tests pass locally
- [ ] Version numbers updated in `gradle.properties`
- [ ] `CHANGELOG.md` updated
- [ ] Release notes prepared
- [ ] Documentation reviewed
- [ ] Examples tested
- [ ] Git tag created
- [ ] GPG key uploaded to keyserver
- [ ] Secrets configured in GitHub
- [ ] Branch is clean (no uncommitted changes)

After publishing:

- [ ] Verify artifacts on Maven Central
- [ ] Test dependency download in sample project
- [ ] Create GitHub release with notes
- [ ] Announce on social media
- [ ] Update documentation website
- [ ] Monitor for issues

## Support

If you encounter issues:

1. Check [Sonatype Documentation](https://central.sonatype.org/publish/publish-guide/)
2. Review [GitHub Actions logs](.github/workflows/release.yml)
3. Open issue at https://github.com/Reedyuk/blue-falcon/issues

## References

- **Maven Publish Plugin:** https://github.com/vanniktech/gradle-maven-publish-plugin
- **Sonatype Central:** https://central.sonatype.com/
- **GPG Signing:** https://central.sonatype.org/publish/requirements/gpg/
- **GitHub Actions:** https://docs.github.com/en/actions
