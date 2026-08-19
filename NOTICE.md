# NOTICE — Third-party native binaries

This project redistributes the aria2c executable (renamed `libaria2c.so`) in
`app/src/main/jniLibs/<abi>/` for all four ABIs. The binaries are built from
the devgianlu/aria2-android cross-compile setup:

- Source: https://github.com/devgianlu/aria2-android (NDK build scripts)
- aria2 upstream: https://github.com/aria2/aria2 (pinned commit
  02f2d0d8472b3c38c29b4dba8c75ebd5fdd2899a, aria2 1.37 era)
- Bundled static libraries: OpenSSL, libssh2, c-ares, expat, zlib

aria2 is licensed under the GNU General Public License version 2 or later
(GPL-2.0-or-later). Per the GPL, the corresponding source code is available:

- aria2 source: https://github.com/aria2/aria2
- Build configuration: https://github.com/devgianlu/aria2-android
- Prebuilt reference binaries: https://github.com/devgianlu/aria2lib

The static linking is deliberate: the binary has no runtime dependencies
beyond Android's bionic libc/libdl/libm, which is what makes it linkable and
executable on Android (see the earlier failure of the junkfood02 aria2c AAR,
whose Termux-style dynamic libraries could not be satisfied by Android).

Full license text: https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
