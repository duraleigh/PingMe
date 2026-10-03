// SPDX-License-Identifier: AGPL-3.0-or-later

//go:build android && amd64

package sig

/*
#cgo LDFLAGS: -L${SRCDIR}/../libsignal/x86_64
*/
import "C"
