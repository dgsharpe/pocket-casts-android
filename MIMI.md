# Pocket Casts + Mimi

This fork's `mimi` branch adds, on top of Automattic's Pocket Casts:

- playback of `.mimi` files (Kyutai's Mimi neural codec, about 2 MB per hour), decoded on the phone's NPU / GPU;
- downloads and streams converted on demand by a **mimi-hub** server, Up Next converted ahead of time;
- the server's transcripts with speaker labels, synced to playback;
- ad and self-promotion detection: auto-skip, highlights on the seek bar, an optional trimmed timeline, and folded
  ad breaks in the transcript;
- per-podcast choices for compression and for transcripts + ad marks (Settings > Advanced > Mimi server, and each
  podcast's settings).
- a server that scales to zero (mimi-hub on Modal) is woken when the app comes to the foreground (at most every
  15 minutes); a server with a token takes it in the URL, `https://<server>/t/<token>`.

The server, the decoder (`android/mimi`, which this build includes from `../android/mimi`) and the setup and build
instructions are in [joshjob42/Podcast-compress-transcribe](https://github.com/joshjob42/Podcast-compress-transcribe):
clone that, clone this branch inside it as `pocket-casts-android/`, run `android/fetch_deps.sh`, then
`./gradlew :app:assembleDebugProd`. Pocket Casts itself is MPL-2.0 (see `LICENSE.md`).
