import 'package:flutter/material.dart';

import '../blocker/blocker_service.dart';
import '../history/history_sync.dart';

class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});
  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  final _b = BlockerService.instance;
  final _s = HistorySync.instance;
  final _email = TextEditingController();
  final _pass = TextEditingController();

  @override
  void initState() {
    super.initState();
    _b.addListener(_r);
    _s.addListener(_r);
  }

  @override
  void dispose() {
    _b.removeListener(_r);
    _s.removeListener(_r);
    super.dispose();
  }

  void _r() {
    if (mounted) setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Shields & sync')),
      body: ListView(children: [
        const _Header('Shields'),
        SwitchListTile(
          title: const Text('Block ads & trackers'),
          subtitle: Text('${_b.networkRuleCount} network rules, ${_b.cosmeticRuleCount} cosmetic rules'),
          value: _b.enabled,
          onChanged: _b.setEnabled,
        ),
        SwitchListTile(
          title: const Text('YouTube ad blocking'),
          subtitle: const Text('Experimental. YouTube changes often, so it may need updates.'),
          value: _b.youtubeBlock,
          onChanged: _b.setYoutube,
        ),
        ListTile(
          title: const Text('Update filter lists'),
          subtitle: Text(_b.loading
              ? 'Updating...'
              : _b.lastUpdated == null
                  ? 'Using built-in list only'
                  : 'Last updated ${_b.lastUpdated}'),
          trailing: _b.loading
              ? const SizedBox(width: 20, height: 20, child: CircularProgressIndicator(strokeWidth: 2))
              : const Icon(Icons.refresh),
          onTap: _b.loading ? null : _b.refresh,
        ),
        const Divider(),
        const _Header('History sync (Firebase)'),
        if (!_s.firebaseReady)
          ListTile(
            leading: const Icon(Icons.cloud_off),
            title: const Text('Firebase not set up'),
            subtitle: Text(_s.error ?? 'History stays on this device.'),
          )
        else if (_s.signedIn) ...[
          ListTile(
            leading: const Icon(Icons.cloud_done),
            title: Text(_s.user?.email ?? 'Signed in'),
            subtitle: const Text('History syncs automatically'),
          ),
          ListTile(title: const Text('Sync now'), leading: const Icon(Icons.sync), onTap: _s.pushNow),
          ListTile(title: const Text('Sign out'), leading: const Icon(Icons.logout), onTap: _s.signOut),
        ] else
          Padding(
            padding: const EdgeInsets.all(16),
            child: Column(children: [
              TextField(
                controller: _email,
                keyboardType: TextInputType.emailAddress,
                decoration: const InputDecoration(labelText: 'Email'),
              ),
              TextField(
                controller: _pass,
                obscureText: true,
                decoration: const InputDecoration(labelText: 'Password (min 6 chars)'),
              ),
              const SizedBox(height: 12),
              FilledButton(
                onPressed: () => _s.signIn(_email.text.trim(), _pass.text),
                child: const Text('Sign in / create account'),
              ),
              if (_s.error != null)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(_s.error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
                ),
            ]),
          ),
      ]),
    );
  }
}

class _Header extends StatelessWidget {
  final String text;
  const _Header(this.text);
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
        child: Text(text, style: Theme.of(context).textTheme.titleSmall?.copyWith(color: Theme.of(context).colorScheme.primary)),
      );
}
