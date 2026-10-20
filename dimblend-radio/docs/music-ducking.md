# Radio background-music control

When a radio is actually audible, background music fades down over 20 unpaused
client ticks. Vanilla music retains its existing stop/scheduling behavior. Music
owned by another mod receives the same envelope without replacing its manager.
When the radio becomes inaudible, the envelope returns to full gain over 20 ticks.
Music that ended or was skipped while suppressed resumes according to its owner's
normal scheduling; this feature does not force a new track.

## Aether compatibility (2026-10-20)

Aether 1.21.1-1.5.10 has an independent `AetherMusicManager.currentMusic` and plays
tickable `MusicSoundInstance` objects in the `MUSIC` category. The old radio mixin
only adjusted vanilla `MusicManager.currentMusic`, leaving these tracks untouched.

The sound engine now multiplies the radio envelope after each sound's current
native volume and the player's category slider. This covers initial playback,
per-tick Aether fades, and category-volume updates. Non-tickable music channels
are refreshed during the envelope as well. The player's volume settings are
unchanged, and `RECORDS`, ambient sounds and other categories are unaffected.

Aether achievement jingles also use `MUSIC`. The optional nested tag
`#aether:achievement_sounds` is included in
`#dimblend_radio:music_ducking_exempt` so those notifications remain audible.
Other notification events may be added to that exemption tag by a datapack.
The implementation has no hard dependency on Aether.

## Verification

- `:dimblend-radio:test :dimblend-radio:build`: 272 tests passed; 69 optional
  native-audio tests skipped without the opt-in audio-native configuration.
- An actual-client probe exercised the applied sound-engine mixins with Aether's
  installed biome and boss sound classes. Half/full suppression, a native Aether
  volume change, a new track at full suppression, and recovery passed.
- The same probe verified ambient/records volume preservation and live tag-based
  achievement exemption. The probe supplied the radio-envelope input directly;
  listening to a real radio in an Aether biome remains the final player check.
- The temporary probe is stored under `build/` and is not part of the mod jar.

To check manually, play a radio in a creative Aether biome while Aether music is
already playing, then stop the radio or leave its audible range. Repeat with
music starting after the radio, and check that an Aether achievement jingle still
plays. Radio muting, missing tracks and completed playback retain the existing
audibility rules.
