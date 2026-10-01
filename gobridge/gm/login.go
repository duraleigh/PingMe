// SPDX-License-Identifier: AGPL-3.0-or-later

package gm

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"go.mau.fi/mautrix-gmessages/pkg/libgm"
	"go.mau.fi/mautrix-gmessages/pkg/libgm/events"
	"go.mau.fi/util/exhttp"
)

// Google account pairing (BUILD_PLAN.md P3.2, DESIGN.md 5.4), following libgm's
// GoogleLoginProcess in mautrix-gmessages pkg/connector/login.go step for step:
// cookies from Google sign-in -> StartGaiaPairing (gives the emoji) -> the user taps the
// same emoji in Google Messages -> FinishGaiaPairing (gives the session).

// Cookie names the pairing needs, as the reference bridge lists them. The first six are
// required; __Secure-1PSIDTS helps the session last longer.
const CookieNames = "SID,HSID,OSID,SSID,APISID,SAPISID,__Secure-1PSIDTS"

// SignInURL is the page that signs the user in and lands on the Messages for web config,
// which sets OSID; its cookies live on .google.com and messages.google.com.
const SignInURL = "https://accounts.google.com/AccountChooser?continue=https://messages.google.com/web/config"

var requiredCookies = []string{"SID", "HSID", "OSID", "SSID", "APISID", "SAPISID"}

// LoginResult is what a finished pairing gives Kotlin to keep.
type LoginResult struct {
	// The session, as JSON, for NewSession later. It holds the pairing keys and cookies.
	Auth string `json:"auth"`
	// "<email>/<device number>": the phone's identity, stable across re-pairings.
	PhoneID string `json:"phoneId"`
	// The Google account's email, for the account's name.
	Email string `json:"email"`
}

// Login is one pairing attempt.
type Login struct {
	client   *libgm.Client
	session  *libgm.PairingSession
	bgCancel context.CancelFunc
}

// NewLogin takes the sign-in cookies as a JSON object of name to value and checks that
// every required one is there.
func NewLogin(cookiesJSON string) (*Login, error) {
	var cookies map[string]string
	if err := json.Unmarshal([]byte(cookiesJSON), &cookies); err != nil {
		return nil, fmt.Errorf("cookies are not a JSON object: %w", err)
	}
	var missing []string
	for _, name := range requiredCookies {
		if cookies[name] == "" {
			missing = append(missing, name)
		}
	}
	if len(missing) > 0 {
		return nil, fmt.Errorf("%w: %v", ErrMissingCookies, missing)
	}
	auth := libgm.NewAuthData()
	auth.Cookies = cookies
	client := libgm.NewClient(auth, nil, newLogger("libgm-login"), exhttp.SensibleClientSettings)
	client.SetEventHandler(func(evt any) {
		client.Logger.Debug().Type("event_type", evt).Msg("Event before pairing finished")
	})
	return &Login{client: client}, nil
}

// ErrMissingCookies means Google sign-in did not give every cookie the pairing needs.
var ErrMissingCookies = errors.New("PAIR_MISSING_COOKIES: sign-in cookies are incomplete")

// Start asks Google to begin pairing and returns the emoji the user must tap on the phone.
func (l *Login) Start() (string, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	if err := l.client.FetchConfig(ctx); err != nil {
		return "", fmt.Errorf("%w: %w", ErrPairStart, err)
	}
	bgCtx, bgCancel := context.WithCancel(l.client.Logger.WithContext(context.Background()))
	l.bgCancel = bgCancel
	emoji, session, err := l.client.StartGaiaPairing(ctx, bgCtx)
	if err != nil {
		l.Cancel()
		return "", pairingError(ErrPairStart, err)
	}
	l.session = session
	return emoji, nil
}

// Finish waits for the user to tap the emoji in Google Messages (up to a few minutes) and
// returns the LoginResult as JSON.
func (l *Login) Finish() (string, error) {
	if l.session == nil {
		return "", errors.New("Start must succeed before Finish")
	}
	defer l.Cancel()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	phoneID, err := l.client.FinishGaiaPairing(ctx, l.session)
	if err != nil {
		return "", pairingError(ErrPairFinish, err)
	}
	authJSON, err := json.Marshal(l.client.AuthData)
	if err != nil {
		return "", fmt.Errorf("could not save the session: %w", err)
	}
	result := LoginResult{
		Auth:    string(authJSON),
		PhoneID: phoneID,
		Email:   l.client.AuthData.Mobile.GetSourceID(),
	}
	out, err := json.Marshal(result)
	if err != nil {
		return "", err
	}
	return string(out), nil
}

// Cancel stops the attempt; safe to call more than once.
func (l *Login) Cancel() {
	if l.bgCancel != nil {
		l.bgCancel()
		l.bgCancel = nil
	}
	l.client.Disconnect()
}

// Pairing failures Kotlin tells apart by the code before the colon.
var (
	ErrPairStart       = errors.New("PAIR_START_FAILED: could not start pairing")
	ErrPairFinish      = errors.New("PAIR_FINISH_FAILED: pairing did not finish")
	ErrNoDevices       = errors.New("PAIR_NO_DEVICES: no phone with Google Messages is signed in to this account")
	ErrPhoneNoResponse = errors.New("PAIR_PHONE_NOT_RESPONDING: the phone did not answer")
	ErrNoPermission    = errors.New("PAIR_NO_PERMISSION: Google refused the pairing for this account")
	ErrWrongEmoji      = errors.New("PAIR_WRONG_EMOJI: the wrong emoji was tapped on the phone")
	ErrCancelled       = errors.New("PAIR_CANCELLED: pairing was cancelled on the phone")
	ErrTimeout         = errors.New("PAIR_TIMEOUT: the emoji was not confirmed in time")
)

func pairingError(stage error, err error) error {
	switch {
	case errors.Is(err, libgm.ErrNoDevicesFound):
		return fmt.Errorf("%w: %w", ErrNoDevices, err)
	case errors.Is(err, libgm.ErrPairingInitTimeout):
		return fmt.Errorf("%w: %w", ErrPhoneNoResponse, err)
	case errors.Is(err, events.ErrCallerNoPermission):
		return fmt.Errorf("%w: %w", ErrNoPermission, err)
	case errors.Is(err, libgm.ErrIncorrectEmoji):
		return fmt.Errorf("%w: %w", ErrWrongEmoji, err)
	case errors.Is(err, libgm.ErrPairingCancelled):
		return fmt.Errorf("%w: %w", ErrCancelled, err)
	case errors.Is(err, libgm.ErrPairingTimeout):
		return fmt.Errorf("%w: %w", ErrTimeout, err)
	default:
		return fmt.Errorf("%w: %w", stage, err)
	}
}
