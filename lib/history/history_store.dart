import 'package:flutter/foundation.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

class HistoryEntry {
  final String id;
  final String url;
  final String title;
  final int visitedAt; // epoch ms
  final bool deleted;
  final bool synced;

  const HistoryEntry({
    required this.id,
    required this.url,
    required this.title,
    required this.visitedAt,
    this.deleted = false,
    this.synced = false,
  });

  factory HistoryEntry.create(String url, String title) {
    final now = DateTime.now().millisecondsSinceEpoch;
    return HistoryEntry(id: '${now}_${url.hashCode.toUnsigned(32)}', url: url, title: title, visitedAt: now);
  }

  Map<String, Object?> toRow() => {
        'id': id,
        'url': url,
        'title': title,
        'visited_at': visitedAt,
        'deleted': deleted ? 1 : 0,
        'synced': synced ? 1 : 0,
      };

  factory HistoryEntry.fromRow(Map<String, Object?> r) => HistoryEntry(
        id: r['id'] as String,
        url: r['url'] as String,
        title: r['title'] as String? ?? '',
        visitedAt: r['visited_at'] as int,
        deleted: r['deleted'] == 1,
        synced: r['synced'] == 1,
      );

  Map<String, Object?> toCloud() =>
      {'url': url, 'title': title, 'visitedAt': visitedAt, 'deleted': deleted};
}

/// Local history (SQLite). Works offline; HistorySync mirrors it to Firestore.
class HistoryStore extends ChangeNotifier {
  HistoryStore._();
  static final instance = HistoryStore._();

  late final Database _db;

  Future<void> init() async {
    _db = await openDatabase(
      p.join(await getDatabasesPath(), 'history.db'),
      version: 1,
      onCreate: (db, _) => db.execute('''
        CREATE TABLE history(
          id TEXT PRIMARY KEY, url TEXT NOT NULL, title TEXT,
          visited_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0,
          synced INTEGER NOT NULL DEFAULT 0)'''),
    );
  }

  /// Records a visit; collapses quick repeat visits of the same URL.
  Future<void> addVisit(String url, String title) async {
    if (!url.startsWith('http')) return;
    final last = await _db.query('history',
        where: 'deleted = 0', orderBy: 'visited_at DESC', limit: 1);
    if (last.isNotEmpty) {
      final e = HistoryEntry.fromRow(last.first);
      if (e.url == url) {
        // same page (e.g. title arrived late): update in place
        await _db.update('history', {'title': title.isEmpty ? e.title : title, 'synced': 0},
            where: 'id = ?', whereArgs: [e.id]);
        notifyListeners();
        return;
      }
    }
    await _db.insert('history', HistoryEntry.create(url, title).toRow(),
        conflictAlgorithm: ConflictAlgorithm.replace);
    notifyListeners();
  }

  Future<List<HistoryEntry>> list({String query = '', int limit = 500}) async {
    final q = query.trim();
    final rows = await _db.query('history',
        where: q.isEmpty ? 'deleted = 0' : 'deleted = 0 AND (url LIKE ? OR title LIKE ?)',
        whereArgs: q.isEmpty ? null : ['%$q%', '%$q%'],
        orderBy: 'visited_at DESC',
        limit: limit);
    return rows.map(HistoryEntry.fromRow).toList();
  }

  Future<void> delete(String id) async {
    // tombstone so the deletion syncs to other devices
    await _db.update('history', {'deleted': 1, 'synced': 0}, where: 'id = ?', whereArgs: [id]);
    notifyListeners();
  }

  Future<void> clearAll() async {
    await _db.update('history', {'deleted': 1, 'synced': 0}, where: 'deleted = 0');
    notifyListeners();
  }

  Future<List<HistoryEntry>> unsynced() async =>
      (await _db.query('history', where: 'synced = 0')).map(HistoryEntry.fromRow).toList();

  Future<void> markSynced(Iterable<String> ids) async {
    final b = _db.batch();
    for (final id in ids) {
      b.update('history', {'synced': 1}, where: 'id = ?', whereArgs: [id]);
    }
    await b.commit(noResult: true);
  }

  /// Applies an entry coming from the cloud (already synced).
  Future<void> upsertFromCloud(HistoryEntry e) async {
    await _db.insert('history', e.toRow(), conflictAlgorithm: ConflictAlgorithm.replace);
  }

  void changed() => notifyListeners();
}
