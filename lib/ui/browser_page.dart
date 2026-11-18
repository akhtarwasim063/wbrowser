import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';

import '../blocker/blocker_service.dart';
import '../history/history_store.dart';
import 'history_page.dart';
import 'settings_page.dart';

const homeUrl = 'https://search.brave.com';

class _Tab {
  final UniqueKey key = UniqueKey();
  InAppWebViewController? controller;
  String url;
  String title = 'New tab';
  double progress = 0;
  _Tab(this.url);
}

class BrowserPage extends StatefulWidget {
  const BrowserPage({super.key});
  @override
  State<BrowserPage> createState() => _BrowserPageState();
}

class _BrowserPageState extends State<BrowserPage> {
  final _blocker = BlockerService.instance;
  final _tabs = <_Tab>[_Tab(homeUrl)];
  int _current = 0;
  final _addr = TextEditingController();
  final _addrFocus = FocusNode();

  _Tab get _tab => _tabs[_current];

  @override
  void initState() {
    super.initState();
    _blocker.addListener(_onBlockerChanged);
  }

  @override
  void dispose() {
    _blocker.removeListener(_onBlockerChanged);
    super.dispose();
  }

  void _onBlockerChanged() {
    if (mounted) setState(() {});
  }

  InAppWebViewSettings get _settings => InAppWebViewSettings(
        contentBlockers: _blocker.activeBlockers,
        javaScriptCanOpenWindowsAutomatically: false,
        mediaPlaybackRequiresUserGesture: false,
        allowsInlineMediaPlayback: true,
        useShouldOverrideUrlLoading: false,
        supportZoom: true,
        builtInZoomControls: false,
        displayZoomControls: false,
        thirdPartyCookiesEnabled: false,
        safeBrowsingEnabled: true,
        isFraudulentWebsiteWarningEnabled: true,
      );

  String _resolve(String input) {
    final t = input.trim();
    if (t.isEmpty) return homeUrl;
    final hasScheme = RegExp(r'^[a-z][a-z0-9+.-]*://', caseSensitive: false).hasMatch(t);
    if (hasScheme) return t;
    if (!t.contains(' ') && t.contains('.')) return 'https://$t';
    return 'https://search.brave.com/search?q=${Uri.encodeQueryComponent(t)}';
  }

  void _go(String input) {
    _addrFocus.unfocus();
    _tab.controller?.loadUrl(urlRequest: URLRequest(url: WebUri(_resolve(input))));
  }

  void _newTab([String url = homeUrl]) {
    setState(() {
      _tabs.add(_Tab(url));
      _current = _tabs.length - 1;
      _addr.text = '';
    });
  }

  void _closeTab(int i) {
    if (_tabs.length == 1) {
      _tabs[0].controller?.loadUrl(urlRequest: URLRequest(url: WebUri(homeUrl)));
      return;
    }
    setState(() {
      _tabs.removeAt(i);
      if (_current >= _tabs.length) _current = _tabs.length - 1;
      _addr.text = _tab.url;
    });
  }

  Future<void> _applyBlockerToTabs() async {
    for (final t in _tabs) {
      await t.controller?.setSettings(settings: _settings);
      await t.controller?.removeAllUserScripts();
      for (final s in _blocker.activeScripts) {
        await t.controller?.addUserScript(userScript: s);
      }
      await t.controller?.reload();
    }
  }

  Future<void> _toggleShield() async {
    await _blocker.setEnabled(!_blocker.enabled);
    await _applyBlockerToTabs();
  }

  void _showTabs() {
    showModalBottomSheet(
      context: context,
      builder: (_) => StatefulBuilder(
        builder: (ctx, setSheet) => SafeArea(
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            ListTile(
              title: Text('${_tabs.length} tab${_tabs.length == 1 ? '' : 's'}'),
              trailing: TextButton.icon(
                icon: const Icon(Icons.add),
                label: const Text('New'),
                onPressed: () {
                  Navigator.pop(ctx);
                  _newTab();
                },
              ),
            ),
            Flexible(
              child: ListView.builder(
                shrinkWrap: true,
                itemCount: _tabs.length,
                itemBuilder: (_, i) => ListTile(
                  selected: i == _current,
                  leading: const Icon(Icons.tab),
                  title: Text(_tabs[i].title, maxLines: 1, overflow: TextOverflow.ellipsis),
                  subtitle: Text(_tabs[i].url, maxLines: 1, overflow: TextOverflow.ellipsis),
                  trailing: IconButton(
                    icon: const Icon(Icons.close),
                    onPressed: () {
                      _closeTab(i);
                      setSheet(() {});
                      if (_tabs.isEmpty) Navigator.pop(ctx);
                    },
                  ),
                  onTap: () {
                    setState(() {
                      _current = i;
                      _addr.text = _tab.url;
                    });
                    Navigator.pop(ctx);
                  },
                ),
              ),
            ),
          ]),
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        final c = _tab.controller;
        if (c != null && await c.canGoBack()) {
          c.goBack();
        }
      },
      child: Scaffold(
        appBar: AppBar(
          titleSpacing: 8,
          title: TextField(
            controller: _addr,
            focusNode: _addrFocus,
            keyboardType: TextInputType.url,
            textInputAction: TextInputAction.go,
            onSubmitted: _go,
            onTap: () => _addr.selection = TextSelection(baseOffset: 0, extentOffset: _addr.text.length),
            decoration: InputDecoration(
              isDense: true,
              filled: true,
              hintText: 'Search or enter address',
              prefixIcon: IconButton(
                tooltip: _blocker.enabled ? 'Shields up (tap to disable)' : 'Shields down (tap to enable)',
                icon: Icon(_blocker.enabled ? Icons.shield : Icons.shield_outlined,
                    color: _blocker.enabled ? cs.primary : cs.outline),
                onPressed: _toggleShield,
              ),
              border: OutlineInputBorder(borderRadius: BorderRadius.circular(24), borderSide: BorderSide.none),
            ),
          ),
          bottom: PreferredSize(
            preferredSize: const Size.fromHeight(2),
            child: _tab.progress < 1
                ? LinearProgressIndicator(value: _tab.progress, minHeight: 2)
                : const SizedBox(height: 2),
          ),
        ),
        body: _blocker.loading && _blocker.contentBlockers.isEmpty
            ? const Center(child: CircularProgressIndicator())
            : IndexedStack(
                index: _current,
                children: [for (final t in _tabs) _buildWebView(t)],
              ),
        bottomNavigationBar: BottomAppBar(
          height: 56,
          padding: EdgeInsets.zero,
          child: Row(mainAxisAlignment: MainAxisAlignment.spaceEvenly, children: [
            IconButton(icon: const Icon(Icons.arrow_back), onPressed: () => _tab.controller?.goBack()),
            IconButton(icon: const Icon(Icons.arrow_forward), onPressed: () => _tab.controller?.goForward()),
            IconButton(icon: const Icon(Icons.refresh), onPressed: () => _tab.controller?.reload()),
            IconButton(
              icon: Badge.count(count: _tabs.length, child: const Icon(Icons.tab)),
              onPressed: _showTabs,
            ),
            PopupMenuButton<String>(
              onSelected: (v) {
                if (v == 'history') {
                  Navigator.push(context, MaterialPageRoute(builder: (_) => HistoryPage(onOpen: (u) => _go(u))));
                } else if (v == 'settings') {
                  Navigator.push(context, MaterialPageRoute(builder: (_) => const SettingsPage()))
                      .then((_) => _applyBlockerToTabs());
                } else if (v == 'new') {
                  _newTab();
                }
              },
              itemBuilder: (_) => const [
                PopupMenuItem(value: 'new', child: Text('New tab')),
                PopupMenuItem(value: 'history', child: Text('History')),
                PopupMenuItem(value: 'settings', child: Text('Shields & sync')),
              ],
            ),
          ]),
        ),
      ),
    );
  }

  Widget _buildWebView(_Tab t) {
    return InAppWebView(
      key: t.key,
      initialUrlRequest: URLRequest(url: WebUri(t.url)),
      initialSettings: _settings,
      initialUserScripts: _blocker.activeScripts,
      onWebViewCreated: (c) => t.controller = c,
      onProgressChanged: (c, p) {
        if (!mounted) return;
        setState(() => t.progress = p / 100);
      },
      onTitleChanged: (c, title) {
        t.title = title ?? t.title;
        if (mounted) setState(() {});
      },
      onUpdateVisitedHistory: (c, url, _) {
        if (url == null) return;
        t.url = url.toString();
        if (_tabs.indexOf(t) == _current && !_addrFocus.hasFocus) _addr.text = t.url;
      },
      onLoadStop: (c, url) async {
        // record history once the page finished, so URL and title match
        if (url == null) return;
        final title = await c.getTitle() ?? '';
        HistoryStore.instance.addVisit(url.toString(), title);
      },
      onCreateWindow: (c, action) async {
        // popups open as a new tab instead of a new window
        final u = action.request.url?.toString();
        if (u != null) _newTab(u);
        return true;
      },
    );
  }
}
