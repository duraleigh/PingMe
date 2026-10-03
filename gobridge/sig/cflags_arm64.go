// SPDX-License-Identifier: AGPL-3.0-or-later

//go:build android && arm64

package sig

// Signal's native library for this chip, downloaded by build.sh (see the libsignal workflow). The
// C++ runtime it needs is linked in, since the app ships no libc++_shared.so.

/*
#cgo LDFLAGS: -L${SRCDIR}/../libsignal/arm64-v8a
*/
import "C"
