# Changelog

All notable changes to Geode, newest first.

## 0.1.19

- Detail title width matched to the card (40dp insets on both sides) — no more mid-morph word snap.

## 0.1.18

- R8 minify + resource shrinking on: APK 13.2 MB → 1.7 MB, installed size ~119 MB → ~7 MB.

## 0.1.17

- API-key step 2 wording fix — it now reads "If it asks you to sign in…" (the deep link opens the API Keys page directly, so there is no "Get started" button to tap).

## 0.1.16

- Skipping the key setup now shows a caution popup first.
- The Test key button reads "Testing key…" with a loader while testing, and "Test key again" after a key is verified.
- Key steps rewritten for a logged-out beginner (Get started, account chooser, Default Gemini API Key).

## 0.1.15

- Grow animation is cancellable mid-flight with the back gesture or button.
- Long-press a card to select it — Share, Copy and Delete for one card, Share and Delete for several (WhatsApp-style).
- Deleting asks for confirmation first. No trash bin — a deleted summary is re-made by re-sharing the reel.

## 0.1.14

- Failed shares save nothing to the Library — notification only, no error cards cluttering the home screen.

## 0.1.13

- G logo as the notification icon.
- Hidden-WebView Instagram fetch — public reels now summarise without any login.
- Private reels get their own short notice: "This reel is private / Geode couldn't see it".

## 0.1.12

- Title travels up with the tags during the grow animation — no more fade-out and reappear.

## 0.1.11

- Grow fix plus all four queued bugs: library refresh, takeaway number alignment, title morph, tap-elsewhere-unfocus helper.

## 0.1.10

- The G logo everywhere — launcher, themed monochrome layer, and in-app.

## 0.1.9

- Crystal logo (superseded by the G in 0.1.10).

## 0.1.8

- Tap elsewhere to unfocus any text field; the key field scrolls into view when the keyboard opens.

## 0.1.7

- Keyboard auto-hides and the key auto-tests itself once a complete key is pasted.

## 0.1.6

- Motion polish: critically-damped easing, corner morph, rapid-tap guard. New signing key (the old debug keystore was lost).

## 0.1.5

- ScaleToBounds grow with a shell-only morph; first release builds.

## 0.1.4

- Collapsed library cards (heading, tags, date only); grow animation; scroll performance fixes.

## 0.1.3

- Gemini 3.5 Flash-Lite with 3.8 Flash fallback; real error messages from Google; keyboard fix.

## 0.1.2

- Contrast fixes — every text pairing is at least 4.5:1 in both themes.

## 0.1.1

- Renamed ReelNotes → Geode; animations; edge-to-edge insets.

## 0.1.0

- First build: BYOK onboarding, library, share receiver.
