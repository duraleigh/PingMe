// SPDX-License-Identifier: AGPL-3.0-or-later

//go:build !android

package sig

// Host tests link the reference bridge's Linux build of the library (gobridge/libsignal/host).

/*
#cgo LDFLAGS: -L${SRCDIR}/../libsignal/host
*/
import "C"
