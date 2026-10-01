// SPDX-License-Identifier: AGPL-3.0-or-later

//go:build tools

package gobridge

// Keeps golang.org/x/mobile in go.mod, so `gomobile bind` finds its bind package here.
import _ "golang.org/x/mobile/bind"
