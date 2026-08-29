import 'package:flutter/material.dart';

import 'core/app_config.dart';

void main() {
  runApp(const SankranthiApp());
}

/// Scaffold placeholder.
///
/// Phase 0 deliberately ships no features: the theme lands in P3 and the
/// session gate in P6. This screen exists to prove the toolchain end to end
/// and to make a missing `--dart-define-from-file` obvious on device rather
/// than as a confusing failure later.
class SankranthiApp extends StatelessWidget {
  const SankranthiApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Sankranthi',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF1B5E20)),
      ),
      home: const _ConfigStatusScreen(),
    );
  }
}

class _ConfigStatusScreen extends StatelessWidget {
  const _ConfigStatusScreen();

  @override
  Widget build(BuildContext context) {
    final List<String> missing = AppConfig.missingKeys;
    final TextTheme text = Theme.of(context).textTheme;

    return Scaffold(
      appBar: AppBar(title: const Text('Sankranthi')),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: <Widget>[
              Text('Phase 0 — scaffold', style: text.titleLarge),
              const SizedBox(height: 8),
              Text(
                missing.isEmpty
                    ? 'Configuration loaded.'
                    : 'Not configured yet. Missing: ${missing.join(', ')}',
                textAlign: TextAlign.center,
                style: text.bodyMedium,
              ),
              if (missing.isNotEmpty) ...<Widget>[
                const SizedBox(height: 16),
                Text(
                  'Copy config/dev.example.json to config/dev.json, then run '
                  'with --dart-define-from-file=config/dev.json',
                  textAlign: TextAlign.center,
                  style: text.bodySmall,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
