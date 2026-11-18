import 'package:flutter_inappwebview/flutter_inappwebview.dart';

/// Result of parsing one or more Adblock-Plus / uBlock style filter lists.
class ParsedFilters {
  final List<ContentBlocker> network = [];
  final List<String> globalCosmetic = [];
  final Map<String, List<String>> domainCosmetic = {};
  final Set<String> exceptionDomains = {};
  int skipped = 0;
}

/// Minimal ABP filter parser -> WebView content-blocker rules.
/// Handles `||domain^`, plain patterns, `$third-party`, `$domain=`, resource
/// types, `@@||domain^` exceptions and `##selector` cosmetic rules. Anything
/// it cannot express safely (scriptlets, procedural cosmetics, redirects...)
/// is skipped.
/// NOTE: this is the swap point for adblock-rust later.
class FilterParser {
  static const _typeMap = <String, ContentBlockerTriggerResourceType>{
    'script': ContentBlockerTriggerResourceType.SCRIPT,
    'image': ContentBlockerTriggerResourceType.IMAGE,
    'stylesheet': ContentBlockerTriggerResourceType.STYLE_SHEET,
    'xmlhttprequest': ContentBlockerTriggerResourceType.RAW,
    'media': ContentBlockerTriggerResourceType.MEDIA,
    'font': ContentBlockerTriggerResourceType.FONT,
    'subdocument': ContentBlockerTriggerResourceType.DOCUMENT,
  };
  static const _ignoredOptions = {'important', 'match-case', 'all'};
  static final _unsupportedCosmetic =
      RegExp(r':has-text|:-abp|:xpath|:matches-|:upward|:remove|:style|\+js|:contains|:not\(:');

  static ParsedFilters parse(Iterable<String> lines, {int maxNetworkRules = 60000}) {
    final out = ParsedFilters();
    for (final raw in lines) {
      final line = raw.trim();
      if (line.isEmpty || line.startsWith('!') || line.startsWith('[')) continue;

      if (line.contains('##') || line.contains('#@#') || line.contains('#?#') || line.contains('#\$#')) {
        _cosmetic(line, out);
        continue;
      }
      if (out.network.length >= maxNetworkRules) {
        out.skipped++;
        continue;
      }
      _network(line, out);
    }
    return out;
  }

  static void _cosmetic(String line, ParsedFilters out) {
    final i = line.indexOf('##');
    if (i < 0 || line.contains('#@#') || line.contains('#?#') || line.contains('#\$#')) {
      out.skipped++;
      return;
    }
    final domains = line.substring(0, i);
    final sel = line.substring(i + 2);
    if (sel.isEmpty || _unsupportedCosmetic.hasMatch(sel)) {
      out.skipped++;
      return;
    }
    if (domains.isEmpty) {
      out.globalCosmetic.add(sel);
    } else {
      for (final d in domains.split(',')) {
        if (d.startsWith('~') || d.contains('*')) continue;
        out.domainCosmetic.putIfAbsent(d, () => []).add(sel);
      }
    }
  }

  static void _network(String line, ParsedFilters out) {
    var rule = line;
    final isException = rule.startsWith('@@');
    if (isException) rule = rule.substring(2);

    String options = '';
    final dollar = rule.lastIndexOf('\$');
    if (dollar > 0 && !rule.substring(dollar).contains('/')) {
      options = rule.substring(dollar + 1);
      rule = rule.substring(0, dollar);
    }
    if (rule.isEmpty || rule.startsWith('/') && rule.endsWith('/') && rule.length > 2) {
      out.skipped++; // raw regex rules: skip
      return;
    }

    ContentBlockerTriggerLoadType? loadType;
    final types = <ContentBlockerTriggerResourceType>[];
    final ifDomain = <String>[];
    final unlessDomain = <String>[];
    for (final opt in options.isEmpty ? <String>[] : options.split(',')) {
      if (_ignoredOptions.contains(opt)) continue;
      if (opt == 'third-party' || opt == '3p') {
        loadType = ContentBlockerTriggerLoadType.THIRD_PARTY;
      } else if (opt == '~third-party' || opt == '1p') {
        loadType = ContentBlockerTriggerLoadType.FIRST_PARTY;
      } else if (_typeMap.containsKey(opt)) {
        types.add(_typeMap[opt]!);
      } else if (opt.startsWith('domain=')) {
        for (final d in opt.substring(7).split('|')) {
          if (d.startsWith('~')) {
            unlessDomain.add('*${d.substring(1)}');
          } else {
            ifDomain.add('*$d');
          }
        }
      } else {
        out.skipped++; // redirect, csp, removeparam, popup, ~type ...
        return;
      }
    }

    final domainOnly = RegExp(r'^\|\|([a-z0-9.-]+)\^?$').firstMatch(rule);
    if (isException) {
      if (domainOnly != null && options.isEmpty) out.exceptionDomains.add(domainOnly.group(1)!);
      return;
    }

    final regex = _toRegex(rule);
    if (regex == null) {
      out.skipped++;
      return;
    }
    out.network.add(ContentBlocker(
      trigger: ContentBlockerTrigger(
        urlFilter: regex,
        resourceType: types,
        loadType: loadType == null ? [] : [loadType],
        ifDomain: ifDomain,
        unlessDomain: unlessDomain,
      ),
      action: ContentBlockerAction(type: ContentBlockerActionType.BLOCK),
    ));
  }

  static String? _toRegex(String p) {
    var s = p;
    var prefix = '';
    var suffix = '';
    if (s.startsWith('||')) {
      prefix = r'^[^:]+:(//)?([^/]+\.)?';
      s = s.substring(2);
    } else if (s.startsWith('|')) {
      prefix = '^';
      s = s.substring(1);
    }
    if (s.endsWith('|')) {
      suffix = r'$';
      s = s.substring(0, s.length - 1);
    }
    if (s.endsWith('^')) s = s.substring(0, s.length - 1); // separator at end: loosen
    if (s.length < 3 && prefix.isEmpty) return null; // too generic
    final b = StringBuffer(prefix);
    for (final c in s.split('')) {
      switch (c) {
        case '*':
          b.write('.*');
        case '^':
          b.write(r'[^a-zA-Z0-9_.%-]');
        case '.' || '?' || '+' || '(' || ')' || '[' || ']' || '{' || '}' || r'$' || '|' || r'\':
          b.write('\\$c');
        default:
          b.write(c);
      }
    }
    b.write(suffix);
    return b.toString();
  }
}
