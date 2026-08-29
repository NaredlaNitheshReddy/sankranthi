import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sankranthi/data/remote/redirect_safe_post.dart';

/// A stand-in for an Apps Script Web App: `/exec` bounces with a 302, and the
/// target reports which handler ran, the way `doPost` vs `doGet` would.
class FakeAppsScript {
  FakeAppsScript._(this._server, this.base);

  final HttpServer _server;
  final String base;

  /// Every request the server actually received, in order.
  final List<String> requests = <String>[];

  /// How many hops `/exec` should bounce through before landing. 1 = Apps
  /// Script's real behaviour.
  int hops = 1;

  /// When false, the redirect omits its Location header.
  bool sendLocation = true;

  /// When true, Location is emitted as a relative path rather than absolute.
  bool relativeLocation = false;

  static Future<FakeAppsScript> start() async {
    final HttpServer server = await HttpServer.bind(
      InternetAddress.loopbackIPv4,
      0,
    );
    final FakeAppsScript fake = FakeAppsScript._(
      server,
      'http://${server.address.host}:${server.port}',
    );
    unawaited(server.forEach(fake._handle));
    return fake;
  }

  Future<void> _handle(HttpRequest req) async {
    final String body = await utf8.decoder.bind(req).join();
    requests.add('${req.method} ${req.uri.path} $body');

    final String path = req.uri.path;
    final int? hop = path.startsWith('/hop')
        ? int.tryParse(path.substring(4))
        : null;

    if (path == '/exec' || hop != null) {
      final int next = (hop ?? 0) + 1;
      if (next <= hops) {
        req.response.statusCode = 302;
        if (sendLocation) {
          req.response.headers.set(
            'location',
            relativeLocation ? '/hop$next' : '$base/hop$next',
          );
        }
        await req.response.close();
        return;
      }
    }

    req.response
      ..statusCode = 200
      ..headers.contentType = ContentType.json
      ..write(
        jsonEncode(<String, Object?>{
          'handler': req.method == 'POST' ? 'doPost' : 'doGet',
          'received': body,
        }),
      );
    await req.response.close();
  }

  Future<void> stop() => _server.close(force: true);
}

Map<String, Object?> asMap(Object? data) {
  if (data is Map) {
    return data.cast<String, Object?>();
  }
  return (jsonDecode(data! as String) as Map<Object?, Object?>)
      .cast<String, Object?>();
}

void main() {
  late FakeAppsScript fake;
  late Dio dio;

  setUp(() async {
    fake = await FakeAppsScript.start();
    dio = Dio();
  });

  tearDown(() async {
    dio.close(force: true);
    await fake.stop();
  });

  const Map<String, Object?> payload = <String, Object?>{
    'action': 'sync',
    'lastSeq': 41,
  };

  group('the trap', () {
    // Pins the platform behaviour this helper exists for. dart:io does NOT
    // auto-follow a redirect for a non-GET/HEAD method, even with
    // followRedirects true -- so a plain POST never reaches doPost.
    test('a plain dio POST never reaches the redirect target', () async {
      await expectLater(
        dio.post<Object?>('${fake.base}/exec', data: payload),
        throwsA(
          isA<DioException>().having(
            (DioException e) => e.response?.statusCode,
            'statusCode',
            302,
          ),
        ),
      );

      // The decisive assertion: the request stopped at /exec. Whatever the
      // client reported, the payload never arrived anywhere useful.
      expect(fake.requests, hasLength(1));
      expect(fake.requests.single, startsWith('POST /exec'));
    });

    test('dart:io does not auto-follow, so nothing silently becomes a GET',
        () async {
      final HttpClient client = HttpClient();
      final HttpClientRequest req = await client.postUrl(
        Uri.parse('${fake.base}/exec'),
      );
      req.followRedirects = true;
      req.maxRedirects = 5;
      req.headers.contentType = ContentType.json;
      req.write(jsonEncode(payload));
      final HttpClientResponse res = await req.close();

      expect(res.statusCode, 302);
      expect(fake.requests, hasLength(1));
      expect(
        fake.requests.single,
        isNot(contains('GET')),
        reason: 'If this ever fails, the platform started converting POST to '
            'GET and the silent-doGet-success failure mode is now live.',
      );
      client.close(force: true);
    });
  });

  group('redirectSafePost', () {
    test('re-issues the POST so the body reaches doPost intact', () async {
      final Response<Object?> res = await redirectSafePost<Object?>(
        dio,
        '${fake.base}/exec',
        body: payload,
      );

      expect(res.statusCode, 200);
      final Map<String, Object?> decoded = asMap(res.data);
      expect(decoded['handler'], 'doPost');
      expect(jsonDecode(decoded['received']! as String), payload);

      expect(fake.requests, hasLength(2));
      expect(fake.requests[0], startsWith('POST /exec'));
      expect(fake.requests[1], startsWith('POST /hop1'));
    });

    test('resolves a relative Location against the current URL', () async {
      fake.relativeLocation = true;

      final Response<Object?> res = await redirectSafePost<Object?>(
        dio,
        '${fake.base}/exec',
        body: payload,
      );

      expect(asMap(res.data)['handler'], 'doPost');
    });

    test('does not follow a redirect that omits Location', () async {
      fake.sendLocation = false;

      await expectLater(
        redirectSafePost<Object?>(dio, '${fake.base}/exec', body: payload),
        throwsA(isA<DioException>()),
      );
      expect(fake.requests, hasLength(1));
    });

    test('gives up rather than looping forever', () async {
      fake.hops = 99;

      await expectLater(
        redirectSafePost<Object?>(dio, '${fake.base}/exec', body: payload),
        throwsA(isA<DioException>()),
      );
      // Bounded: the initial call plus the hop limit, and no more.
      expect(fake.requests.length, lessThanOrEqualTo(5));
    });
  });
}
