import 'package:flutter_test/flutter_test.dart';
import 'package:sankranthi/core/app_config.dart';

void main() {
  // `flutter test` runs without --dart-define-from-file, so these assert the
  // unconfigured case. That is the case worth pinning: a missing define yields
  // an empty string rather than an error, so the app must detect it explicitly
  // instead of failing later with an unhelpful network error.
  group('AppConfig with no defines', () {
    test('reports no gateway', () {
      expect(AppConfig.gatewayUrl, isEmpty);
      expect(AppConfig.hasGateway, isFalse);
    });

    test('reports no Google sign-in', () {
      expect(AppConfig.googleWebClientId, isEmpty);
      expect(AppConfig.hasGoogleSignIn, isFalse);
    });

    test('names every missing key so startup can say what to fix', () {
      expect(AppConfig.missingKeys, <String>[
        'GATEWAY_URL',
        'GOOGLE_WEB_CLIENT_ID',
      ]);
    });
  });
}
