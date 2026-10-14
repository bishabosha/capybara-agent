package oidc

/** HTTP status and body of an API call made through a session. */
final case class Response(status: Int, body: String)

/** What to show the user after `start()`. Public information: the user
 *  opens the URL in a browser and confirms the code. */
final case class Challenge(verificationUri: String, userCode: String, expiresInSeconds: Int)

enum Status {
  /** No login has been started in this agent process. */
  case NoLogin
  /** Waiting for the user to approve the code in the browser. */
  case Pending
  /** The user approved; `session()` returns the session. */
  case Ready
  /** The code expired before the user approved; call `start()` again. */
  case Expired
  /** The user denied the login. */
  case Denied
}

trait Oidc {
  /** Opaque. Cannot be built, inspected or printed by agent code. */
  type Session

  /** Begin a device-flow login with the configured OpenID Connect provider.
   *  Returns at once; the login stays pending until the user approves.
   *  Replaces any previous login in this agent process. */
  def start(): Challenge

  /** Poll the provider once (no more often than it allows) and report the
   *  state of the current login. Safe to call from a later tool call. */
  def status(): Status

  /** The session of the current login, once `status()` is `Ready`. */
  def session(): Option[Session]

  /** GET `path` on the session's API base with the session's bearer token
   *  attached by the implementation. `path` must be relative to that base
   *  (start with `/`, no scheme, no `//`, no `..`); anything else throws. */
  def get(s: Session, path: String): Response

  /** The provider's userinfo for this session. */
  def whoami(s: Session): Response

  /** Forget the login and its tokens; later calls with `s` throw. */
  def logout(s: Session): Unit
}
