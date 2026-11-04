# libmobi regression corpus

These unmodified files are from `bfabiszewski/libmobi` tag `v0.12`, directory
`tests/samples/`. The uncompressed sample identifies itself as “Libmobi test
sample”, author Bartek Fabiszewski. Source and notices accompany the vendored
library in `third_party/libmobi/`; see its `COPYING` and `UPSTREAM.md`.

- sample-unicode-uncompressed.mobi: uncompressed Unicode resources and pictures.
- sample-unicode-huffdic.mobi: HUFF/CDIC decoding.
- sample-ncx.mobi: navigation reconstruction.
- sample-drm-v1.mobi: rejection test only. Encryption support remains disabled.

The application APK does not bundle this test corpus. The fixtures are in the
instrumentation test APK only.
