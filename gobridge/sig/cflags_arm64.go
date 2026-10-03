// SPDX-License-Identifier: AGPL-3.0-or-later

//go:build android && arm64

package sig

// Signal's native library for this chip, downloaded by build.sh (see the libsignal workflow).

/*
#cgo LDFLAGS: -L${SRCDIR}/../libsignal/arm64-v8a
*/
import "C"
