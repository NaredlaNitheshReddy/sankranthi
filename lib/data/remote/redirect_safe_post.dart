import 'package:dio/dio.dart';

/// The maximum number of redirect hops to follow before giving up.
const int _maxRedirectHops = 3;

/// Status codes that Apps Script uses to bounce a POST to its real host.
const Set<int> _redirectCodes = <int>{301, 302, 303, 307, 308};

/// POSTs [body] to [url], re-issuing the POST across redirects.
///
/// ## Why this exists
///
/// An Apps Script Web App answers a POST to `/exec` with a **302** to
/// `script.googleusercontent.com`. Nothing reaches `doPost` unless the POST is
/// re-issued to that Location, so without this helper every gateway call fails.
///
/// ### What actually happens on our platform — measured, not assumed
///
/// `dart:io`'s `HttpClient` only auto-follows redirects for GET and HEAD. For a
/// POST it hands the 302 straight back, **even with `followRedirects = true`
/// and `maxRedirects = 5`**. So on Android/native:
///
/// * `dio` throws `DioException [bad response]` — its default `validateStatus`
///   rejects 302.
/// * `package:http` returns a 302 with an empty body, which then fails at
///   `jsonDecode`.
///
/// Either way it fails *loudly*. That is the good case, and
/// `redirect_safe_post_test.dart` pins it.
///
/// ### The silent variant, and why it is still worth knowing
///
/// Most other stacks — browsers, `curl -L`, Python `requests`, JS `fetch` —
/// *do* convert POST to GET on 302/303, as RFC 9110 permits. There the request
/// returns **HTTP 200 with a JSON body**, because the script's `doGet` handler
/// answered instead of `doPost`. Nothing throws, the status looks fine, and the
/// payload silently evaporated.
///
/// Two consequences:
///
/// 1. Do not port a working recipe from curl or Python and assume it maps —
///    those tools hid this for you.
/// 2. If a Flutter **web** target is ever added, `BrowserClient` delegates
///    redirects to the browser and the silent failure mode becomes live. There
///    is no web target today (see CLAUDE.md).
Future<Response<T>> redirectSafePost<T>(
  Dio dio,
  String url, {
  required Object body,
  Map<String, String>? headers,
}) async {
  String target = url;

  for (int hop = 0; hop <= _maxRedirectHops; hop++) {
    final Response<T> response = await dio.post<T>(
      target,
      data: body,
      options: Options(
        headers: headers,
        followRedirects: false,
        // Redirects are not errors here — we handle them — so let them through
        // to be inspected rather than thrown.
        validateStatus: (int? status) =>
            status != null && (status < 400 || _redirectCodes.contains(status)),
      ),
    );

    if (!_redirectCodes.contains(response.statusCode)) {
      return response;
    }

    final String? location = _locationOf(response);
    if (location == null || location.isEmpty) {
      throw DioException(
        requestOptions: response.requestOptions,
        response: response,
        type: DioExceptionType.badResponse,
        error:
            'Gateway returned ${response.statusCode} with no Location header.',
      );
    }

    // Resolve relative Locations against the URL we just called.
    target = Uri.parse(target).resolve(location).toString();
  }

  throw DioException(
    requestOptions: RequestOptions(path: url),
    type: DioExceptionType.badResponse,
    error:
        'Gateway exceeded $_maxRedirectHops redirect hops starting from $url; '
        'refusing to follow further.',
  );
}

String? _locationOf(Response<Object?> response) {
  final List<String>? values = response.headers.map['location'];
  if (values == null || values.isEmpty) {
    return null;
  }
  return values.first;
}
