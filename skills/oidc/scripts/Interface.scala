package oidc

/** HTTP status and body of an API call made through a session. */
final case class Response(status: Int, body: String)

trait Oidc {
  /** Opaque. Cannot be built, inspected or printed by agent code. */
  type Session

  /** Start a device-flow login with the configured OpenID Connect provider.
   *  The verification URL and user code are shown to the user on the agent's
   *  terminal; this call BLOCKS until the user approves (or the code expires,
   *  which throws). Returns the session for this tool call. */
  def login(): Session

  /** GET `path` on the session's API base with the session's bearer token
   *  attached by the implementation. `path` must be relative to that base
   *  (start with `/`, no scheme, no `//`, no `..`); anything else throws. */
  def get(s: Session, path: String): Response

  /** The provider's userinfo for this session. */
  def whoami(s: Session): Response

  /** Forget the session's tokens; later calls with `s` throw. */
  def logout(s: Session): Unit
}
