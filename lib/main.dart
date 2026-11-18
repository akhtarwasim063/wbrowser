import 'package:flutter/material.dart';

import 'blocker/blocker_service.dart';
import 'history/history_store.dart';
import 'history/history_sync.dart';
import 'ui/browser_page.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await HistoryStore.instance.init();
  await BlockerService.instance.init(); // loads cached lists (fast); refresh runs in background
  HistorySync.instance.init(); // not awaited: app must start even without Firebase
  runApp(const WBrowserApp());
}

class WBrowserApp extends StatelessWidget {
  const WBrowserApp({super.key});

  @override
  Widget build(BuildContext context) {
    const brand = Color(0xFFFB542B);
    return MaterialApp(
      title: 'WBrowser',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: brand, useMaterial3: true),
      darkTheme: ThemeData(colorSchemeSeed: brand, useMaterial3: true, brightness: Brightness.dark),
      home: const BrowserPage(),
    );
  }
}
