import 'dart:collection';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart' show rootBundle;
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'filter_parser.dart';
import 'youtube_script.dart';

class FilterListSource {
  final String name;
  final String url;
  const FilterListSource(this.name, this.url);
}

const defaultLists = [
  FilterListSource('EasyList', 'https://easylist.to/easylist/easylist.txt'),
  FilterListSource('EasyPrivacy', 'https://easylist.to/easylist/easyprivacy.txt'),
  FilterListSource('uBlock filters',
      'https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt'),
];

/// Loads filter lists, converts them and hands WebViews their rules/scripts.
class BlockerService extends ChangeNotifier {
  BlockerService._();
  static final instance = BlockerService._();

  bool enabled = true;
  bool youtubeBlock = true;
  bool loading = false;
  int networkRuleCount = 0;
  int cosmeticRuleCount = 0;
  DateTime? lastUpdated;
  List<ContentBlocker> contentBlockers = [];
  UserScript? cosmeticScript;

  Future<void> init() async {
    final p = await SharedPreferences.getInstance();
    enabled = p.getBool('blocker_enabled') ?? true;
    youtubeBlock = p.getBool('youtube_block') ?? true;
    await _rebuild();
    // refresh lists in the background when older than a day
    if (lastUpdated == null || DateTime.now().difference(lastUpdated!).inHours > 24) {
      refresh();
    }
  }

  Future<void> setEnabled(bool v) async {
    enabled = v;
    (await SharedPreferences.getInstance()).setBool('blocker_enabled', v);
    notifyListeners();
  }

  Future<void> setYoutube(bool v) async {
    youtubeBlock = v;
    (await SharedPreferences.getInstance()).setBool('youtube_block', v);
    notifyListeners();
  }

  Future<File> _cacheFile(String name) async {
    final dir = await getApplicationSupportDirectory();
    return File('${dir.path}/filters_${name.replaceAll(RegExp(r'\W'), '_')}.txt');
  }

  /// Downloads all lists, then rebuilds the rule set.
  Future<void> refresh() async {
    loading = true;
    notifyListeners();
    for (final l in defaultLists) {
      try {
        final r = await http.get(Uri.parse(l.url)).timeout(const Duration(seconds: 60));
        if (r.statusCode == 200 && r.body.length > 1000) {
          await (await _cacheFile(l.name)).writeAsString(r.body);
        }
      } catch (e) {
        debugPrint('List ${l.name} failed: $e');
      }
    }
    await _rebuild();
  }

  Future<void> _rebuild() async {
    loading = true;
    notifyListeners();
    final lines = <String>[];
    lines.addAll(const LineSplitter().convert(await rootBundle.loadString('assets/filters/base.txt')));
    DateTime? newest;
    for (final l in defaultLists) {
      final f = await _cacheFile(l.name);
      if (await f.exists()) {
        lines.addAll(const LineSplitter().convert(await f.readAsString()));
        final m = await f.lastModified();
        if (newest == null || m.isAfter(newest)) newest = m;
      }
    }
    lastUpdated = newest;
    // parsing 100k+ lines: keep it off the UI isolate
    final result = await compute(_parseIsolate, lines);
    contentBlockers = result.network;
    networkRuleCount = result.network.length;
    cosmeticRuleCount = result.globalCosmetic.length +
        result.domainCosmetic.values.fold(0, (a, b) => a + b.length);
    cosmeticScript = UserScript(
      source: _cosmeticJs(result),
      injectionTime: UserScriptInjectionTime.AT_DOCUMENT_START,
    );
    loading = false;
    notifyListeners();
  }

  static ParsedFilters _parseIsolate(List<String> lines) {
    final p = FilterParser.parse(lines);
    final ex = p.exceptionDomains.map((d) => d.replaceAll('.', r'\.')).toList();
    if (ex.isNotEmpty) {
      p.network.removeWhere((r) {
        final f = r.trigger.urlFilter;
        return ex.any(f.contains);
      });
    }
    return p;
  }

  static String _cosmeticJs(ParsedFilters p) {
    final global = p.globalCosmetic.take(4000).toList();
    final byDomain = <String, List<String>>{};
    p.domainCosmetic.forEach((k, v) => byDomain[k] = v.take(200).toList());
    return '''
(function(){
  var g = ${jsonEncode(global)};
  var d = ${jsonEncode(byDomain)};
  var sels = g.slice();
  var parts = location.hostname.split('.');
  for (var i = 0; i < parts.length - 1; i++) {
    var k = parts.slice(i).join('.');
    if (d[k]) sels = sels.concat(d[k]);
  }
  if (!sels.length) return;
  var css = '';
  for (var j = 0; j < sels.length; j += 100)
    css += sels.slice(j, j + 100).join(',') + '{display:none!important}\\n';
  function apply(){
    var s = document.createElement('style'); s.textContent = css;
    (document.head || document.documentElement).appendChild(s);
  }
  if (document.head) apply();
  else document.addEventListener('DOMContentLoaded', apply);
})();
''';
  }

  List<ContentBlocker> get activeBlockers => enabled ? contentBlockers : const [];

  UnmodifiableListView<UserScript> get activeScripts {
    final s = <UserScript>[];
    if (enabled && cosmeticScript != null) s.add(cosmeticScript!);
    if (enabled && youtubeBlock) {
      s.add(UserScript(
          source: youtubeAdScript, injectionTime: UserScriptInjectionTime.AT_DOCUMENT_START));
    }
    return UnmodifiableListView(s);
  }
}
