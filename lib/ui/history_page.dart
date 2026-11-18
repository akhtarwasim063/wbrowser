import 'package:flutter/material.dart';

import '../history/history_store.dart';

class HistoryPage extends StatefulWidget {
  final void Function(String url) onOpen;
  const HistoryPage({super.key, required this.onOpen});
  @override
  State<HistoryPage> createState() => _HistoryPageState();
}

class _HistoryPageState extends State<HistoryPage> {
  final _store = HistoryStore.instance;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _store.addListener(_refresh); // live-updates when other devices sync in
  }

  @override
  void dispose() {
    _store.removeListener(_refresh);
    super.dispose();
  }

  void _refresh() {
    if (mounted) setState(() {});
  }

  String _day(DateTime d) {
    final now = DateTime.now();
    final today = DateTime(now.year, now.month, now.day);
    final day = DateTime(d.year, d.month, d.day);
    final diff = today.difference(day).inDays;
    if (diff == 0) return 'Today';
    if (diff == 1) return 'Yesterday';
    return '${d.day.toString().padLeft(2, '0')}/${d.month.toString().padLeft(2, '0')}/${d.year}';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('History'),
        actions: [
          IconButton(
            tooltip: 'Clear all',
            icon: const Icon(Icons.delete_sweep),
            onPressed: () async {
              final ok = await showDialog<bool>(
                context: context,
                builder: (c) => AlertDialog(
                  title: const Text('Clear all history?'),
                  content: const Text('This also removes it from your synced devices.'),
                  actions: [
                    TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Cancel')),
                    FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('Clear')),
                  ],
                ),
              );
              if (ok == true) await _store.clearAll();
            },
          ),
        ],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(56),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 8),
            child: TextField(
              onChanged: (v) => setState(() => _query = v),
              decoration: InputDecoration(
                isDense: true,
                filled: true,
                hintText: 'Search history',
                prefixIcon: const Icon(Icons.search),
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(24), borderSide: BorderSide.none),
              ),
            ),
          ),
        ),
      ),
      body: FutureBuilder<List<HistoryEntry>>(
        future: _store.list(query: _query),
        builder: (context, snap) {
          final items = snap.data ?? const <HistoryEntry>[];
          if (items.isEmpty) return const Center(child: Text('No history'));
          return ListView.builder(
            itemCount: items.length,
            itemBuilder: (_, i) {
              final e = items[i];
              final d = DateTime.fromMillisecondsSinceEpoch(e.visitedAt);
              final showHeader = i == 0 ||
                  _day(DateTime.fromMillisecondsSinceEpoch(items[i - 1].visitedAt)) != _day(d);
              final tile = Dismissible(
                key: ValueKey(e.id),
                background: Container(color: Colors.red),
                onDismissed: (_) => _store.delete(e.id),
                child: ListTile(
                  leading: const Icon(Icons.public),
                  title: Text(e.title.isEmpty ? e.url : e.title, maxLines: 1, overflow: TextOverflow.ellipsis),
                  subtitle: Text(e.url, maxLines: 1, overflow: TextOverflow.ellipsis),
                  trailing: Icon(e.synced ? Icons.cloud_done_outlined : Icons.cloud_off_outlined, size: 16),
                  onTap: () {
                    widget.onOpen(e.url);
                    Navigator.pop(context);
                  },
                ),
              );
              if (!showHeader) return tile;
              return Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
                  child: Text(_day(d), style: Theme.of(context).textTheme.titleSmall),
                ),
                tile,
              ]);
            },
          );
        },
      ),
    );
  }
}
