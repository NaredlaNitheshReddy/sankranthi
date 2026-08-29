/// Build-time configuration.
///
/// Values arrive via `--dart-define-from-file=config/dev.json`, which is
/// gitignored. Per REQUIREMENTS §60 nothing secret belongs here: both values
/// below are public identifiers, and no service-account key, API secret or
/// private credential is ever compiled into the APK.
///
/// Read configuration through this class and never call
/// `String.fromEnvironment` at a call site — a typo in a define name silently
/// yields an empty string, and having exactly one place to look makes that
/// visible.
abstract final class AppConfig {
  /// The Apps Script Web App `/exec` endpoint.
  ///
  /// Not a secret: every request is authorised from its own token, never from
  /// possession of the URL. It is environment-specific, which is why it is
  /// injected rather than committed.
  static const String gatewayUrl = String.fromEnvironment('GATEWAY_URL');

  /// The **Web application** OAuth client id, used as `serverClientId`.
  ///
  /// Must match the `aud` the gateway verifies. This is not the Android client
  /// id — that one is registered with Google against the package name and
  /// signing SHA-1, and is never referenced from Dart.
  static const String googleWebClientId = String.fromEnvironment(
    'GOOGLE_WEB_CLIENT_ID',
  );

  /// Whether a backend is configured at all.
  static bool get hasGateway => gatewayUrl.isNotEmpty;

  /// Whether Google sign-in can be attempted.
  static bool get hasGoogleSignIn => googleWebClientId.isNotEmpty;

  /// Every missing key, for a single actionable startup message rather than a
  /// null-ish failure later.
  static List<String> get missingKeys => <String>[
    if (gatewayUrl.isEmpty) 'GATEWAY_URL',
    if (googleWebClientId.isEmpty) 'GOOGLE_WEB_CLIENT_ID',
  ];
}
