# BrightAuthenticator

TOTP two-factor authenticator for the **Light Phone III**. Shows up on the phone as
**Authenticator** (`com.gios.lightauth`).

## Install via BrightMarket

<p align="center">
  <img src="https://gi-os.github.io/brightmarket-index/assets/qr/BrightAuthenticator.png" alt="Scan to open BrightAuthenticator in BrightMarket" width="180" />
</p>

Scan the code above with **BrightMarket** installed to open BrightAuthenticator there and
install or update it directly. Don't have BrightMarket yet? Get it, and browse
every Bright app, at
**[brightmarket.gzl.dev](https://brightmarket.gzl.dev)**.

**Current version: v1.2.x.** See [Version history](#version-history).

Scan the QR code a site gives you for 2FA setup; the six-digit code is there when you
need it. Codes are computed on the phone from the stored secret — the app requests no
`INTERNET` permission at all, so nothing can leave it even in principle. Set a PIN and the
secrets are encrypted *behind it*, not merely hidden behind a screen — see
[The PIN](#the-pin).

> **If every site rejects every code, it is the phone's clock.** Open **CLOCK**, compare
> the UTC time it shows against [time.is](https://time.is), and turn the wheel until they
> match — one second per notch. See [The clock](#the-clock).

## Backups

Set up [BrightSync](https://github.com/gi-os/BrightSync) once and this app is included — daily, onto
BasilNet, encrypted on the phone before it leaves.

#### With a PIN set, the backup is sealed with the PIN

The PIN nearly broke backups, and the way it did is worth writing down. BrightSync exports in the
background, daily, unattended — and the vault is locked whenever the app is not in the
foreground, which is nearly always, and is the whole point. A locked app can read exactly zero
secrets, so a live background export could only ever produce an empty file. **Empty is the
dangerous outcome, not the harmless one:** it would overwrite a good backup with something that
still looks like a backup.

So exporting never decrypts live. While the app *is* unlocked it writes a snapshot to private
storage, and the provider streams that file — an export is then a file read, needing no key. If
the snapshot does not exist yet the provider throws instead of shipping nothing, so BrightSync
records a failed run and keeps yesterday's copy.

The snapshot is sealed with a key derived from the **PIN alone**, its salt travelling inside the
payload. Not the vault key: that one is wrapped by the AndroidKeyStore key, so anything sealed
with it restores into nothing on a new phone. Sealing with the PIN means a new phone needs only
the PIN, and it makes the backup strictly better than it was — it used to leave here as
plaintext URIs. Restore also lands while locked, so the payload is parked and imported at the
next unlock, when a PIN exists to open it with; a payload sealed under an older PIN is kept
rather than consumed, so it can be retried once that PIN is entered.

With no PIN set there is nothing to derive from, so the payload stays the plaintext URI list it
has always been and BrightSync's own encryption is what protects it.

What goes up is **`otpauth://` URIs, not the database**, and the difference matters. Every stored
secret is wrapped with an AES key generated inside AndroidKeyStore, which by design cannot leave
the device, so a copy of `totp_accounts.db` restored onto a new phone would be rows of ciphertext
with the key gone forever — a backup that looks like one and restores into nothing. Exporting the
standard URI form instead means the backup is portable, restorable into any authenticator, and
readable by hand in a pinch.

Restore feeds the same parser the QR scanner uses, and `addAccount` treats issuer plus label as
the identity — so restoring twice, or onto a phone that still has some of the accounts, converges
rather than duplicating.

## What this is and why

This is the [light-sdk `authenticator` example](https://github.com/lightphone/light-sdk/tree/main/examples/authenticator)
rebuilt as a plain sideloadable APK. Light's SDK tools cannot be installed on a LightOS
build in the wild today — community tools are meant to be built and signed by Light
from a public git commit, and the SDK's own README says as much — so this keeps the
example's logic (base32 decoder, RFC 6238 generator, `otpauth://` parser, AndroidKeyStore
cipher, Room schema) and replaces the `LightScreen` chrome with Compose/Material3 in the
same monochrome idiom as the rest of the [gi-os Light App collection](#the-gi-os-light-app-collection).
A plain APK installs over `adb` now and updates through Obtainium later.

One deliberate behavioural change over the SDK original: **removing an account asks
first.** `REMOVE` on a code opens a confirm screen naming the account, with `CANCEL` and
`REMOVE` given equal weight — cancel on the left, so the destructive button is never
where the previous screen's `REMOVE` just was. The SDK example already had a confirm
screen, but its bottom bar held only `CONFIRM` (cancelling meant the back arrow); this
makes both outcomes equally reachable. The confirmation exists because the delete is
genuinely unrecoverable — the secret is sealed behind a non-exportable AndroidKeyStore
key and exists in exactly one place, so getting the code back means re-enrolling with
the provider.

## Quick start

Grab the newest signed APK from [Releases](../../releases/latest) and sideload it:

```bash
adb install -r LightAuth-v1.2.x.apk
```

Or track `https://github.com/gi-os/BrightAuthenticator` in **Obtainium** for updates in place.

1. **ADD NEW** → point the camera at the QR code on the site's 2FA setup page.
2. The account lands in the list, issuer on top, account name underneath.
3. Tap it for the current code and the time left on it.
4. **REMOVE** → confirm.
5. **SETTINGS** → turn on the PIN, change it, or check the clock.

Re-scanning a QR for an account already in the list overwrites it rather than adding a
duplicate — which is what rotating a secret at the provider looks like from this end.

No setup, no permissions to grant, no server to configure. This is the fastest app in
the portfolio to get to a working state from a cold install.

## Configuration and usage

### Scanning

The scanner is [LightQR](https://github.com/gi-os/LightQR)'s, added in v1.0.2: a CameraX
preview with frames decoded in-process by ZXing core off the luminance plane
(`QrAnalyzer`), hints narrowed to `QR_CODE` only — a 2FA page never shows any other kind
of barcode, and every other format left enabled is work done on every frame for nothing.
Pure Java, no Google Play Services, which matters because LightOS ships without GMS and
ML Kit's barcode scanner cannot run at all. It replaced `zxing-android-embedded`, which
launched its own full-colour `CaptureActivity` with its own top bar, unrestyleable from
here; the scanner is now an ordinary screen in the app, in the same monochrome idiom as
everything else. Two things that port needed: `PreviewView.ImplementationMode.COMPATIBLE`
forced, because the window sets `FLAG_SECURE` and a `SurfaceView` preview inside a secure
window can render black; and popping back to home *before* enrolling, since a bad QR
surfaces as an error dialog that lives on the home screen, not the scanner.

`digits`, `period` and `algorithm` are all read off the `otpauth://` URI, so eight-digit
codes, 60-second windows, and SHA-256 or SHA-512 accounts work as well as the usual
six-digit SHA-1 ones. `otpauth://hotp/` counter-based accounts are rejected on the spot
rather than stored as something that will never produce a working code, and a
base32-undecodable secret is rejected at scan time for the same reason.

**Two parser fixes over the SDK original**, both in `OtpAuthUriParser.kt`:
`URI.getPath()` already un-escapes percent triplets, so the SDK's `URLDecoder.decode` on
top of it double-decoded a label — this decodes `uri.rawPath` exactly once. And
`URLDecoder` is a form decoder that turns a literal `+` into a space, so a label of
`user+2fa@example.com` came out with a space in it; a literal `+` is escaped to `%2B`
before decoding to stop that. Both were present in the upstream SDK example and are
fixed here.

### The PIN

**Settings → Require a PIN.** Four to eight digits, asked for every time the app comes to the
foreground. Off by default; turning it off again keeps every account.

The important part is what the PIN *is*. It would have been easy to make it a screen that
decides which composable to draw, and that would have been theatre — the secrets would still be
decryptable by anything running as the app, so getting at them would need a debuggable build or
a rooted phone, not the digits. Instead there is a **vault key**: 256 random bits that every
TOTP secret is encrypted under, which is never stored in the clear. It is stored wrapped twice:

1. by a key derived from the PIN with **PBKDF2-HMAC-SHA256, 210,000 iterations**, and
2. by the **non-exportable AndroidKeyStore key**.

Both are needed, and the order is the design. The KeyStore layer on the outside means a copied
`shared_prefs` file is inert — so four digits never face an offline attacker with a GPU, which
they would lose to in under a second. The PIN layer on the inside means that on the phone,
where the KeyStore key *can* be invoked, the digits are still missing. Ten thousand
combinations is a weak secret; ten thousand combinations that can only be tried on one specific
handset, one guess at a time, is not.

While locked the key is simply absent. `TotpAccountRepository.decryptSecret` has nothing to
decrypt with and returns null; adding an account refuses outright rather than writing a row
under a key it could not read back. The key lives in one field, is never written anywhere, and
is dropped in `onStop` — the screen sleeping, the app going to recents, or another app coming
forward. (`onStop` rather than `onPause`, because `onPause` also fires for the camera permission
dialog, and re-locking mid-enrolment would throw away the QR just read.)

Wrong PINs are slowed down, always: three free tries, then 5s, 30s, 60s, 5min, 15min. Trying all
10,000 four-digit PINs against that schedule takes over 60 days, which is asserted in a test
rather than estimated here. The deadline is on `SystemClock.elapsedRealtime`, not wall time,
because wall time is attacker-settable — and, as the clock screen exists to prove, not
always right anyway. A reboot clears the deadline but not the failure *count*, so the next wrong
PIN lands further down the schedule instead of starting over.

**Erase after failed attempts** is separate, off by default, and asks twice. It destroys the
vault key, which destroys every secret with it — there is no copy, so nothing can be walked
back. Only worth turning on if the BrightSync backup below is genuinely running. A throttled
attempt does not count toward it, so erasing takes a patient attacker rather than a fast one.

Changing the PIN asks for the old one first. That is the actual requirement, not a courtesy:
re-wrapping the vault key needs it unwrapped, which needs the old PIN.

**Forgetting the PIN loses every account.** There is no recovery code and no reset — either
would be a second door into the same vault. The backup is the recovery path.

### The clock

A TOTP code is a pure function of the clock and the secret. There is no challenge, no
round trip, nothing to negotiate — the phone and the server each hash the current
thirty-second window independently and the digits either match or they do not. So a phone
whose clock has drifted produces codes that are perfectly correct for a window that has
already passed, and every provider refuses all of them.

That failure is indistinguishable, from the code screen, from a wrong secret: six
plausible digits, a countdown that looks healthy, and a rejection at the other end. It is
also the more likely of the two, because a wrong secret is wrong for one account while a
wrong clock is wrong for all of them. **Codes failing on every account at once is a clock
symptom, not a secret symptom.**

**CLOCK** shows the UTC time this app is actually deriving codes from, next to the phone's
own uncorrected clock. UTC on purpose: it is what TOTP counts in, and it is what
[time.is](https://time.is) puts next to a countdown, so the two read side by side with no
timezone in the way. Turn the wheel to nudge the correction a second per notch, or use
`−10s` / `+10s` for a coarse move and `RESET` to drop back to the phone's own clock. Once
a correction is in force the code screen says so under the countdown, so a code computed
from something other than the phone's clock never looks like an ordinary one.

The correction is a signed second count in `SharedPreferences`, applied in `TimeSource`,
which is now the only place in the app that reads the wall clock. Everything is clamped to
±24h, and the arithmetic lives in `TimeMath` with no Android imports so the drift-and-
correct round trip is unit-tested against the RFC 6238 vectors.

Providers accept the neighbouring window as well as the current one, so drift under one
period still works — which is exactly why a slowly drifting clock fails intermittently
first and completely later.

**There is deliberately no automatic time sync.** Fetching real time means SNTP or HTTP,
which means the `INTERNET` permission, and an app that holds every one of your 2FA secrets
and can also open a socket is a worse trade than lining the clock up by hand once. The
durable fix is the phone's own clock: `adb shell settings put global auto_time 1` if
LightOS has dropped network time.

### The wheel

Turning the wheel scrolls the account list, and nudges the correction on the clock screen
a second per notch. It works because Light relabelled the wheel sensor's scancodes in
`/system/usr/keylayout/Generic.kl`; nothing in the system intercepts them, so they land
in the focused window as ordinary key events and `MainActivity` reads them in
`dispatchKeyEvent` before anything on screen can claim them. No companion service, no
permission, no root. Notches are paid off a fraction per frame rather than applied on
arrival, so a spin reads as one sweep instead of a stack of jumps, and the first notch
after a pause is held until a second confirms it — the wheel sits under a thumb, and a
stray brush shouldn't move the code you're reading. Only the turns are handled here; the
wheel click and camera button do nothing in BrightAuthenticator.

### Optional: BrightControl

[BrightControl](https://github.com/gi-os/BrightControl) is a separate, optional app that
gives the wheel click and camera button a job phone-wide: hold the wheel and turn for
brightness, tap it for the flashlight, the camera button for the camera, each rebindable
— tap and hold as two separate gestures — to any app on the phone. It deliberately passes
bare turns through to `com.gios.*` (BrightAuthenticator included), because per-notch scrolling
inside the app beats anything reachable from outside it, so installing it does not take
BrightAuthenticator's scrolling away.

```bash
adb install -r LightControl-v1.0.x.apk

# NOTE: this setting is a list, and this command REPLACES it — if you also run
# LightVoice's push-to-talk, colon-join both components instead.
adb shell settings put secure enabled_accessibility_services \
  com.gios.lightcontrol/com.gios.lightcontrol.keys.ControlService
adb shell settings put secure accessibility_enabled 1

adb shell appops set com.gios.lightcontrol WRITE_SETTINGS allow
adb shell appops set com.gios.lightcontrol SYSTEM_ALERT_WINDOW allow
```

Latest APK: https://github.com/gi-os/BrightControl/releases/latest

## Where the secrets live

- Room database, one row per account, holding an **AES-GCM blob** — never the base32
  secret.
- The wrapping key is generated inside **AndroidKeyStore** and cannot be exported, so
  pulling `totp_accounts.db` off the phone yields ciphertext and nothing else.
- Since v1.2, the AndroidKeyStore key wraps a **vault key** rather than each secret
  directly, and a PIN — when set — is a second, independent wrap around the same key. See
  [The PIN](#the-pin). Existing rows are re-wrapped once, automatically, on the first
  launch after upgrading.
- No biometric or lock-screen gate. LightOS has neither, which is why the PIN is the app's
  own and not a delegation to `BiometricPrompt`.
- `allowBackup` is off: a restored database on another device would hold blobs nothing
  can decrypt, which is worse than starting empty.
- The window is `FLAG_SECURE`, so codes stay out of screenshots and the recents
  thumbnail.
- The decrypted secret is read when a code screen opens and lives only in that
  composition — never held in a `StateFlow`, never handed to the account list.

## Building

```bash
./gradlew :app:assembleDebug
```

The TOTP maths, base32 decoder and URI parser are free of Android imports and covered by
unit tests CI runs *before* it will build an APK (61 tests, including the RFC 6238
appendix B vectors and the whole lock/unlock state machine) — a wrong code is invisible
in a screenshot, so it has to be caught here:

```bash
./gradlew :app:testDebugUnitTest
```

The launcher icon is generated, not hand-drawn — geometry lives in
`scripts/generate_icon.py`, emitted as both adaptive vector layers and raster fallbacks.
Edit it there and re-run `python3 scripts/generate_icon.py`; the script asserts the mark
stays inside the adaptive safe zone.

## Contributing

Solo repo, no PR workflow: commits go straight to `main`, and every push to `main`
triggers CI, which builds, signs, and publishes a GitHub Release. **A push is a release,
not a cosmetic action** — verify before pushing, not after.

The keystore is committed at `keystore/lightauth.jks` (alias/passwords all `lightauth`),
so every build carries the same signing certificate and upgrades install over the top.
CI pins that certificate's SHA-256 in `signing-fingerprint.txt` and fails the build if it
ever drifts, because a changed cert surfaces in Obtainium only as an opaque
`Failure: Invalid`. Exactly one APK is attached per release; the debug build stays a
workflow artifact only. `versionCode` is the workflow run number; `versionName` in the
committed `build.gradle.kts` (currently `1.2.0`) is only the `major.minor` base — CI
stamps the released `major.minor.RUN` (e.g. `1.0.4`) at build time and tags it `vX.Y.Z`.

## Version history

Real tags, oldest to newest:

| Version | What changed |
| --- | --- |
| v1.0.1 | Initial release — light-sdk authenticator example ported to a plain APK |
| v1.0.2 | In-app QR scanning via LightQR's CameraX + ZXing-core reader, replacing `zxing-android-embedded` |
| v1.0.3 | Hardware wheel scrolls the account list |
| v1.0.4 | README: documents the wheel and the optional BrightControl integration |
| v1.2.x | **PIN lock.** Optional 4–8 digit PIN that encrypts the vault rather than hiding a screen: a random vault key wrapped by PBKDF2(PIN) *and* by the non-exportable AndroidKeyStore key, absent from memory whenever the app is not in the foreground. Escalating lockout, opt-in erase-on-failure, PIN-sealed BrightSync snapshots so unattended backups still work while locked. Fixes a bug where backgrounding the app with no PIN set stranded every code until relaunch |
| v1.1.8 | **Clock screen.** Codes were being rejected everywhere because the phone's clock had drifted, with nothing on screen to say so. `TimeSource` is now the single clock the app reads, with a persisted signed correction; **CLOCK** shows the UTC time codes are derived from next to the phone's own, wheel-nudgeable a second per notch. Also fixes the QR analyzer, which described the padded camera Y plane as `width`-wide instead of `rowStride`-wide and sheared every row |

## Why this isn't a LightOS SDK tool

The SDK example is a fine piece of code, but SDK tools cannot be installed on a LightOS
build in the wild today. A plain APK installs over `adb` now and updates through
Obtainium later — the same reason [BrightPasses](https://github.com/gi-os/BrightPasses) and
[BrightTip](https://github.com/gi-os/BrightTip) are plain APKs. The SDK's own
`LightQrCodeScanner` has no equivalent outside the sandbox, so LightQR's scanner does
that job instead.

## Notes for the LPIII panel

- The screen is **greyscale on matte glass**, so the palette is luminance only and the
  code is set large and tracked out — six digits read as three pairs, the gaps are what
  stop a transcription slip.
- Surfaces are true black with no tonal elevation, so 1dp rules separate regions and the
  error dialog gets an explicit dark-grey fill — a scrim over black tints nothing.
- Text uses Akkurat when LightOS provides it, so the app matches the system UI.


## Licence

MIT.

<!-- bright-footer:begin -->
---

## Bright\*

*TOTP codes in a PIN-wrapped vault, on a phone with no authenticator app and no Play Services to run one.*

26 open-source apps for the **Light Phone III** — camera, music, maps, messages,
reading, transit, games. The phone has no app store, so they install by sideload: scan one
code from **[brightmarket.gzl.dev](https://brightmarket.gzl.dev)** and BrightMarket keeps them updated.

[Roll](https://github.com/gi-os/Roll) · [BrightMusic](https://github.com/gi-os/BrightMusic) · [BrightWay](https://github.com/gi-os/BrightWay) · [BrightChat](https://github.com/gi-os/BrightChat) · [BrightControl](https://github.com/gi-os/BrightControl) · [BrightRemote](https://github.com/gi-os/BrightRemote) · [browse all 26 →](https://brightmarket.gzl.dev)

The Light Phone does not sponsor or endorse any of these. Built by
[Giovanni Lupo](https://github.com/gi-os) — if this one is useful to you, a ⭐ helps the next
person find it.
<!-- bright-footer:end -->
