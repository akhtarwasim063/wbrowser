import 'dart:async';

import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../firebase_options.dart';
import 'history_store.dart';

/// Syncs local history with Firestore: users/{uid}/history/{entryId}.
/// Push: rows with synced=0. Pull: realtime listener on the same collection.
/// Deletes are tombstones (deleted=true) so other devices drop them too.
class HistorySync extends ChangeNotifier {
  HistorySync._();
  static final instance = HistorySync._();

  bool firebaseReady = false;
  String? error;
  StreamSubscription<QuerySnapshot<Map<String, dynamic>>>? _sub;
  Timer? _debounce;

  User? get user => firebaseReady ? FirebaseAuth.instance.currentUser : null;
  bool get signedIn => user != null;

  Future<void> init() async {
    try {
      await Firebase.initializeApp(options: DefaultFirebaseOptions.currentPlatform);
      firebaseReady = true;
      FirebaseAuth.instance.authStateChanges().listen((_) => _restart());
      HistoryStore.instance.addListener(_schedulePush);
      _restart();
    } catch (e) {
      firebaseReady = false;
      error = 'Firebase not configured (run flutterfire configure). History stays local.';
      debugPrint('Firebase init failed: $e');
    }
    notifyListeners();
  }

  Future<void> signIn(String email, String password) async {
    error = null;
    try {
      await FirebaseAuth.instance.signInWithEmailAndPassword(email: email, password: password);
    } on FirebaseAuthException catch (e) {
      if (e.code == 'user-not-found' || e.code == 'invalid-credential') {
        try {
          await FirebaseAuth.instance.createUserWithEmailAndPassword(email: email, password: password);
        } on FirebaseAuthException catch (e2) {
          error = e2.message ?? e2.code;
        }
      } else {
        error = e.message ?? e.code;
      }
    }
    notifyListeners();
  }

  Future<void> signOut() async {
    await FirebaseAuth.instance.signOut();
    notifyListeners();
  }

  CollectionReference<Map<String, dynamic>>? get _col {
    final u = user;
    if (u == null) return null;
    return FirebaseFirestore.instance.collection('users').doc(u.uid).collection('history');
  }

  void _restart() {
    _sub?.cancel();
    _sub = null;
    final col = _col;
    if (col == null) {
      notifyListeners();
      return;
    }
    _sub = col.snapshots().listen((snap) async {
      var any = false;
      for (final ch in snap.docChanges) {
        final d = ch.doc.data();
        if (d == null) continue;
        await HistoryStore.instance.upsertFromCloud(HistoryEntry(
          id: ch.doc.id,
          url: d['url'] as String? ?? '',
          title: d['title'] as String? ?? '',
          visitedAt: (d['visitedAt'] as num?)?.toInt() ?? 0,
          deleted: d['deleted'] == true,
          synced: true,
        ));
        any = true;
      }
      if (any) HistoryStore.instance.changed();
    }, onError: (e) {
      error = 'Sync error: $e';
      notifyListeners();
    });
    _schedulePush();
    notifyListeners();
  }

  void _schedulePush() {
    _debounce?.cancel();
    _debounce = Timer(const Duration(seconds: 2), pushNow);
  }

  Future<void> pushNow() async {
    final col = _col;
    if (col == null) return;
    try {
      final pending = await HistoryStore.instance.unsynced();
      for (var i = 0; i < pending.length; i += 400) {
        final chunk = pending.skip(i).take(400);
        final batch = FirebaseFirestore.instance.batch();
        for (final e in chunk) {
          batch.set(col.doc(e.id), e.toCloud(), SetOptions(merge: true));
        }
        await batch.commit();
        await HistoryStore.instance.markSynced(chunk.map((e) => e.id));
      }
      (await SharedPreferences.getInstance())
          .setInt('last_sync', DateTime.now().millisecondsSinceEpoch);
    } catch (e) {
      error = 'Sync error: $e';
      notifyListeners();
    }
  }
}
